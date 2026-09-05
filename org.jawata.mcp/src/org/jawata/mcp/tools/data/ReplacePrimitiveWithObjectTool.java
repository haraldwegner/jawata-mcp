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
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
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
 * {@code data kind=replace_primitive} — Fowler row 54, Replace Primitive with Object.
 *
 * <p>A value starts as a {@code String} or an {@code int} because that is all it needed to
 * be. Then it grows a rule — a priority compares, a phone number formats, an order id
 * validates — and the rule has nowhere to live, so it is written at every site that holds
 * the primitive. Fowler's cure gives the value its own type, and the behaviour then has an
 * obvious home.</p>
 *
 * <h2>The usage migration IS this row</h2>
 *
 * <p>{@code refactor_to_pattern kind=replace_type_code_with_class} already generates a type
 * from a group of constants and says, in its own description, that it "does NOT auto-migrate
 * usages". That is the half this row performs: the field is RETYPED and every reference to it
 * — in this file and in every other file in the workspace — is rewritten so the code still
 * compiles. A read becomes {@code order.priority.value()}; a write becomes
 * {@code order.priority = new Priority(x)}.</p>
 *
 * <p><b>Cross-file, and unlike {@code move kind=field} it does not need to be told anything
 * to be.</b> Row 23 refuses a non-private field because each outside access has to be
 * rewritten through a RECEIVER that only a human at that site can name. Here the rewrite is
 * the same everywhere — wrap on write, unwrap on read — so a public field is no harder than
 * a private one and is not refused.</p>
 *
 * <h2>A record, not a class</h2>
 *
 * <p>The generated type is {@code public record Priority(String value) {}}, nested in the
 * field's own declaring type. A record is the language's own spelling of a value object: it
 * supplies the accessor, {@code equals}, {@code hashCode} and {@code toString}, which are
 * exactly the members Fowler has you write by hand and exactly the ones that must not be got
 * wrong. Nested for the reason row 22 is: the change machinery here edits EXISTING files, and
 * a top-level type needs a create-compilation-unit change, which is a different mechanism with
 * different failure modes. {@code move kind=class} lifts it out later.</p>
 *
 * <p><b>One known gap, stated rather than guarded.</b> Outside the declaring file the record
 * is constructed through its owner — {@code new Shipment.Carrier(x)} — which resolves wherever
 * that owner's simple name is in scope, and it is in every file that imported it to hold the
 * expression in the first place. A file reaching the field through a fully-qualified type it
 * never imported is the exception, and there the constructed name does not resolve. The
 * compile gate refuses and undoes the whole change, so the outcome is a decline rather than
 * broken code; adding an {@code ImportRewrite} per referencing file would close it, and it is
 * not done here because no measured case needs it yet.</p>
 *
 * <h2>What it refuses, and why each refusal is not merely caution</h2>
 *
 * <ul>
 *   <li><b>A field whose type is not primitive-like.</b> There is no primitive to replace, and
 *       the refusal says what the type actually is.</li>
 *   <li><b>{@code static final} — a constant.</b> A compile-time constant may appear in a
 *       {@code switch} case label and in an annotation value, and neither position accepts an
 *       object. The rewrite would compile everywhere else and fail there, which is the worst
 *       shape of all: it looks like it worked.</li>
 *   <li><b>A compound assignment or an increment</b> — {@code count += 1}, {@code count++}.
 *       There is no single right rewrite: whether the arithmetic belongs on the wrapper or
 *       inside it is the design decision the caller is making by adopting a value type, and
 *       guessing it produces code that compiles and means something else.</li>
 *   <li><b>A shared declaration</b> — {@code int width, height;}. Retyping one fragment means
 *       splitting the declaration, which is a different edit from this one.</li>
 *   <li><b>A name the declaring type already uses.</b> Generating over it would not compile;
 *       the refusal names the clash so a caller can pass {@code typeName}.</li>
 * </ul>
 *
 * <h2>Its route, or rather its absence — recorded per the per-row contract</h2>
 *
 * <p>UNROUTED, and measured rather than assumed. The plan credits this row with
 * {@code primitive_obsession}'s findings — 125 on this repository today. Reading the detector
 * settles it as a CENSUS rather than a sample: it visits {@code MethodDeclaration} only, and
 * emits from a single site, so every one of the 125 is a finding about a PARAMETER LIST, and
 * every message reads "Consider Replace Type Code with Class / Parameter Object". Both named
 * cures are other rows — the first ships already, the second is row 21 in Stage 4. Nothing in
 * that population is about a field with behaviour, which is this row's subject, so no shipped
 * finding names it.</p>
 *
 * <p><b>The reason lives HERE and in the plan, and NOT in
 * {@code CureCatalog.SHIPPED_BUT_UNROUTED} — which is a weaker place than it sounds, so it is
 * stated rather than left to be discovered.</b> {@code EveryShippedKindIsRoutedOrExplainedTest}
 * reads that table over four doors — {@code apply_cleanup}, {@code extract}, {@code inline},
 * {@code move} — and {@code data} is not one of them, so an entry there would today be read by
 * nothing. Widening the guard is blocked on a real question rather than on effort: three smells
 * route to the BARE front-door name {@code "data"}, which named one operation when it was
 * written and now names four, so the guard would read every {@code data} kind as routed — false
 * for this row, for {@code hide_delegate} and for {@code special_case} alike. Qualifying those
 * three routes is Stage 5's merge work, per the plan's rule that {@code CureCatalog} has one
 * owner and is batched at the merge.</p>
 */
public class ReplacePrimitiveWithObjectTool extends AbstractRefactoringTool
        implements ToolKindDelegate {

    /**
     * The types that count as "a primitive being used as a value".
     *
     * <p>The same set {@code PrimitiveObsessionDetector} calls primitive-like, plus the
     * primitives themselves. It is written out rather than shared because the two are
     * answering different questions — that detector asks what a parameter list is made of,
     * this asks what can be wrapped — and a shared constant would couple a refusal here to a
     * threshold decision there.</p>
     */
    private static final Set<String> WRAPPABLE = Set.of(
        "byte", "short", "int", "long", "float", "double", "char", "boolean",
        "String", "Byte", "Short", "Integer", "Long", "Float", "Double", "Character",
        "Boolean");

    public ReplacePrimitiveWithObjectTool(Supplier<IJdtService> serviceSupplier,
                                          RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String kindName() {
        return "replace_primitive";
    }

    @Override
    public String kindSummary() {
        return """
            replace a primitive field with a value object, MIGRATING EVERY USAGE —
            which is the half replace_type_code_with_class leaves to you. Generates
            a nested record (Order.Priority), retypes the field, and rewrites every
            reference in the workspace: a read becomes `order.priority.value()`, a
            write becomes `new Priority(x)`. Cross-file without being told anything,
            because the rewrite is the same at every site. Refuses a non-primitive
            field, a static final constant (a case label cannot hold an object), a
            compound assignment or increment (whether the arithmetic belongs on the
            wrapper or inside it is the decision you are making), and a shared
            declaration. Each refusal names which.""";
    }

    /** Structural: the field's declared type changes, and every reader is rewritten. */
    @Override
    public boolean isStructural() {
        return true;
    }

    @Override
    public String getName() {
        return "replace_primitive";
    }

    @Override
    public String getDescription() {
        return "Replace Primitive with Object — give a primitive field its own type and "
            + "migrate every usage to it. Delegate of data.";
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
            "description", "Zero-based column of the field declaration."));
        properties.put("typeName", Map.of("type", "string",
            "description", "Name for the generated value type (default: the field's own "
                + "name, capitalised). Every call site that constructs one reads it."));
        properties.put("accessorName", Map.of("type", "string",
            "description", "Name of the record component, and so of the accessor every read "
                + "is rewritten through (default: value)."));
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
                        + ". Replace Primitive with Object acts on a FIELD declaration.");
            }
            return replace(service, field, getStringParam(arguments, "typeName"),
                getStringParam(arguments, "accessorName"), arguments);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(ReplacePrimitiveWithObjectTool.class)
                .warn("replace_primitive failed: {}", e.toString(), e);
            return ToolResponse.internalError(e);
        }
    }

    private ToolResponse replace(IJdtService service, IField field, String requestedType,
                                 String requestedAccessor, JsonNode arguments) throws Exception {
        IType declaring = field.getDeclaringType();
        if (declaring == null || declaring.getCompilationUnit() == null) {
            return ToolResponse.symbolNotFound(
                "'" + field.getElementName() + "' has no source declaring type in this"
                    + " workspace, so nothing can be generated beside it.");
        }
        // A CONSTANT can appear where only a constant may: a switch case label, an annotation
        // value. Those positions take no object, and they are the two the compile gate would
        // catch LAST — a rewrite that succeeds in twenty places and fails in one reads as a
        // working refactoring right up until it does not.
        if (org.eclipse.jdt.core.Flags.isStatic(field.getFlags())
                && org.eclipse.jdt.core.Flags.isFinal(field.getFlags())) {
            return ToolResponse.invalidParameter("position",
                "'" + field.getElementName() + "' is static final — a compile-time constant."
                    + " It may be used as a switch case label or an annotation value, and"
                    + " neither position accepts an object. Replace Type Code with Class"
                    + " (refactor_to_pattern) is the operation for a group of constants.");
        }

        ICompilationUnit unit = declaring.getCompilationUnit();
        CompilationUnit ast = parse(unit);
        FieldDeclaration declaration = declarationOf(ast, field.getElementName());
        if (declaration == null) {
            return ToolResponse.invalidParameter("position",
                "'" + field.getElementName() + "' is declared alongside others on one line"
                    + " (`int width, height;`). Retyping one of them means splitting the"
                    + " declaration first, which is a different edit from this one.");
        }
        String primitive = declaration.getType().toString();
        if (!WRAPPABLE.contains(primitive)) {
            return ToolResponse.invalidParameter("position",
                "'" + field.getElementName() + "' is a " + primitive + ", which is not a"
                    + " primitive being used as a value — there is nothing to replace. This"
                    + " row acts on a field typed with a primitive or String.");
        }

        String typeName = requestedType != null && !requestedType.isBlank()
            ? requestedType
            : capitalise(field.getElementName());
        String accessor = requestedAccessor != null && !requestedAccessor.isBlank()
            ? requestedAccessor
            : "value";
        for (IType nested : declaring.getTypes()) {
            if (nested.getElementName().equals(typeName)) {
                return ToolResponse.invalidParameter("typeName",
                    declaring.getElementName() + " already declares a nested " + typeName
                        + ". Pass typeName to choose another.");
            }
        }

        // EVERY referencing file, found rather than assumed. A private field's references are
        // all in its own unit and this returns exactly that; a public one's are wherever they
        // are, and the declaring unit is added unconditionally because the declaration itself
        // is not a reference.
        Set<ICompilationUnit> units = new LinkedHashSet<>();
        units.add(unit);
        List<SearchMatch> found = service.getSearchService().findAllReferences(field, 10000);
        int references = found.size();
        for (SearchMatch match : found) {
            if (match.getElement() instanceof IJavaElement referencing) {
                ICompilationUnit owner = (ICompilationUnit) referencing
                    .getAncestor(IJavaElement.COMPILATION_UNIT);
                if (owner != null && owner.getResource() instanceof IFile) {
                    units.add(owner);
                }
            }
        }

        // DOTTED, and it is not a style choice. IType.getFullyQualifiedName() separates an
        // enclosing type with '$' while ITypeBinding.getQualifiedName() uses '.', so the two
        // never match for a NESTED type — and the failure is silent: collectSites matches
        // nothing, every site is skipped, and the operation reports a successful change that
        // rewrote no usage at all. A no-op that calls itself a success is the shape this
        // codebase has been bitten by before, so the comparison is made in one spelling.
        String declaringFqn = declaring.getFullyQualifiedName('.');

        Map<IFile, List<TextEdit>> edits = new LinkedHashMap<>();
        int reads = 0;
        int writes = 0;
        for (ICompilationUnit referencing : units) {
            boolean isDeclaringUnit = referencing.equals(unit);
            CompilationUnit referencingAst = isDeclaringUnit ? ast : parse(referencing);
            List<ASTNode> sites = new ArrayList<>();
            List<String> unsupported = new ArrayList<>();
            collectSites(referencingAst, field.getElementName(),
                declaringFqn, sites, unsupported);
            if (!unsupported.isEmpty()) {
                return ToolResponse.invalidParameter("position",
                    "'" + field.getElementName() + "' is changed in place at "
                        + unsupported.size() + " site(s), and there is no single right"
                        + " rewrite: whether the arithmetic belongs ON the value type or"
                        + " INSIDE it is the design decision you are making by adopting one."
                        + " Give the type an operation and rewrite these by hand first: "
                        + String.join(", ", unsupported));
            }

            ASTRewrite rewrite = ASTRewrite.create(referencingAst.getAST());
            // OUTSIDE the declaring file the record is reached through its owner, because it
            // is nested in it: `new Order.Priority(x)`. Inside, the simple name is what the
            // file already resolves.
            String constructed = isDeclaringUnit
                ? typeName
                : declaring.getElementName() + "." + typeName;
            String source = referencing.getSource();
            List<ASTNode> writeSites = new ArrayList<>();
            List<ASTNode> readSites = new ArrayList<>();
            for (ASTNode site : sites) {
                (isWriteTarget(site) ? writeSites : readSites).add(site);
            }
            for (ASTNode write : writeSites) {
                Expression right = ((Assignment) write.getParent()).getRightHandSide();
                // A READ OF THE SAME FIELD INSIDE THE VALUE BEING ASSIGNED — `count = count
                // + 1`, which upstream's circuit breaker writes exactly. Both the value and
                // the read inside it are sites, and asking ASTRewrite to replace a node AND
                // one of its own descendants is not a thing it can do. So the nested reads
                // are taken OUT of the node-level pass and unwrapped textually inside the
                // value instead, which is the one place the two rewrites have to agree.
                List<ASTNode> nested = new ArrayList<>();
                for (java.util.Iterator<ASTNode> it = readSites.iterator(); it.hasNext();) {
                    ASTNode read = it.next();
                    if (read.getStartPosition() >= right.getStartPosition()
                            && read.getStartPosition() + read.getLength()
                                <= right.getStartPosition() + right.getLength()) {
                        nested.add(read);
                        it.remove();
                    }
                }
                rewrite.replace(right, rewrite.createStringPlaceholder(
                    "new " + constructed + "("
                        + unwrapWithin(source, right, nested, accessor) + ")",
                    ASTNode.CLASS_INSTANCE_CREATION), null);
                writes++;
                reads += nested.size();
            }
            for (ASTNode read : readSites) {
                rewrite.replace(read, rewrite.createStringPlaceholder(
                    text(source, read) + "." + accessor + "()",
                    ASTNode.METHOD_INVOCATION), null);
                reads++;
            }

            if (isDeclaringUnit) {
                retypeDeclaration(rewrite, declaration, typeName, accessor, primitive,
                    source, referencingAst, declaring.getElementName());
                writes += declaration.fragments().size();
            }
            edits.put((IFile) referencing.getResource(),
                List.of(rewrite.rewriteAST(new Document(source),
                    FormatterOptions.forGeneratedCode(referencingAst))));
        }

        // THE SILENT NO-OP, refused rather than reported as a success. The search found
        // reference sites and the AST walk classified none of them, which cannot both be
        // true of a working match — so the operation would generate the record, retype the
        // declaration, and leave every usage reading a record where a primitive is expected.
        // The compile gate would then undo it and report a type error, which is a correct
        // outcome reached by an incomprehensible route. This says what actually went wrong.
        if (references > 0 && reads == 0 && writes == declaration.fragments().size()) {
            return ToolResponse.internalError(new IllegalStateException(
                "found " + references + " reference(s) to '" + field.getElementName()
                    + "' and matched none of them in the syntax tree. The field binding and"
                    + " the declaring type '" + declaringFqn + "' did not agree, so nothing"
                    + " would have been migrated."));
        }

        String label = "replace primitive " + primitive + " " + field.getElementName()
            + " with " + declaring.getElementName() + "." + typeName + " (" + reads
            + " read(s), " + writes + " write(s), across " + units.size() + " file(s))";
        return runPreCheckedRefactoring(service,
            new PreparedRefactoring(ChangeEngine.fromFileEdits(label, edits), label),
            "replace_primitive", arguments);
    }

    /**
     * The declaration's own edit: the record beside it, the new type on it, the initializer
     * wrapped.
     *
     * <p>All three go through the SAME {@link ASTRewrite} as the file's reads and writes.
     * Two rewrites over one file is the linked-edit hazard row 16 met from the other
     * direction — the second apply overwrites the first.</p>
     */
    private static void retypeDeclaration(ASTRewrite rewrite, FieldDeclaration declaration,
                                          String typeName, String accessor, String primitive,
                                          String source, CompilationUnit ast,
                                          String declaringName) {
        rewrite.replace(declaration.getType(),
            rewrite.createStringPlaceholder(typeName, ASTNode.SIMPLE_TYPE), null);
        for (Object fragment : declaration.fragments()) {
            Expression initializer = ((VariableDeclarationFragment) fragment).getInitializer();
            if (initializer != null) {
                rewrite.replace(initializer, rewrite.createStringPlaceholder(
                    "new " + typeName + "(" + text(source, initializer) + ")",
                    ASTNode.CLASS_INSTANCE_CREATION), null);
            }
        }

        AbstractTypeDeclaration owner = enclosingType(declaration);
        ListRewrite members = rewrite.getListRewrite(owner,
            owner.getBodyDeclarationsProperty());
        members.insertLast(rewrite.createStringPlaceholder(
            "/**\n * The " + primitive + " " + declaringName + " used to hold bare, now a"
                + " type of its own.\n"
                + " * Generated by Replace Primitive with Object (Fowler row 54); give it the"
                + " behaviour\n * that was written at every site holding the "
                + primitive + ".\n */\n"
                + "public record " + typeName + "(" + primitive + " " + accessor + ") {\n}",
            ASTNode.TYPE_DECLARATION), null);
    }

    /**
     * Every reference to the field in this unit, split into what can be rewritten and what
     * cannot.
     *
     * <p>Matched by BINDING rather than by name: a local, a parameter or another class's
     * field of the same name resolves to a different binding and is left alone. The
     * declaring class is compared too, because two classes in one file may both declare
     * {@code name}.</p>
     */
    private static void collectSites(CompilationUnit ast, String fieldName,
                                     String declaringFqn, List<ASTNode> sites,
                                     List<String> unsupported) {
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
                    return true;   // the declaration itself, retyped separately
                }
                ASTNode access = accessNodeOf(node);
                ASTNode parent = access.getParent();
                if (parent instanceof Assignment assignment
                        && assignment.getLeftHandSide() == access
                        && assignment.getOperator() != Assignment.Operator.ASSIGN) {
                    unsupported.add(where(ast, access) + " (" + assignment.getOperator() + ")");
                } else if (parent instanceof PrefixExpression prefix
                        && (prefix.getOperator() == PrefixExpression.Operator.INCREMENT
                            || prefix.getOperator() == PrefixExpression.Operator.DECREMENT)) {
                    unsupported.add(where(ast, access) + " (" + prefix.getOperator() + ")");
                } else if (parent instanceof PostfixExpression postfix) {
                    unsupported.add(where(ast, access) + " (" + postfix.getOperator() + ")");
                } else {
                    sites.add(access);
                }
                return true;
            }
        });
    }

    /**
     * The whole access, not the name inside it.
     *
     * <p>{@code this.priority} is a {@code FieldAccess} and {@code other.priority} a
     * {@code QualifiedName}; replacing the {@code SimpleName} in either would produce
     * {@code this.priority.value().value()} on one side of the edit and lose the receiver on
     * the other. The same shape {@code move kind=field} handles for {@code FieldAccess}, with
     * the qualified form added — that one is reached only from another file, which is where
     * this row goes and that one does not.</p>
     */
    private static ASTNode accessNodeOf(SimpleName node) {
        if (node.getParent() instanceof FieldAccess access && access.getName() == node) {
            return access;
        }
        if (node.getParent() instanceof QualifiedName qualified && qualified.getName() == node) {
            return qualified;
        }
        return node;
    }

    private static boolean isWriteTarget(ASTNode access) {
        return access.getParent() instanceof Assignment assignment
            && assignment.getLeftHandSide() == access
            && assignment.getOperator() == Assignment.Operator.ASSIGN;
    }

    private static String where(CompilationUnit ast, ASTNode node) {
        Object unit = ast.getJavaElement();
        String name = unit instanceof IJavaElement element ? element.getElementName() : "?";
        return name + ":" + (ast.getLineNumber(node.getStartPosition()) - 1);
    }

    /**
     * The assigned value's own text, with every read of the field inside it unwrapped.
     *
     * <p>Spliced RIGHT TO LEFT, which is the whole reason the order is stated: an insertion
     * shifts every offset after it, so working forwards makes each splice land further wrong
     * than the last. Stage 6 shipped that bug once in a string-literal rewrite and it is the
     * same arithmetic here.</p>
     */
    private static String unwrapWithin(String source, Expression right, List<ASTNode> nested,
                                       String accessor) {
        StringBuilder out = new StringBuilder(text(source, right));
        nested.sort((a, b) -> Integer.compare(b.getStartPosition(), a.getStartPosition()));
        for (ASTNode read : nested) {
            out.insert(read.getStartPosition() + read.getLength() - right.getStartPosition(),
                "." + accessor + "()");
        }
        return out.toString();
    }

    private static String text(String source, ASTNode node) {
        return source.substring(node.getStartPosition(),
            node.getStartPosition() + node.getLength());
    }

    private static AbstractTypeDeclaration enclosingType(ASTNode node) {
        for (ASTNode at = node; at != null; at = at.getParent()) {
            if (at instanceof AbstractTypeDeclaration type) {
                return type;
            }
        }
        return null;
    }

    /**
     * The single-fragment declaration of {@code name}, or null.
     *
     * <p>Null for a shared declaration ({@code int width, height;}) for the reason
     * {@code move kind=field} gives: retyping one fragment means splitting the line, which is
     * a different edit. Null is turned into that refusal by the caller.</p>
     */
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

    private static String capitalise(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
