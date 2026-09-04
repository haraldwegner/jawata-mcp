package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jface.text.Document;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.CheckedChange;
import org.jawata.mcp.refactoring.JdtRefactoringEngine;
import org.jawata.mcp.refactoring.PreparedRefactoring;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.refactoring.atoms.DeleteAtom;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Fowler — <b>Inline Class</b> (row 17). A class that is not doing enough to justify
 * itself has its features folded into the one class that uses it, and then it goes.
 *
 * <p>A delegate of {@link InlineTool} (kind {@code class}); not registered standalone.
 * Routed from {@code lazy_class}, which is the smell that says a class has stopped
 * earning its keep.</p>
 *
 * <h2>What it refuses, and why each refusal is the operation's honesty</h2>
 *
 * <p>Inlining a class is a deletion, so every precondition here exists because getting it
 * wrong destroys code rather than merely reshaping it:</p>
 *
 * <ul>
 *   <li><b>Exactly one class may reference it.</b> Inline Class folds a class INTO
 *       something; with two users there is no single destination, and choosing one would
 *       leave the other reaching for a type that no longer exists.</li>
 *   <li><b>No subtypes.</b> A superclass cannot be inlined into its own user without
 *       deciding what happens to everything that extends it — a different refactoring.</li>
 *   <li><b>The user holds exactly one field of its type.</b> That field is what every
 *       access is rewritten through, and with two the rewrite would have to pick.</li>
 *   <li><b>That field has no lifecycle.</b> Its own initializer is fine, and so is a
 *       {@code final} field assigned in a constructor — that is how Java spells an
 *       initializer needing a constructor argument, and the compiler already guarantees
 *       one assignment per path. A non-final field assigned after construction, or any
 *       field with a setter, has a lifecycle, and folding the class away silently
 *       discards it.</li>
 *   <li><b>The class declares no constructor with a body.</b> Construction logic has
 *       nowhere to go once there is no construction.</li>
 *   <li><b>No member name collides</b> with one already in the absorbing class. Two
 *       members of one name is not an inline, it is a merge, and merging is a decision.</li>
 * </ul>
 *
 * <p>The deletion itself is {@link DeleteAtom}, which was built for exactly this and had
 * no production caller until now: it answers NO to every offer the Eclipse engine makes to
 * widen a deletion, so the file that goes is the file that was named.</p>
 */
public class InlineClassTool extends AbstractRefactoringTool {

    public InlineClassTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "inline_class";
    }

    @Override
    public String getDescription() {
        return "Inline Class — fold a class that is not earning its keep into the single "
            + "class that uses it, and delete it. Delegate of inline.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file declaring the class to inline."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of a caret in that class."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column of that caret."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (filePathStr == null || filePathStr.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "filePath is required");
        }
        if (line < 0 || column < 0) {
            return ToolResponse.invalidCoordinates(line, column,
                "line and column are required and must be zero-based non-negative integers");
        }

        try {
            Path filePath = service.getPathUtils().resolve(filePathStr);
            IType source = service.getTypeAtPosition(filePath, line, column);
            if (source == null || source.getCompilationUnit() == null) {
                return ToolResponse.symbolNotFound(
                    "No source type at " + filePathStr + ":" + line + ":" + column);
            }
            IType[] subtypes = service.getSearchService().getAllSubtypes(source);
            if (subtypes != null && subtypes.length > 0) {
                return ToolResponse.invalidParameter("position",
                    source.getElementName() + " has " + subtypes.length + " subtype(s), and"
                        + " folding a superclass into one of its users leaves them extending"
                        + " a type that is gone. Remove the subclasses first (inline"
                        + " kind=subclass) or inline something else.");
            }

            // WHO USES IT. One user is the whole premise: Inline Class folds a class INTO
            // something, and with two there is no single destination.
            List<SearchMatch> references =
                org.jawata.mcp.refactoring.CompleteReferences.of(service, source);
            Set<ICompilationUnit> users = new LinkedHashSet<>();
            for (SearchMatch match : references) {
                if (match.getElement() instanceof IJavaElement element) {
                    ICompilationUnit unit = (ICompilationUnit) element
                        .getAncestor(IJavaElement.COMPILATION_UNIT);
                    if (unit != null && !unit.equals(source.getCompilationUnit())) {
                        users.add(unit);
                    }
                }
            }
            if (users.size() != 1) {
                return ToolResponse.invalidParameter("position",
                    users.isEmpty()
                        ? source.getElementName() + " is referenced by nothing, so there is"
                            + " no class to fold it into. If it is genuinely dead, delete it."
                        : source.getElementName() + " is referenced by " + users.size()
                            + " classes, and Inline Class folds a class INTO one. Choosing"
                            + " one would leave the others reaching for a type that is gone.");
            }
            ICompilationUnit absorberCu = users.iterator().next();
            return inline(service, source, absorberCu, arguments);
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    private ToolResponse inline(IJdtService service, IType source, ICompilationUnit absorberCu,
                                JsonNode arguments) throws Exception {
        CompilationUnit sourceAst = parse(source.getCompilationUnit());
        AbstractTypeDeclaration sourceType = typeNamed(sourceAst, source.getElementName());
        if (sourceType == null) {
            return ToolResponse.symbolNotFound(
                "could not locate the body of " + source.getElementName());
        }
        for (Object member : sourceType.bodyDeclarations()) {
            if (member instanceof MethodDeclaration method && method.isConstructor()
                    && method.getBody() != null && !method.getBody().statements().isEmpty()) {
                return ToolResponse.invalidParameter("position",
                    source.getElementName() + " has a constructor with a body, and once the"
                        + " class is gone there is no construction for that logic to run in.");
            }
        }

        CompilationUnit absorberAst = parse(absorberCu);
        AbstractTypeDeclaration absorber = firstType(absorberAst);
        if (absorber == null) {
            return ToolResponse.symbolNotFound("could not locate the absorbing type");
        }

        List<VariableDeclarationFragment> holders = new ArrayList<>();
        boolean holderIsFinal = false;
        for (Object member : absorber.bodyDeclarations()) {
            if (member instanceof FieldDeclaration field
                    && source.getElementName().equals(field.getType().toString())) {
                for (Object fragment : field.fragments()) {
                    holders.add((VariableDeclarationFragment) fragment);
                    holderIsFinal =
                        org.eclipse.jdt.core.dom.Modifier.isFinal(field.getModifiers());
                }
            }
        }
        if (holders.size() != 1) {
            return ToolResponse.invalidParameter("position",
                holders.isEmpty()
                    ? absorber.getName().getIdentifier() + " holds no field of type "
                        + source.getElementName() + ", so there is nothing to rewrite the"
                        + " accesses through."
                    : absorber.getName().getIdentifier() + " holds " + holders.size()
                        + " fields of type " + source.getElementName()
                        + ", and the rewrite would have to pick one.");
        }
        VariableDeclarationFragment holder = holders.get(0);
        String holderName = holder.getName().getIdentifier();

        if (writtenOutsideItsInitializer(absorberAst, holderName, holderIsFinal)) {
            return ToolResponse.invalidParameter("position",
                "'" + holderName + "' is assigned somewhere other than its own initializer,"
                    + " so it has a lifecycle; folding the class away would discard it.");
        }

        Set<String> existing = memberNames(absorber);
        // A FORWARDER OF THE SAME NAME IS NOT A COLLISION — it is the thing being inlined.
        // When the absorber's member does nothing but call the holder's member of that
        // name, the two are one operation written twice, and Fowler's Inline Class replaces
        // the forwarder with the body it forwards to. Upstream's FilterManager forwards
        // BOTH of its methods to FilterChain under the same two names, which is what the
        // pattern's public face normally looks like; refusing it refused the row's own
        // canonical shape. Anything else of a shared name is still a merge, and still
        // refused, because then two DIFFERENT operations claim one name.
        Set<String> forwarders = forwardersTo(absorber, holderName, sourceType);
        existing.removeAll(forwarders);
        List<BodyDeclaration> moving = new ArrayList<>();
        for (Object member : sourceType.bodyDeclarations()) {
            if (member instanceof MethodDeclaration method && method.isConstructor()) {
                continue;
            }
            BodyDeclaration declaration = (BodyDeclaration) member;
            for (String name : namesOf(declaration)) {
                if (existing.contains(name)) {
                    return ToolResponse.invalidParameter("position",
                        "'" + name + "' exists in both classes. Two members of one name is"
                            + " not an inline, it is a merge, and merging is a decision.");
                }
            }
            moving.add(declaration);
        }

        ASTRewrite rewrite = ASTRewrite.create(absorberAst.getAST());
        int rewritten = dropHolderQualifier(absorberAst, holderName, rewrite, forwarders);
        unlinkDeletedType(absorberAst, source.getElementName(), rewrite);
        rewrite.remove(holder.getParent(), null);
        // AND WHATEVER ASSIGNED IT. A final field built in a constructor — `filterChain =
        // new FilterChain();` — is now a write to a field that no longer exists, and the
        // compile gate is what said so: this path could not be reached until a constructor
        // assignment stopped being a refusal one edit ago. Dropping the statement loses
        // nothing, because a class with a constructor BODY is refused earlier, so the
        // right-hand side can only be the implicit no-argument construction.
        for (org.eclipse.jdt.core.dom.Assignment write
                : assignmentsTo(absorberAst, holderName)) {
            rewrite.remove(write.getParent() instanceof ExpressionStatement statement
                ? statement : write, null);
        }
        ListRewrite members = rewrite.getListRewrite(absorber,
            absorber.getBodyDeclarationsProperty());
        // The superseded forwarders go. Each one's arriving replacement carries the body it
        // was calling, so keeping it would leave two methods of one signature — and once
        // the receiver is stripped, one of them calling itself.
        for (Object member : absorber.bodyDeclarations()) {
            if (member instanceof MethodDeclaration method
                    && forwarders.contains(method.getName().getIdentifier())) {
                rewrite.remove(method, null);
            }
        }
        for (BodyDeclaration declaration : moving) {
            members.insertLast((BodyDeclaration) ASTNode.copySubtree(
                absorberAst.getAST(), declaration), null);
        }

        Map<IFile, List<TextEdit>> edits = new LinkedHashMap<>();
        edits.put((IFile) absorberCu.getResource(),
            List.of(rewrite.rewriteAST(new Document(absorberCu.getSource()),
                org.jawata.mcp.tools.shared.FormatterOptions.forGeneratedCode(absorberAst))));

        String label = "inline class " + source.getElementName() + " into "
            + absorber.getName().getIdentifier() + " (" + moving.size() + " member(s), "
            + rewritten + " access(es) unqualified)";
        CheckedChange deletion = DeleteAtom.delete(
            new IJavaElement[] { source.getCompilationUnit() },
            "delete " + source.getElementName(), new JdtRefactoringEngine());
        if (deletion.isRefused()) {
            return ToolResponse.error("REFACTORING_FAILED",
                "the engine refused to delete " + source.getElementName() + ": "
                    + deletion.messages(),
                "No files were modified.");
        }

        CompositeChange composite = new CompositeChange(label);
        composite.add(ChangeEngine.fromFileEdits(label, edits));
        composite.add(deletion.change());
        return runPreCheckedRefactoring(service,
            new PreparedRefactoring(composite, label), "inline_class", arguments);
    }

    /**
     * The absorber's methods that do nothing but call the holder's method of the SAME name.
     *
     * <p>These are superseded rather than collided with. The body arriving from the
     * absorbed class IS what the forwarder was reaching for, so the forwarder is removed
     * and the body takes its place — which is Fowler's Inline Class in its plainest form.
     * Leaving the forwarder would be worse than a collision: {@code dropHolderQualifier}
     * strips the receiver from {@code holder.addFilter(f)}, so the method would end up
     * calling itself.</p>
     *
     * <p>Only a PUBLIC absorbed member supersedes a forwarder. A private one would take a
     * public method's place and quietly narrow the absorber's API, and that is a decision
     * rather than a rewrite — such a name stays a collision and is still refused.</p>
     */
    private static Set<String> forwardersTo(AbstractTypeDeclaration absorber,
                                            String holderName,
                                            AbstractTypeDeclaration sourceType) {
        Set<String> publicInSource = new LinkedHashSet<>();
        for (Object member : sourceType.bodyDeclarations()) {
            if (member instanceof MethodDeclaration method && !method.isConstructor()
                    && org.eclipse.jdt.core.dom.Modifier.isPublic(method.getModifiers())) {
                publicInSource.add(method.getName().getIdentifier());
            }
        }
        Set<String> forwarders = new LinkedHashSet<>();
        for (Object member : absorber.bodyDeclarations()) {
            if (!(member instanceof MethodDeclaration method) || method.getBody() == null
                    || !publicInSource.contains(method.getName().getIdentifier())) {
                continue;
            }
            List<?> body = method.getBody().statements();
            if (body.size() != 1) {
                continue;
            }
            Expression only = body.get(0) instanceof ExpressionStatement expression
                ? expression.getExpression()
                : body.get(0) instanceof ReturnStatement returned
                    ? returned.getExpression() : null;
            if (only instanceof MethodInvocation call
                    && call.getExpression() instanceof SimpleName receiver
                    && holderName.equals(receiver.getIdentifier())
                    && call.getName().getIdentifier()
                        .equals(method.getName().getIdentifier())) {
                forwarders.add(method.getName().getIdentifier());
            }
        }
        return forwarders;
    }

    /** Every `holder.x` becomes `x`, since x now lives here. */
    private static int dropHolderQualifier(CompilationUnit ast, String holderName,
                                           ASTRewrite rewrite, Set<String> forwarders) {
        List<ASTNode> sites = new ArrayList<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                // A superseded forwarder is being REMOVED whole, so there is nothing inside
                // it worth rewriting — and editing within a node that is also being deleted
                // is the kind of overlapping edit an ASTRewrite is entitled to reject.
                return !forwarders.contains(node.getName().getIdentifier());
            }

            @Override
            public boolean visit(MethodInvocation node) {
                if (node.getExpression() instanceof SimpleName name
                        && holderName.equals(name.getIdentifier())) {
                    sites.add(node);
                }
                return true;
            }

            @Override
            public boolean visit(FieldAccess node) {
                if (node.getExpression() instanceof SimpleName name
                        && holderName.equals(name.getIdentifier())) {
                    sites.add(node);
                }
                return true;
            }
        });
        for (ASTNode site : sites) {
            if (site instanceof MethodInvocation call) {
                rewrite.remove(call.getExpression(), null);
            } else if (site instanceof FieldAccess access) {
                rewrite.replace(site,
                    ASTNode.copySubtree(ast.getAST(), access.getName()), null);
            }
        }
        return sites.size();
    }

    /**
     * Whether the holder has a LIFECYCLE — a value that changes after construction.
     *
     * <p>A {@code final} field assigned in a constructor does NOT. That is how Java spells
     * an initializer that needs a constructor argument, and the compiler already guarantees
     * such a field is assigned exactly once on every path. Refusing it would refuse the
     * commonest way the shape this row exists for is actually written: upstream's
     * {@code FilterManager} holds {@code private final FilterChain filterChain} and builds
     * it in its constructor, which is a holder with no lifecycle at all.</p>
     *
     * <p>Everything else still counts. A non-final field assigned in a constructor may be
     * re-assigned later, and a setter is a lifecycle whatever the field's modifiers say —
     * so the constructor exemption is granted only together with {@code final}.</p>
     */
    private static boolean writtenOutsideItsInitializer(CompilationUnit ast, String holderName,
                                                        boolean holderIsFinal) {
        boolean[] written = { false };
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(org.eclipse.jdt.core.dom.Assignment node) {
                String target = node.getLeftHandSide() instanceof SimpleName name
                    ? name.getIdentifier()
                    : node.getLeftHandSide() instanceof FieldAccess access
                        ? access.getName().getIdentifier() : null;
                if (holderName.equals(target) && !(holderIsFinal && insideAConstructor(node))) {
                    written[0] = true;
                }
                return true;
            }
        });
        return written[0];
    }

    /**
     * Unwrap every {@code @link} to the class being deleted, leaving its name as prose.
     *
     * <p>A link to a type that no longer exists is a dangling reference the COMPILE GATE
     * CANNOT SEE — javadoc is a comment, so the rewrite passes every check and ships a
     * broken cross-reference anyway; {@code -Xdoclint} is where it eventually surfaces.
     * Upstream's {@code FilterManager} is documented as managing "the filters and
     * {@link FilterChain}", and inlining FilterChain made that link point at nothing.</p>
     *
     * <p>The tag becomes the bare type name rather than being deleted. The sentence stays
     * true — the class did exist and its behaviour is now here — and rewriting somebody's
     * prose is not this operation's business.</p>
     */
    private static int unlinkDeletedType(CompilationUnit ast, String typeName,
                                         ASTRewrite rewrite) {
        List<org.eclipse.jdt.core.dom.TagElement> tags = new ArrayList<>();
        // ASTVisitor(true) — the no-argument constructor does NOT enter doc comments, so a
        // visitor written the usual way walks straight past every javadoc tag in the file
        // and reports, truthfully, that it found none.
        ast.accept(new ASTVisitor(true) {
            @Override
            public boolean visit(org.eclipse.jdt.core.dom.TagElement node) {
                if ("@link".equals(node.getTagName()) && node.fragments().size() == 1
                        && node.fragments().get(0) instanceof org.eclipse.jdt.core.dom.Name name
                        && typeName.equals(name.getFullyQualifiedName())) {
                    tags.add(node);
                }
                return true;
            }
        });
        for (org.eclipse.jdt.core.dom.TagElement tag : tags) {
            org.eclipse.jdt.core.dom.TextElement plain =
                ast.getAST().newTextElement();
            plain.setText(typeName);
            rewrite.replace(tag, plain, null);
        }
        return tags.size();
    }

    /** Every assignment to the named field, so the rewrite can drop them with the field. */
    private static List<org.eclipse.jdt.core.dom.Assignment> assignmentsTo(
            CompilationUnit ast, String holderName) {
        List<org.eclipse.jdt.core.dom.Assignment> writes = new ArrayList<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(org.eclipse.jdt.core.dom.Assignment node) {
                String target = node.getLeftHandSide() instanceof SimpleName name
                    ? name.getIdentifier()
                    : node.getLeftHandSide() instanceof FieldAccess access
                        ? access.getName().getIdentifier() : null;
                if (holderName.equals(target)) {
                    writes.add(node);
                }
                return true;
            }
        });
        return writes;
    }

    /** Whether the node sits inside a constructor body, walking out to the declaration. */
    private static boolean insideAConstructor(ASTNode node) {
        for (ASTNode at = node; at != null; at = at.getParent()) {
            if (at instanceof MethodDeclaration method) {
                return method.isConstructor();
            }
        }
        return false;
    }

    private static Set<String> memberNames(AbstractTypeDeclaration type) {
        Set<String> names = new LinkedHashSet<>();
        for (Object member : type.bodyDeclarations()) {
            names.addAll(namesOf((BodyDeclaration) member));
        }
        return names;
    }

    private static List<String> namesOf(BodyDeclaration declaration) {
        List<String> names = new ArrayList<>();
        if (declaration instanceof MethodDeclaration method) {
            names.add(method.getName().getIdentifier());
        } else if (declaration instanceof FieldDeclaration field) {
            for (Object fragment : field.fragments()) {
                names.add(((VariableDeclarationFragment) fragment).getName().getIdentifier());
            }
        }
        return names;
    }

    private static AbstractTypeDeclaration typeNamed(CompilationUnit ast, String name) {
        for (Object type : ast.types()) {
            if (type instanceof AbstractTypeDeclaration declaration
                    && name.equals(declaration.getName().getIdentifier())) {
                return declaration;
            }
        }
        return null;
    }

    private static AbstractTypeDeclaration firstType(CompilationUnit ast) {
        for (Object type : ast.types()) {
            if (type instanceof TypeDeclaration declaration) {
                return declaration;
            }
        }
        return null;
    }

    private static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
