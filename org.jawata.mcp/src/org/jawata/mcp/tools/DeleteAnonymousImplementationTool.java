package org.jawata.mcp.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.shared.FormatterOptions;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sprint 28g — <b>D10</b>: delete an anonymous implementation and make its target implement the
 * interface instead.
 *
 * <p><b>SEVENTEEN hand edits in Sprint 8 and no tool at all.</b> The census row is explicit —
 * <em>"delete anonymous implementation — the strategy's or a test's forwarding callback bodies |
 * 6 prod | 11 test | 17 | 267 lines | none | NO-TOOL 17 | no tool removes an anonymous
 * implementation and makes its target implement the interface instead (the stage-1/2/3 shape,
 * done by hand each time)"</em>. Its sites name the shape plainly: <em>"manager implements
 * SLCallback itself so the strategy's forwarding anonymous class can go"</em>, <em>"two overrides
 * of the anonymous SlotCallback"</em>, <em>"anonymous SlotCallback shrunk to two methods"</em>,
 * <em>"the last anonymous SlotCallback in the strategy"</em>. It is the largest single untooled
 * shape the sprint produced.</p>
 *
 * <h2>What it performs</h2>
 *
 * <p>An anonymous class whose every method does nothing but forward to one collaborator is a
 * wrapper that exists only because that collaborator does not declare the interface. So the
 * collaborator declares it, and the wrapper goes:</p>
 *
 * <pre>
 *   slot.attach(ports, new SlotListener() {        slot.attach(ports, manager);
 *       public void onFill(String o) {      →
 *           manager.onFill(o);                     // ... and SlotManager implements SlotListener
 *       }
 *       public void onCancel(String o) {
 *           manager.onCancel(o);
 *       }
 *   });
 * </pre>
 *
 * <p>Two edits, and the second is the one a hand edit forgets: the creation expression becomes
 * the receiver, and the receiver's own type gains the interface — with the import it needs,
 * because a bare simple name from another package is the defect 28d's rows 16 and 28 both
 * shipped before {@code ImportRewrite} was put in the path.</p>
 *
 * <h2>Why every refusal below is a refusal rather than a cleverer rewrite</h2>
 *
 * <p>The safety argument is that it moves NO code: the bodies are deleted, not relocated,
 * because the target already has the methods they were calling. So "is this a plain forwarder"
 * is not a convenience check — it is the precondition that makes deletion equivalent to the
 * original. Each refusal names what it saw:</p>
 *
 * <ul>
 *   <li><b>A body that is not a single forwarding call</b> — anything computed, logged,
 *       conditioned or sequenced is behaviour that lives nowhere else.</li>
 *   <li><b>An implicit receiver</b> ({@code onFill(o) { handle(o); }}), which forwards to the
 *       ENCLOSING instance. Out of this version deliberately: the replacement would be
 *       {@code this} and the enclosing type would gain the interface, a different edit on a
 *       different declaration. <b>Checked BEFORE the method name, because a failing test found
 *       the order wrong</b> — with no receiver there is no target, so asking whether the name
 *       matches the target's method is a question about nothing, and the fixture's implicit case
 *       is necessarily also a rename ({@code onFill} forwarding to {@code onFill} with no
 *       receiver would be infinite recursion). Asked in the wrong order it answered
 *       {@code FORWARD_RENAMES_THE_METHOD}, which is true and useless.</li>
 *   <li><b>Arguments that are not the method's own parameters, in order, unchanged.</b>
 *       {@code onFill(o) { manager.onFill(o.trim()); }} is an adapter, not a wrapper.</li>
 *   <li><b>A forward to a DIFFERENT method name.</b> Promoting the target only works if the
 *       target's method IS the interface method; a renamed forward needs the target renamed
 *       first, which is {@code rename_symbol}'s decision.</li>
 *   <li><b>Two different receivers across the methods.</b> No single target to promote, and
 *       picking one would silently drop the other's calls.</li>
 *   <li><b>A created type that is not an interface</b>, where {@code implements} would be wrong
 *       and an anonymous subclass of a CLASS overrides rather than forwards.</li>
 *   <li><b>A target whose type is not source here</b> — a JDK or library type cannot be made to
 *       implement anything.</li>
 *   <li><b>A method the target does not declare</b>, named, because generating it means
 *       inventing a body.</li>
 *   <li><b>Anything in the anonymous body that is not a method</b> — a field is state the
 *       wrapper owns, so the wrapper is not a wrapper.</li>
 * </ul>
 *
 * <h2>Why the APPLYING base</h2>
 *
 * <p>It reports what it did — which type gained the interface, which methods were deleted, and
 * whether the target already implemented it — and only this base carries extras into the
 * response. Written first on {@link AbstractRefactoringTool}, the extras map was built and
 * silently discarded, because {@code runRefactoring} has nowhere to put it. A test asserting on
 * a field found it; nothing else would have, since the operation itself was already correct.</p>
 */
public class DeleteAnonymousImplementationTool extends AbstractApplyingRefactoringTool {

    public DeleteAnonymousImplementationTool(Supplier<IJdtService> serviceSupplier,
                                             RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "delete_anonymous_implementation";
    }

    /** A type gains an interface and a creation disappears — both visible to every caller. */
    @Override
    public boolean isStructural() {
        return true;
    }

    @Override
    public String getDescription() {
        return """
            Delete an anonymous implementation whose methods only FORWARD to one
            collaborator, and make that collaborator implement the interface instead.

            USAGE:
              delete_anonymous_implementation(filePath="...", line=12, column=30)

            Point at the `new SomeInterface() { ... }` expression. The creation becomes
            the receiver its methods were forwarding to, and the receiver's own type
            gains `implements SomeInterface` plus the import it needs.

            Seventeen hand edits in Sprint 8 had this shape and no tool performed it.

            It moves NO code — the bodies are deleted because the target already
            declares the methods they called, and that equivalence is the whole safety
            argument. So it REFUSES, naming what it saw, when a body is not a single
            forwarding call, when the receiver is implicit (that forwards to the
            enclosing instance — a different edit), when the arguments are not the
            method's own parameters unchanged and in order, when the forward names a
            different method, when two methods forward to different receivers, when the
            created type is not an interface, when the target's type is not source here,
            when the target does not declare one of the methods, and when the anonymous
            body declares anything that is not a method.

            IMPORTANT: ZERO-BASED coordinates. Applies by default and returns
            filesModified/diff/undoChangeId/summary; pass auto_apply=false to stage.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file holding the anonymous implementation."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line on or inside the `new Interface() { ... }`."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (filePathStr == null || filePathStr.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath",
                "filePath is required"));
        }
        if (line < 0 || column < 0) {
            return Preparation.fail(ToolResponse.invalidCoordinates(line, column,
                "line and column are required and must be zero-based non-negative integers"));
        }

        Path path = service.getPathUtils().resolve(filePathStr);
        ICompilationUnit unit = service.getCompilationUnit(path);
        if (unit == null) {
            return Preparation.fail(ToolResponse.fileNotFound(filePathStr));
        }
        CompilationUnit ast = parse(unit);
        int offset = ast.getPosition(line + 1, column);
        if (offset < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "Invalid position"));
        }
        ClassInstanceCreation creation = enclosing(
            new NodeFinder(ast, offset, 0).getCoveringNode(), ClassInstanceCreation.class);
        if (creation == null || creation.getAnonymousClassDeclaration() == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "No anonymous class creation at this position. Point at the "
                    + "`new SomeInterface() { ... }` expression."));
        }
        AnonymousClassDeclaration body = creation.getAnonymousClassDeclaration();

        ITypeBinding created = creation.getType().resolveBinding();
        if (created == null) {
            return refuse("TYPE_UNRESOLVED",
                "the created type does not resolve, so whether it is an interface cannot be "
                    + "established");
        }
        if (!created.isInterface()) {
            return refuse("CREATED_TYPE_IS_NOT_AN_INTERFACE",
                created.getName() + " is a class, not an interface, so `implements` would be "
                    + "wrong and an anonymous SUBCLASS is not this shape — its body overrides "
                    + "rather than forwards.");
        }

        String receiverText = null;
        IVariableBinding receiverBinding = null;
        List<String> forwarded = new ArrayList<>();
        for (Object declaration : body.bodyDeclarations()) {
            if (!(declaration instanceof MethodDeclaration method)) {
                return refuse("BODY_IS_NOT_ONLY_METHODS",
                    "the anonymous body declares something that is not a method ("
                        + ((BodyDeclaration) declaration).getClass().getSimpleName()
                        + "), so it owns state or behaviour of its own and is not a wrapper.");
            }
            MethodInvocation forward = plainForward(method);
            if (forward == null) {
                return refuse("BODY_IS_NOT_A_PLAIN_FORWARD",
                    method.getName().getIdentifier() + " is not a single forwarding call, so "
                        + "deleting its body would lose behaviour that lives nowhere else. This "
                        + "operation moves no code; that is what makes the deletion equivalent "
                        + "to the original.");
            }
            // THE RECEIVER IS ASKED FIRST. With no receiver there is no target, so every later
            // question — does the name match, does the target declare it — is about nothing.
            Expression receiver = forward.getExpression();
            if (receiver == null) {
                return refuse("RECEIVER_IS_IMPLICIT",
                    method.getName().getIdentifier() + " forwards with no receiver, so it calls "
                        + "the ENCLOSING instance. That case is real and is out of this version "
                        + "deliberately: the replacement would be `this` and the enclosing type "
                        + "would gain the interface, which is a different edit on a different "
                        + "declaration.");
            }
            if (!(receiver instanceof SimpleName name)
                    || !(name.resolveBinding() instanceof IVariableBinding variable)) {
                return refuse("RECEIVER_IS_NOT_A_VARIABLE",
                    "the receiver `" + receiver + "` is not a plain variable or field of the "
                        + "enclosing scope, so there is no single object to promote to the "
                        + "interface.");
            }
            String text = receiver.toString();
            if (receiverText == null) {
                receiverText = text;
                receiverBinding = variable;
            } else if (!receiverText.equals(text)) {
                return refuse("RECEIVERS_DISAGREE",
                    "the methods forward to two different receivers, `" + receiverText
                        + "` and `" + text + "`, so there is no single target and promoting "
                        + "either would silently drop the other's calls.");
            }
            if (!forward.getName().getIdentifier().equals(method.getName().getIdentifier())) {
                return refuse("FORWARD_RENAMES_THE_METHOD",
                    method.getName().getIdentifier() + " forwards to a method called "
                        + forward.getName().getIdentifier() + ". Making the target implement "
                        + created.getName() + " only works when the target's method IS the "
                        + "interface method; rename the target's method first (rename_symbol), "
                        + "then run this.");
            }
            String argumentRefusal = argumentsArePassedThrough(method, forward);
            if (argumentRefusal != null) {
                return refuse("ARGUMENTS_ARE_NOT_PASSED_THROUGH", argumentRefusal);
            }
            forwarded.add(method.getName().getIdentifier());
        }
        if (forwarded.isEmpty()) {
            return refuse("BODY_IS_EMPTY",
                "the anonymous body declares no methods, so there is nothing to establish a "
                    + "target from.");
        }

        ITypeBinding targetType = receiverBinding.getType();
        IType target = targetType == null || targetType.getJavaElement() == null ? null
            : (IType) targetType.getJavaElement();
        if (target == null || target.getCompilationUnit() == null) {
            return refuse("TARGET_IS_NOT_IN_THE_WORKSPACE",
                "the receiver's type "
                    + (targetType == null ? "(unresolved)" : targetType.getName())
                    + " is not source in this workspace, so it cannot be made to implement "
                    + created.getName() + ".");
        }
        String missing = methodTheTargetLacks(targetType, forwarded);
        if (missing != null) {
            return refuse("TARGET_LACKS_A_FORWARDED_METHOD",
                target.getElementName() + " does not declare " + missing + ", which the anonymous "
                    + "class forwards to it — so deleting the wrapper would leave the interface "
                    + "unimplemented. Generating it means inventing a body, which is not this "
                    + "operation's decision.");
        }

        boolean promote = !alreadyImplements(targetType, created);
        return build(unit, ast, creation, receiverText, promote ? target : null,
            created, target, forwarded);
    }

    /**
     * The two edits. {@code typeToAmend} is null when the target already declares the interface,
     * in which case only the creation is replaced — the wrapper was pure overhead.
     */
    private Preparation build(ICompilationUnit unit, CompilationUnit ast,
                              ClassInstanceCreation creation, String receiverText,
                              IType typeToAmend, ITypeBinding created, IType target,
                              List<String> forwarded) throws Exception {
        Map<IFile, List<TextEdit>> editsByFile = new LinkedHashMap<>();
        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        rewrite.replace(creation,
            rewrite.createStringPlaceholder(receiverText, ASTNode.SIMPLE_NAME), null);

        ICompilationUnit targetUnit = typeToAmend == null ? null : typeToAmend.getCompilationUnit();
        boolean sameFile = targetUnit != null && targetUnit.equals(unit);
        CompilationUnit targetAst = typeToAmend == null ? null
            : sameFile ? ast : parse(targetUnit);
        ImportRewrite imports = null;

        if (typeToAmend != null) {
            // The shared lookup answers with an AbstractTypeDeclaration, because an enum and a
            // record are type declarations too. Only a class or interface declaration carries a
            // superInterfaceTypes list, so anything else is refused rather than cast.
            if (!(org.jawata.mcp.tools.shared.TypeLookup.declaration(targetAst, typeToAmend)
                    instanceof TypeDeclaration declaration)) {
                return refuse("TARGET_IS_NOT_A_CLASS_DECLARATION",
                    typeToAmend.getElementName() + " is not a class or interface declaration — an "
                        + "enum or a record cannot be given an interface this way.");
            }
            imports = ImportRewrite.create(targetAst, true);
            String nameToUse = imports.addImport(created);
            ASTRewrite targetRewrite = sameFile ? rewrite : ASTRewrite.create(targetAst.getAST());
            ListRewrite interfaces = targetRewrite.getListRewrite(declaration,
                TypeDeclaration.SUPER_INTERFACE_TYPES_PROPERTY);
            interfaces.insertLast(
                (Type) targetRewrite.createStringPlaceholder(nameToUse, ASTNode.SIMPLE_TYPE),
                null);
            if (!sameFile) {
                addEdit(editsByFile, (IFile) targetUnit.getResource(),
                    targetRewrite.rewriteAST(new Document(targetUnit.getSource()),
                        FormatterOptions.forGeneratedCode(targetAst)));
            }
        }

        // One rewrite per document: when the target is in this same file, both changes are in
        // this one tree and must be written out together rather than twice.
        addEdit(editsByFile, (IFile) unit.getResource(),
            rewrite.rewriteAST(new Document(unit.getSource()),
                FormatterOptions.forGeneratedCode(ast)));
        if (imports != null) {
            TextEdit importEdit = imports.rewriteImports(null);
            addEdit(editsByFile, (IFile) targetUnit.getResource(), importEdit);
        }

        Change change = ChangeEngine.fromFileEdits(
            "delete anonymous " + created.getName(), editsByFile);

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("interface", created.getQualifiedName());
        extras.put("target", target.getFullyQualifiedName('.'));
        extras.put("methodsDeleted", forwarded);
        extras.put("targetGainedTheInterface", typeToAmend != null);
        if (typeToAmend == null) {
            extras.put("note", target.getElementName() + " already implements "
                + created.getName() + ", so only the wrapper was removed");
        }
        String summary = "deleted the anonymous " + created.getName() + "; "
            + target.getElementName() + (typeToAmend != null
                ? " now implements it" : " already implemented it");
        return Preparation.of(change, summary, extras);
    }

    private static Preparation refuse(String reasonCode, String reason) {
        return Preparation.fail(ToolResponse.preconditionNotMet(reason, reasonCode));
    }

    private static void addEdit(Map<IFile, List<TextEdit>> edits, IFile file, TextEdit edit) {
        if (edit == null || edit.getChildrenSize() == 0) {
            return;
        }
        edits.computeIfAbsent(file, f -> new ArrayList<>()).add(edit);
    }

    /** The single forwarding invocation this method's body consists of, or null. */
    private static MethodInvocation plainForward(MethodDeclaration method) {
        if (method.getBody() == null || method.getBody().statements().size() != 1) {
            return null;
        }
        Statement only = (Statement) method.getBody().statements().get(0);
        Expression expression = only instanceof ExpressionStatement statement
            ? statement.getExpression()
            : only instanceof ReturnStatement returned ? returned.getExpression() : null;
        return expression instanceof MethodInvocation invocation ? invocation : null;
    }

    /** Null when the forward passes exactly this method's parameters, in order, unchanged. */
    private static String argumentsArePassedThrough(MethodDeclaration method,
                                                    MethodInvocation forward) {
        List<?> parameters = method.parameters();
        List<?> arguments = forward.arguments();
        if (parameters.size() != arguments.size()) {
            return method.getName().getIdentifier() + " takes " + parameters.size()
                + " parameter(s) and forwards " + arguments.size()
                + " argument(s), so it is an adapter rather than a wrapper.";
        }
        for (int i = 0; i < parameters.size(); i++) {
            String declared = ((SingleVariableDeclaration) parameters.get(i))
                .getName().getIdentifier();
            Object argument = arguments.get(i);
            if (!(argument instanceof SimpleName passed)
                    || !passed.getIdentifier().equals(declared)) {
                return method.getName().getIdentifier() + " passes `" + argument
                    + "` where its own parameter `" + declared + "` sits, so the forward "
                    + "transforms its arguments and deleting it would lose that.";
            }
        }
        return null;
    }

    /** The first forwarded method the target does not declare, or null. */
    private static String methodTheTargetLacks(ITypeBinding targetType, List<String> forwarded) {
        for (String name : forwarded) {
            boolean found = false;
            for (ITypeBinding type = targetType; type != null && !found;
                    type = type.getSuperclass()) {
                for (IMethodBinding candidate : type.getDeclaredMethods()) {
                    if (candidate.getName().equals(name)) {
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                return name + "()";
            }
        }
        return null;
    }

    private static boolean alreadyImplements(ITypeBinding type, ITypeBinding iface) {
        for (ITypeBinding declared : type.getInterfaces()) {
            if (declared.getQualifiedName().equals(iface.getQualifiedName())) {
                return true;
            }
        }
        return false;
    }

    private static <T extends ASTNode> T enclosing(ASTNode node, Class<T> type) {
        ASTNode cursor = node;
        while (cursor != null && !type.isInstance(cursor)) {
            cursor = cursor.getParent();
        }
        return type.cast(cursor);
    }

    private static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
