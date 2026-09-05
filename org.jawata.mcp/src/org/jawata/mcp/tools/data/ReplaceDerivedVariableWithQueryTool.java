package org.jawata.mcp.tools.data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.PreparedRefactoring;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractRefactoringTool;
import org.jawata.mcp.tools.ToolKindDelegate;
import org.jawata.mcp.tools.shared.FormatterOptions;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code data kind=replace_derived_variable} — Fowler row 45, Replace Derived Variable with
 * Query.
 *
 * <p>A field that is always recomputed from other fields is not state; it is an answer being
 * cached. The cost is that it can go stale: every place that changes an input has to remember
 * to update it, and one that forgets produces a value that is wrong and looks fine. Deleting
 * the field and computing the answer on demand removes the class of bug rather than an
 * instance of it.</p>
 *
 * <h2>What it does, and the one condition that makes it safe</h2>
 *
 * <p>It finds every write to the field, ACROSS THE WORKSPACE, and requires them all to assign
 * THE SAME EXPRESSION. That is the whole safety argument: if every writer computes the same
 * thing, the field's value is that thing at all times, so a method returning it is
 * indistinguishable from reading the field. If the writers disagree, the field is not derived
 * from one rule and this refactoring does not apply — and the refusal quotes what it actually
 * found, so a caller can see the disagreement rather than take the tool's word for it.</p>
 *
 * <p>The field and every assignment are then deleted, a query method takes their place, and
 * every read — in this file and in every other — becomes a call to it. The generated method
 * keeps the FIELD's visibility, so nothing that could read the field before loses access.</p>
 *
 * <h2>What it refuses, and why each refusal is not merely caution</h2>
 *
 * <ul>
 *   <li><b>Writers that assign different expressions.</b> See above — the safety argument is
 *       exactly their agreement.</li>
 *   <li><b>A derived expression that reads a LOCAL or a PARAMETER.</b> The expression has to
 *       move into a method with no arguments, where those names do not exist. This is the
 *       commonest real refusal: {@code this.total = price * qty} inside a method whose
 *       parameters are {@code price} and {@code qty} is derived from the CALL, not from the
 *       object, and turning it into a query would need those values passed in — which is
 *       Replace Query with Parameter, a different row.</li>
 *   <li><b>A derived expression that CALLS anything.</b> A query stands in for a cached field
 *       only when the computation gives the same answer every time, and purity is not
 *       decidable here. Upstream's circuit breaker writes {@code lastFailureTime =
 *       System.nanoTime() + futureTime} in two places, identically — so the agreement check
 *       passes, and turning it into a query would re-read the CLOCK on every access, changing
 *       what the class does while compiling perfectly. That silent-behaviour-change is the
 *       worst outcome available, so the conservative direction is the only honest one.</li>
 *   <li><b>No writes at all.</b> Nothing derives it, so it is ordinary state.</li>
 *   <li><b>A name the declaring type already declares as a method.</b> The query would clash.
 *       </li>
 * </ul>
 *
 * <h2>Its route, or rather its absence</h2>
 *
 * <p>UNROUTED. No shipped detector reports a field that duplicates a computation, and none is
 * among Stage 8's six. {@code temporary_field} is the nearest and reports something else — a
 * field used by only one method — and it already has one route, {@code extract kind=class},
 * which the tier model would turn to ADVISE the moment a second was added.</p>
 */
public class ReplaceDerivedVariableWithQueryTool extends AbstractRefactoringTool
        implements ToolKindDelegate {

    public ReplaceDerivedVariableWithQueryTool(Supplier<IJdtService> serviceSupplier,
                                               RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String kindName() {
        return "replace_derived_variable";
    }

    @Override
    public String kindSummary() {
        return """
            delete a field that is always recomputed from other fields, and answer
            the question on demand instead — so it can no longer go stale when a
            writer forgets to update it. Requires every write in the workspace to
            assign THE SAME expression: that agreement IS the safety argument, and
            the refusal quotes the ones that disagree. Every read, here and in
            other files, becomes a call to the generated query, which keeps the
            field's own visibility. Refuses writers that disagree, a derived
            expression that CALLS anything (a query stands in for a cached field
            only when the computation gives the same answer every time, and a clock
            or a counter re-read on each access changes behaviour while compiling),
            an expression reading a LOCAL or PARAMETER (it must move into a
            no-argument method, and a value that comes from the call is Replace
            Query with Parameter instead), a field nothing writes, and a method
            name already declared. Each refusal names which.""";
    }

    /** Structural: a field leaves the type's surface and a method arrives on it. */
    @Override
    public boolean isStructural() {
        return true;
    }

    @Override
    public String getName() {
        return "replace_derived_variable";
    }

    @Override
    public String getDescription() {
        return "Replace Derived Variable with Query — delete a field that is always "
            + "recomputed and answer on demand. Delegate of data.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file declaring the field."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the field declaration."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column on the field's name."));
        properties.put("queryName", Map.of("type", "string",
            "description", "Name for the generated query method (default: the field's own "
                + "name, which reads at the call site as the question it answers)."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    public ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
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
            IJavaElement element = service.getElementAtPosition(filePath, line, column);
            if (!(element instanceof IField field)) {
                return ToolResponse.invalidParameter("position",
                    "position does not resolve to a field; got "
                        + (element == null ? "nothing" : element.getClass().getSimpleName())
                        + ". Replace Derived Variable with Query acts on the FIELD that"
                        + " caches the answer.");
            }
            return replace(service, field, getStringParam(arguments, "queryName"), arguments);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(ReplaceDerivedVariableWithQueryTool.class)
                .warn("replace_derived_variable failed: {}", e.toString(), e);
            return ToolResponse.internalError(e);
        }
    }

    private ToolResponse replace(IJdtService service, IField field, String requestedName,
                                 JsonNode arguments) throws Exception {
        IType declaring = field.getDeclaringType();
        ICompilationUnit unit = field.getCompilationUnit();
        if (declaring == null || unit == null) {
            return ToolResponse.symbolNotFound(
                "'" + field.getElementName() + "' has no source declaring type here.");
        }
        String declaringFqn = declaring.getFullyQualifiedName('.');
        CompilationUnit ast = parse(unit);
        FieldDeclaration declaration = declarationOf(ast, field.getElementName());
        if (declaration == null) {
            return ToolResponse.invalidParameter("position",
                "'" + field.getElementName() + "' is declared alongside others on one line,"
                    + " so deleting it means splitting the declaration first.");
        }

        Set<ICompilationUnit> units = new LinkedHashSet<>();
        units.add(unit);
        for (SearchMatch match : service.getSearchService().findAllReferences(field, 10000)) {
            if (match.getElement() instanceof IJavaElement referencing) {
                ICompilationUnit owner = (ICompilationUnit) referencing
                    .getAncestor(IJavaElement.COMPILATION_UNIT);
                if (owner != null && owner.getResource() instanceof IFile) {
                    units.add(owner);
                }
            }
        }

        // PASS ONE — read every writer before changing anything, because the safety argument
        // is a property of the whole SET of them and cannot be decided one file at a time.
        List<String> derivations = new ArrayList<>();
        Map<ICompilationUnit, CompilationUnit> parsed = new LinkedHashMap<>();
        Map<ICompilationUnit, List<ASTNode>> writesByUnit = new LinkedHashMap<>();
        Map<ICompilationUnit, List<ASTNode>> readsByUnit = new LinkedHashMap<>();
        for (ICompilationUnit referencing : units) {
            CompilationUnit tree = referencing.equals(unit) ? ast : parse(referencing);
            parsed.put(referencing, tree);
            List<ASTNode> writes = new ArrayList<>();
            List<ASTNode> reads = new ArrayList<>();
            collect(tree, field.getElementName(), declaringFqn, writes, reads);
            writesByUnit.put(referencing, writes);
            readsByUnit.put(referencing, reads);
            String source = referencing.getSource();
            for (ASTNode write : writes) {
                derivations.add(text(source,
                    ((Assignment) write.getParent()).getRightHandSide()));
            }
        }

        if (derivations.isEmpty()) {
            return ToolResponse.invalidParameter("position",
                "nothing assigns '" + field.getElementName() + "', so it is not derived from"
                    + " anything — it is ordinary state, set once at its declaration or never."
                    + " There is no computation here to turn into a query.");
        }
        String derivation = derivations.get(0);
        for (String other : derivations) {
            if (!other.equals(derivation)) {
                return ToolResponse.invalidParameter("position",
                    "the writers of '" + field.getElementName() + "' do not agree on how it is"
                        + " computed, so it is not derived from ONE rule and a query cannot"
                        + " stand in for it. Found: " + new LinkedHashSet<>(derivations)
                        + ". Their agreement is the whole safety argument here.");
            }
        }

        // EVERY WRITE, not just the first — and the reason is that the agreement check above
        // compares expression TEXT, which two writers can share while binding to different
        // things. `this.total = itemCount * unitPrice` inside a constructor whose parameters
        // are named itemCount and unitPrice reads the PARAMETERS; the identical line in an
        // ordinary method reads the FIELDS. Text-equal, meaning-different. Requiring every
        // writer's expression to name only fields is what makes the text comparison sound,
        // and checking one writer would have let the shadowed pair through.
        //
        // The expression must also move into a method that takes no arguments, so a local or
        // a parameter is unreachable from where it is going. That is the same condition, and
        // it is the commonest real refusal rather than a corner: a value computed from a
        // call's own arguments is derived from the CALL, not from the object.
        for (List<ASTNode> writes : writesByUnit.values()) {
            for (ASTNode write : writes) {
                org.eclipse.jdt.core.dom.Expression value =
                    ((Assignment) write.getParent()).getRightHandSide();
                // A CALL MAKES THE SUBSTITUTION UNSOUND, and the failure is silent. This row's
                // whole claim is that a query is indistinguishable from reading the field —
                // true only if the expression yields the same answer every time. Upstream's
                // circuit breaker writes `lastFailureTime = System.nanoTime() + futureTime`
                // in two places, identically, so the agreement check passes; turning it into
                // a query would re-read the CLOCK on every access and change what the class
                // does, while compiling perfectly. Purity is not decidable here, so the
                // conservative direction is the only honest one — the same call every other
                // row in this stage makes about a safety condition it cannot prove.
                String call = firstInvocation(value);
                if (call != null) {
                    return ToolResponse.invalidParameter("position",
                        "'" + field.getElementName() + "' is computed by calling '" + call
                            + "()', and a query is only indistinguishable from a cached field"
                            + " when the computation gives the same answer every time. That"
                            + " cannot be established here, and getting it wrong changes what"
                            + " the class does while still compiling — a clock or a counter"
                            + " read afresh on every access. Arithmetic over the object's own"
                            + " fields is the shape this performs.");
                }
                String outsider = firstOutsideName(value);
                if (outsider != null) {
                    return ToolResponse.invalidParameter("position",
                        "'" + field.getElementName() + "' is computed from '" + outsider
                            + "', which is a local or a parameter rather than the object's own"
                            + " state. The query would take no arguments and could not see it"
                            + " — a value that comes from the CALL is Replace Query with"
                            + " Parameter (row 55), a different refactoring.");
                }
            }
        }

        String queryName = requestedName != null && !requestedName.isBlank()
            ? requestedName
            : field.getElementName();
        for (IMethod existing : declaring.getMethods()) {
            if (existing.getElementName().equals(queryName)
                    && existing.getNumberOfParameters() == 0) {
                return ToolResponse.invalidParameter("queryName",
                    declaring.getElementName() + " already declares " + queryName
                        + "(). Pass queryName to choose another.");
            }
        }

        Map<IFile, List<TextEdit>> edits = new LinkedHashMap<>();
        int rewrittenReads = 0;
        int removedWrites = 0;
        for (ICompilationUnit referencing : units) {
            CompilationUnit tree = parsed.get(referencing);
            boolean isDeclaringUnit = referencing.equals(unit);
            ASTRewrite rewrite = ASTRewrite.create(tree.getAST());
            String source = referencing.getSource();

            for (ASTNode write : writesByUnit.get(referencing)) {
                ASTNode statement = write.getParent().getParent();
                if (!(statement instanceof ExpressionStatement)) {
                    return ToolResponse.invalidParameter("position",
                        "an assignment to '" + field.getElementName() + "' is part of a larger"
                            + " expression rather than a statement of its own, so removing it"
                            + " would change what that expression evaluates to.");
                }
                rewrite.remove(statement, null);
                removedWrites++;
            }
            for (ASTNode read : readsByUnit.get(referencing)) {
                rewrite.replace(read, rewrite.createStringPlaceholder(
                    text(source, read) + "()", ASTNode.METHOD_INVOCATION), null);
                rewrittenReads++;
            }

            ImportRewrite imports = ImportRewrite.create(tree, true);
            if (isDeclaringUnit) {
                String type = imports.addImport(
                    declaration.getType().resolveBinding());
                rewrite.remove(declaration, null);
                AbstractTypeDeclaration owner = enclosingType(declaration);
                ListRewrite members = rewrite.getListRewrite(owner,
                    owner.getBodyDeclarationsProperty());
                members.insertLast(rewrite.createStringPlaceholder(
                    "/**\n * The answer this class used to cache in a field.\n"
                        + " * Computed on demand by Replace Derived Variable with Query"
                        + " (Fowler row 45),\n * so it can no longer disagree with the state"
                        + " it is derived from.\n */\n"
                        + visibilityOf(field) + type + " " + queryName + "() {\n"
                        + "    return " + derivation + ";\n}",
                    ASTNode.METHOD_DECLARATION), null);
            }

            List<TextEdit> unitEdits = new ArrayList<>();
            unitEdits.add(rewrite.rewriteAST(new Document(source),
                FormatterOptions.forGeneratedCode(tree)));
            if (imports.hasRecordedChanges()) {
                unitEdits.add(imports.rewriteImports(null));
            }
            edits.put((IFile) referencing.getResource(), unitEdits);
        }

        String label = "replace derived variable " + declaring.getElementName() + "."
            + field.getElementName() + " with the query " + queryName + "() (" + removedWrites
            + " write(s) removed, " + rewrittenReads + " read(s) rewritten, across "
            + units.size() + " file(s))";
        return runPreCheckedRefactoring(service,
            new PreparedRefactoring(ChangeEngine.fromFileEdits(label, edits), label),
            "replace_derived_variable", arguments);
    }

    /** The field's own modifiers, so nothing that could read it loses access. */
    private static String visibilityOf(IField field) throws Exception {
        int flags = field.getFlags();
        String access = org.eclipse.jdt.core.Flags.isPublic(flags) ? "public "
            : org.eclipse.jdt.core.Flags.isProtected(flags) ? "protected "
                : org.eclipse.jdt.core.Flags.isPrivate(flags) ? "private " : "";
        return access + (org.eclipse.jdt.core.Flags.isStatic(flags) ? "static " : "");
    }

    /** The first method called anywhere in the expression, or null. */
    private static String firstInvocation(ASTNode expression) {
        String[] found = new String[1];
        expression.accept(new ASTVisitor() {
            @Override
            public boolean visit(org.eclipse.jdt.core.dom.MethodInvocation node) {
                if (found[0] == null) {
                    found[0] = node.getName().getIdentifier();
                }
                return true;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.ClassInstanceCreation node) {
                if (found[0] == null) {
                    found[0] = "new " + node.getType();
                }
                return true;
            }
        });
        return found[0];
    }

    /**
     * The first name in the expression that a no-argument method could not see, or null.
     *
     * <p>A field, a type and a method call are all reachable from the query; a local and a
     * parameter are not. Matched on the BINDING rather than on the name, so a local that
     * happens to share a field's name is judged for what it is.</p>
     */
    private static String firstOutsideName(ASTNode expression) {
        String[] found = new String[1];
        expression.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (found[0] == null
                        && node.resolveBinding() instanceof IVariableBinding variable
                        && !variable.isField()) {
                    found[0] = node.getIdentifier();
                }
                return true;
            }
        });
        return found[0];
    }

    /** Every reference in this unit, split into the writes and the reads. */
    private static void collect(CompilationUnit ast, String fieldName, String declaringFqn,
                                List<ASTNode> writes, List<ASTNode> reads) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (!fieldName.equals(node.getIdentifier())
                        || !(node.resolveBinding() instanceof IVariableBinding variable)
                        || !variable.isField()) {
                    return true;
                }
                org.eclipse.jdt.core.dom.ITypeBinding owner = variable.getDeclaringClass();
                if (owner == null
                        || !declaringFqn.equals(owner.getErasure().getQualifiedName())) {
                    return true;
                }
                if (node.getParent() instanceof VariableDeclarationFragment fragment
                        && fragment.getName() == node) {
                    return true;   // the declaration itself, removed separately
                }
                ASTNode access = node.getParent() instanceof FieldAccess fieldAccess
                    && fieldAccess.getName() == node ? fieldAccess
                    : node.getParent() instanceof QualifiedName qualified
                        && qualified.getName() == node ? qualified : node;
                if (access.getParent() instanceof Assignment assignment
                        && assignment.getLeftHandSide() == access) {
                    writes.add(access);
                } else {
                    reads.add(access);
                }
                return true;
            }
        });
    }

    private static FieldDeclaration declarationOf(CompilationUnit ast, String name) {
        List<FieldDeclaration> found = new ArrayList<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(FieldDeclaration node) {
                for (Object fragment : node.fragments()) {
                    if (fragment instanceof VariableDeclarationFragment f
                            && name.equals(f.getName().getIdentifier())) {
                        found.add(node);
                    }
                }
                return true;
            }
        });
        return found.size() == 1 && found.get(0).fragments().size() == 1 ? found.get(0) : null;
    }

    private static AbstractTypeDeclaration enclosingType(ASTNode node) {
        for (ASTNode at = node; at != null; at = at.getParent()) {
            if (at instanceof AbstractTypeDeclaration type) {
                return type;
            }
        }
        return null;
    }

    private static String text(String source, ASTNode node) {
        return source.substring(node.getStartPosition(),
            node.getStartPosition() + node.getLength());
    }

    private static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
