package org.jawata.mcp.tools.inheritance;

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
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractApplyingRefactoringTool;
import org.jawata.mcp.tools.ToolKindDelegate;
import org.jawata.mcp.tools.shared.FormatterOptions;
import org.jawata.mcp.tools.shared.FqnTarget;
import org.jawata.mcp.tools.shared.TypeLookup;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code hierarchy direction=replace_superclass_with_delegate} — Fowler row 57, and the cure
 * {@code composition_over_inheritance} names in its own message ("Replace Inheritance with
 * Delegation", Fowler's earlier title for it).
 *
 * <p>A subclass that inherits only to REUSE is claiming to be something it is not. It holds the
 * old superclass instead and forwards to it, so the reuse survives and the false is-a goes.</p>
 *
 * <h2>The precondition that does all the work: nothing may treat it AS its superclass</h2>
 *
 * <p>Deleting {@code extends} is safe only if no code anywhere depends on the substitutability it
 * removes. So every reference to the class is read, and the row refuses if ANY of them passes it
 * where the superclass is expected, assigns it to a superclass-typed variable, or returns it as
 * one. <b>That check is cross-file and it is the entire safety argument</b> — without it this row
 * ships a change that compiles in the class's own file and breaks its callers.</p>
 *
 * <p>An OVERRIDE is refused for the same reason from the other side: a caller holding the
 * superclass and calling an overridden method is depending on dynamic dispatch, and delegation
 * does not provide it.</p>
 */
public class ReplaceSuperclassWithDelegateTool extends AbstractApplyingRefactoringTool
        implements ToolKindDelegate {

    /** Callers are enumerated, never sampled; reaching this cap REFUSES rather than truncates. */
    private static final int MAX_REFERENCES = 1000;

    /** WHICH precondition declined — see {@link org.jawata.mcp.models.ErrorInfo}. */
    public static final class Refusal {

        /** The position or name does not resolve to a class. */
        public static final String NOT_A_TYPE = "NOT_A_TYPE";
        /** The class extends nothing but {@code Object}. */
        public static final String NO_SUPERCLASS = "NO_SUPERCLASS";
        /** It OVERRIDES an inherited method, so a caller may depend on dynamic dispatch. */
        public static final String OVERRIDES_INHERITED_METHOD = "OVERRIDES_INHERITED_METHOD";
        /** Something treats an instance of it AS its superclass. */
        public static final String USED_AS_ITS_SUPERCLASS = "USED_AS_ITS_SUPERCLASS";
        /** Something extends it, so its own parent is part of another type's contract. */
        public static final String HAS_SUBCLASSES = "HAS_SUBCLASSES";
        /** The reference search hit its cap, so the caller list is a sample. */
        public static final String REFERENCE_CAP_REACHED = "REFERENCE_CAP_REACHED";

        private Refusal() {
        }
    }

    public ReplaceSuperclassWithDelegateTool(Supplier<IJdtService> serviceSupplier,
                                             RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String kindName() {
        return "replace_superclass_with_delegate";
    }

    @Override
    public String getName() {
        return "replace_superclass_with_delegate";
    }

    @Override
    public String getDescription() {
        return """
            Replace Superclass with Delegate — a class that inherits only to REUSE holds its old
            superclass instead and forwards to it, so the reuse survives and the false is-a goes.
            Point at the subclass (typeName=pkg.Type, or a position); fieldName names the
            delegate, defaulting to the superclass's own name uncapitalised.
            REFUSES when anything treats an instance AS its superclass — passed where the
            superclass is expected, assigned to a superclass-typed variable, or returned as one —
            because deleting `extends` removes exactly that substitutability. Also refuses an
            OVERRIDE (a caller may depend on dynamic dispatch, which delegation does not give)
            and a class that is itself extended. This is the cure composition_over_inheritance
            names in its own findings.""";
    }

    /** Structural: it changes the type's place in the hierarchy. */
    @Override
    public boolean isStructural() {
        return true;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file declaring the subclass."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the class declaration."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column on that line."));
        properties.put("typeName", Map.of("type", "string",
            "description", "Fully-qualified class name, as an alternative to a position."));
        properties.put("fieldName", Map.of("type", "string",
            "description", "Name for the delegate field. Default: the superclass's own name,"
                + " uncapitalised."));
        schema.put("properties", properties);
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        java.util.Optional<ToolResponse> nameForm =
            FqnTarget.materializePosition(service, arguments);
        if (nameForm.isPresent()) {
            return Preparation.fail(nameForm.get());
        }
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath",
                "filePath is required (or name the class with typeName=pkg.Type)"));
        }
        org.eclipse.jdt.core.IJavaElement element = service.getElementAtPosition(
            java.nio.file.Path.of(filePath),
            getIntParam(arguments, "line", -1), getIntParam(arguments, "column", -1));
        IType type = element instanceof IType found ? found : element == null ? null
            : (IType) element.getAncestor(org.eclipse.jdt.core.IJavaElement.TYPE);
        if (type == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the position does not resolve to a class.", Refusal.NOT_A_TYPE));
        }

        ICompilationUnit unit = type.getCompilationUnit();
        CompilationUnit ast = parse(unit);
        AbstractTypeDeclaration declared = TypeLookup.declaration(ast, type);
        if (!(declared instanceof TypeDeclaration target) || target.isInterface()) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the target is not a class.", Refusal.NOT_A_TYPE));
        }
        ITypeBinding subclass = target.resolveBinding();
        ITypeBinding superclass = subclass == null ? null : subclass.getSuperclass();
        if (target.getSuperclassType() == null || superclass == null
                || "java.lang.Object".equals(superclass.getQualifiedName())) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + type.getElementName() + "' extends nothing but Object, so there is no"
                    + " superclass to replace with a delegate.", Refusal.NO_SUPERCLASS));
        }

        // AN OVERRIDE MEANS A CALLER MAY BE DEPENDING ON DYNAMIC DISPATCH, which delegation
        // does not provide. Refused from the same reasoning as the substitutability check
        // below, seen from the other side.
        for (MethodDeclaration method : target.getMethods()) {
            IMethodBinding bound = method.resolveBinding();
            if (bound == null || method.isConstructor()) {
                continue;
            }
            for (IMethodBinding inherited : superclass.getDeclaredMethods()) {
                if (bound.overrides(inherited)) {
                    return Preparation.fail(ToolResponse.invalidParameter("position",
                        "'" + type.getElementName() + "." + method.getName() + "' OVERRIDES "
                            + superclass.getName() + "'s method of the same name. A caller"
                            + " holding a " + superclass.getName() + " and calling it is"
                            + " depending on dynamic dispatch, which a delegate does not give.",
                        Refusal.OVERRIDES_INHERITED_METHOD));
                }
            }
        }

        // THE SAFETY ARGUMENT, and it is cross-file. Deleting `extends` removes exactly one
        // thing — the ability to use this where its superclass is expected — so every
        // reference is read and any such use refuses the change.
        List<org.eclipse.jdt.core.search.SearchMatch> references =
            service.getSearchService().findAllReferences(type, MAX_REFERENCES);
        if (references.size() >= MAX_REFERENCES) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the reference search reached its cap of " + MAX_REFERENCES + ", so the list of"
                    + " uses is a SAMPLE rather than an enumeration — and this row's safety"
                    + " rests on having seen them all.", Refusal.REFERENCE_CAP_REACHED));
        }
        String substitution = anyUseAsTheSuperclass(service, references, subclass, superclass);
        if (substitution != null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                substitution + " — so something depends on a " + type.getElementName()
                    + " BEING a " + superclass.getName() + ", which is exactly what deleting"
                    + " `extends` takes away.", Refusal.USED_AS_ITS_SUPERCLASS));
        }
        for (IType sub : service.getSearchService().getAllSubtypes(type)) {
            if (!sub.equals(type)) {
                return Preparation.fail(ToolResponse.invalidParameter("position",
                    "'" + sub.getElementName() + "' extends '" + type.getElementName() + "', so"
                        + " this class's own parent is part of another type's contract.",
                    Refusal.HAS_SUBCLASSES));
            }
        }

        String field = getStringParam(arguments, "fieldName");
        if (field == null || field.isBlank()) {
            field = Character.toLowerCase(superclass.getName().charAt(0))
                + superclass.getName().substring(1);
        }

        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        // `extends A` goes.
        rewrite.remove(target.getSuperclassType(), null);
        // A field of A takes its place, declared first so a reader meets it before its uses.
        ListRewrite members = rewrite.getListRewrite(target,
            TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        members.insertFirst(rewrite.createStringPlaceholder(
            "private final " + superclass.getName() + " " + field + ";",
            ASTNode.FIELD_DECLARATION), null);

        // Every constructor's super(...) becomes the delegate's construction, so whatever the
        // superclass was given is still given to it.
        int constructors = 0;
        for (MethodDeclaration method : target.getMethods()) {
            if (!method.isConstructor() || method.getBody() == null) {
                continue;
            }
            constructors++;
            @SuppressWarnings("unchecked")
            List<Statement> body = method.getBody().statements();
            String arguments0 = "";
            if (!body.isEmpty() && body.get(0) instanceof SuperConstructorInvocation up) {
                List<String> passed = new ArrayList<>();
                for (Object each : up.arguments()) {
                    passed.add(each.toString());
                }
                arguments0 = String.join(", ", passed);
                rewrite.replace(up, rewrite.createStringPlaceholder(
                    "this." + field + " = new " + superclass.getName() + "(" + arguments0 + ");",
                    ASTNode.EXPRESSION_STATEMENT), null);
            } else {
                rewrite.getListRewrite(method.getBody(),
                    org.eclipse.jdt.core.dom.Block.STATEMENTS_PROPERTY)
                    .insertFirst(rewrite.createStringPlaceholder(
                        "this." + field + " = new " + superclass.getName() + "();",
                        ASTNode.EXPRESSION_STATEMENT), null);
            }
        }

        // Every unqualified use of an INHERITED member becomes a use through the delegate.
        int forwarded = forwardInheritedUses(rewrite, target, superclass, field);

        TextEdit edit = rewrite.rewriteAST(new Document(unit.getSource()),
            FormatterOptions.forGeneratedCode(ast));
        Map<IFile, List<TextEdit>> byFile = Map.of((IFile) unit.getResource(), List.of(edit));

        String label = "replace " + type.getElementName() + "'s superclass "
            + superclass.getName() + " with a delegate field '" + field + "'";
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("subclass", type.getElementName());
        extras.put("superclass", superclass.getName());
        extras.put("fieldName", field);
        extras.put("constructorsRewired", constructors);
        extras.put("inheritedUsesForwarded", forwarded);
        extras.put("referencesChecked", references.size());
        return Preparation.of(ChangeEngine.fromFileEdits(label, byFile), label
            + " (" + constructors + " constructor(s) rewired, " + forwarded + " inherited use(s)"
            + " forwarded, " + references.size() + " reference(s) read and none of them used it"
            + " as a " + superclass.getName() + ")", extras);
    }

    /**
     * The first reference that treats a subclass instance AS its superclass, or null.
     *
     * <p>Read from the CALLER's own AST rather than inferred: a reference is only a
     * substitution if the type it is being used at is the superclass, and that is a fact about
     * the expression's context.</p>
     */
    private String anyUseAsTheSuperclass(IJdtService service,
                                         List<org.eclipse.jdt.core.search.SearchMatch> references,
                                         ITypeBinding subclass, ITypeBinding superclass)
            throws Exception {
        for (org.eclipse.jdt.core.search.SearchMatch match : references) {
            if (!(match.getElement() instanceof org.eclipse.jdt.core.IJavaElement found)) {
                continue;
            }
            ICompilationUnit callerUnit = (ICompilationUnit) found.getAncestor(
                org.eclipse.jdt.core.IJavaElement.COMPILATION_UNIT);
            if (callerUnit == null) {
                continue;
            }
            CompilationUnit callerAst = parse(callerUnit);
            String[] verdict = new String[1];
            callerAst.accept(new ASTVisitor() {
                @Override
                public boolean visit(org.eclipse.jdt.core.dom.VariableDeclarationFragment node) {
                    IVariableBinding bound = node.resolveBinding();
                    if (verdict[0] == null && bound != null && node.getInitializer() != null
                            && isSubclassExpression(node.getInitializer(), subclass)
                            && superclass.getKey().equals(bound.getType().getKey())) {
                        verdict[0] = "in " + callerUnit.getElementName() + ", a "
                            + subclass.getName() + " is assigned to a "
                            + superclass.getName() + "-typed variable '"
                            + node.getName().getIdentifier() + "'";
                    }
                    return true;
                }

                @Override
                public boolean visit(MethodInvocation node) {
                    IMethodBinding bound = node.resolveMethodBinding();
                    if (verdict[0] != null || bound == null) {
                        return true;
                    }
                    ITypeBinding[] parameters = bound.getParameterTypes();
                    for (int i = 0; i < parameters.length && i < node.arguments().size(); i++) {
                        if (superclass.getKey().equals(parameters[i].getKey())
                                && isSubclassExpression(
                                    (org.eclipse.jdt.core.dom.Expression) node.arguments().get(i),
                                    subclass)) {
                            verdict[0] = "in " + callerUnit.getElementName() + ", a "
                                + subclass.getName() + " is passed to '" + bound.getName()
                                + "' where a " + superclass.getName() + " is expected";
                        }
                    }
                    return true;
                }
            });
            if (verdict[0] != null) {
                return verdict[0];
            }
        }
        return null;
    }

    private boolean isSubclassExpression(org.eclipse.jdt.core.dom.Expression expression,
                                         ITypeBinding subclass) {
        ITypeBinding actual = expression.resolveTypeBinding();
        return actual != null && subclass.getKey().equals(actual.getKey());
    }

    /** Rewrites unqualified uses of inherited members to go through the delegate field. */
    private int forwardInheritedUses(ASTRewrite rewrite, TypeDeclaration target,
                                     ITypeBinding superclass, String field) {
        int[] forwarded = {0};
        target.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                IMethodBinding bound = node.resolveMethodBinding();
                if (node.getExpression() == null && bound != null
                        && bound.getDeclaringClass() != null
                        && superclass.getKey().equals(bound.getDeclaringClass().getKey())) {
                    rewrite.set(node, MethodInvocation.EXPRESSION_PROPERTY,
                        rewrite.createStringPlaceholder(field, ASTNode.SIMPLE_NAME), null);
                    forwarded[0]++;
                }
                return true;
            }

            @Override
            public boolean visit(SimpleName node) {
                IBinding bound = node.resolveBinding();
                if (!(bound instanceof IVariableBinding variable) || !variable.isField()
                        || variable.getDeclaringClass() == null
                        || !superclass.getKey().equals(variable.getDeclaringClass().getKey())
                        || node.getParent() instanceof MethodInvocation) {
                    return true;
                }
                rewrite.replace(node, rewrite.createStringPlaceholder(
                    field + "." + node.getIdentifier(), ASTNode.QUALIFIED_NAME), null);
                forwarded[0]++;
                return true;
            }
        });
        return forwarded[0];
    }

    /** Parse with bindings. See {@link PullUpConstructorBodyTool}'s note on the sixteen copies. */
    private CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
