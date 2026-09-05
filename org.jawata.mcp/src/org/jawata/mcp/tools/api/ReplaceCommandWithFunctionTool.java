package org.jawata.mcp.tools.api;

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
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.core.search.SearchMatch;
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
 * {@code change_method_signature kind=replace_command_with_function} — Fowler row 41, Replace
 * Command with Function.
 *
 * <p>The exact inverse of row 48, {@code extract kind=function_to_command}. A command object earns
 * its keep when the call has to be taken apart — queued, logged, undone, or decomposed into named
 * steps that share its fields. A command that is only ever constructed and immediately run is
 * paying for all of that and using none of it: two lines and a class where one call would do.</p>
 *
 * <pre>{@code
 * new PriceCalculator(order, rate).execute()   ->   PriceCalculator.execute(order, rate)
 * }</pre>
 *
 * <h2>The dominant case is a REFUSAL, and the corpus is what says so</h2>
 *
 * <p>Over the fork's 1354 main sources, 33 classes have exactly one public method and it is named
 * like a command. <b>27 of the 33 declare it because a SUPERTYPE does</b> — a {@code Runnable}, a
 * {@code Callable}, a strategy, a filter, an HTTP handler. Those are not paying for a command they
 * do not use; they are being DISPATCHED, and turning one into a static function removes the choice
 * the caller was making. So the first thing this row establishes is that no supertype declares the
 * method, and it refuses by far more often than it performs.</p>
 *
 * <h2>The function's parameters are the CONSTRUCTOR's, and that has to be exact</h2>
 *
 * <p>Each field must be assigned in the one constructor as {@code this.f = p;} and written nowhere
 * else. Then the body's reads of {@code f} become reads of {@code p}, and the function's parameter
 * list is the constructor's followed by the method's own. A constructor that COMPUTES a field
 * ({@code this.total = a + b}) is refused: a function taking {@code a} and {@code b} would have to
 * carry that computation, which is code this row would be inventing rather than moving.</p>
 *
 * <p><b>The class stays, holding the one static method</b>, which in Java is what a function is.
 * Moving it somewhere else is {@code move kind=method}, applied where the caller judges it worth
 * it — the same division row 35 makes between the mechanical step and the judgement.</p>
 */
public class ReplaceCommandWithFunctionTool extends AbstractApplyingRefactoringTool
        implements ToolKindDelegate {

    /** Callers are enumerated, never sampled; reaching this cap REFUSES rather than truncates. */
    private static final int MAX_REFERENCES = 1000;

    /** WHICH precondition declined — see {@link org.jawata.mcp.models.ErrorInfo}. */
    public static final class Refusal {

        /** The position or name does not resolve to a class. */
        public static final String NOT_A_TYPE = "NOT_A_TYPE";
        /** The class does not have exactly one public instance method to become the function. */
        public static final String NOT_A_COMMAND = "NOT_A_COMMAND";
        /** A supertype declares the method, so the command is being DISPATCHED. */
        public static final String METHOD_IS_INHERITED = "METHOD_IS_INHERITED";
        /** Zero or several constructors: which parameter list the function takes is a choice. */
        public static final String NOT_ONE_CONSTRUCTOR = "NOT_ONE_CONSTRUCTOR";
        /** A field is computed, or written outside the constructor, so a function cannot carry it. */
        public static final String STATE_NOT_CONSTRUCTOR_ONLY = "STATE_NOT_CONSTRUCTOR_ONLY";
        /** A use is not {@code new C(...).m(...)}, so this rewrite cannot reach it. */
        public static final String CALL_SITE_NOT_CHAINED = "CALL_SITE_NOT_CHAINED";
        /** Nothing constructs the command, so there is no call the function would serve. */
        public static final String NO_CALL_SITES = "NO_CALL_SITES";
        /** The reference search hit its cap, so the caller list is a sample. */
        public static final String REFERENCE_CAP_REACHED = "REFERENCE_CAP_REACHED";

        private Refusal() {
        }
    }

    public ReplaceCommandWithFunctionTool(Supplier<IJdtService> serviceSupplier,
                                          RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String kindName() {
        return "replace_command_with_function";
    }

    @Override
    public String getName() {
        return "replace_command_with_function";
    }

    @Override
    public String getDescription() {
        return """
            Replace Command with Function — a command object that is only ever constructed and
            immediately run becomes one static function. Name the CLASS (typeName=pkg.Type, or a
            position). Its single public method takes the constructor's parameters followed by
            its own, the fields and constructor go, and every `new C(a).m(b)` becomes `C.m(a,
            b)`. The exact inverse of extract kind=function_to_command. REFUSES when a supertype
            declares the method — that command is being dispatched, not wasted — which is by far
            the commonest case; also a computed field, several constructors, and a use this
            rewrite cannot reach.""";
    }

    /** Structural: the class loses its state and every construction site changes shape. */
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
            "description", "Source file declaring the command class."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of a caret in the class."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column on that line."));
        properties.put("typeName", Map.of("type", "string",
            "description", "Fully-qualified name of the command class, instead of a position."));
        schema.put("properties", properties);
        schema.put("required", List.of());
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
        IJavaElement element = service.getElementAtPosition(Path.of(filePath),
            getIntParam(arguments, "line", -1), getIntParam(arguments, "column", -1));
        IType type = element instanceof IType t ? t
            : element == null ? null : (IType) element.getAncestor(IJavaElement.TYPE);
        if (type == null || type.getCompilationUnit() == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the position does not resolve to a class in this workspace.",
                Refusal.NOT_A_TYPE));
        }

        ICompilationUnit unit = type.getCompilationUnit();
        CompilationUnit ast = IntroducedParameter.parse(unit);
        if (!(TypeLookup.declaration(ast, type) instanceof TypeDeclaration decl)
            || decl.isInterface()) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "could not locate a class declaration for " + type.getElementName() + ".",
                Refusal.NOT_A_TYPE));
        }

        List<MethodDeclaration> constructors = new ArrayList<>();
        List<MethodDeclaration> commands = new ArrayList<>();
        List<FieldDeclaration> fields = new ArrayList<>();
        for (Object member : decl.bodyDeclarations()) {
            if (member instanceof FieldDeclaration field
                && !Modifier.isStatic(field.getModifiers())) {
                fields.add(field);
            } else if (member instanceof MethodDeclaration m) {
                if (m.isConstructor()) {
                    constructors.add(m);
                } else if (!Modifier.isStatic(m.getModifiers()) && !isObjectOverride(m)) {
                    commands.add(m);
                }
            }
        }
        if (commands.size() != 1) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                type.getElementName() + " has " + commands.size() + " instance method(s) besides"
                    + " constructors and the Object overrides. A command is one thing to do; a"
                    + " class with several is an object, and turning it into a function would"
                    + " have to choose which of them the function is.",
                Refusal.NOT_A_COMMAND));
        }
        MethodDeclaration command = commands.get(0);

        // THE DOMINANT REFUSAL, and the corpus is why it runs first: 27 of the fork's 33
        // single-method command-shaped classes declare that method because a supertype does.
        IMethodBinding binding = command.resolveBinding();
        String inherited = binding == null ? null : declaringSupertype(binding);
        if (inherited != null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                inherited + " declares " + command.getName().getIdentifier() + " too, so this"
                    + " command is being DISPATCHED rather than wasted — the caller chose this"
                    + " implementation by constructing it. A static function has no dispatch, so"
                    + " the choice would vanish.", Refusal.METHOD_IS_INHERITED));
        }

        if (constructors.size() != 1) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                type.getElementName() + " declares " + constructors.size() + " constructors, so"
                    + " which parameter list the function takes is a choice rather than a"
                    + " reading.", Refusal.NOT_ONE_CONSTRUCTOR));
        }
        MethodDeclaration constructor = constructors.get(0);

        // Each field must be assigned EXACTLY `this.f = p;` in that constructor and written
        // nowhere else. Anything computed is code this row would be inventing rather than moving.
        String source = unit.getSource();
        Map<String, String> fieldToParameter = new LinkedHashMap<>();
        String settled = settleFields(constructor, fields, fieldToParameter);
        if (settled != null) {
            return Preparation.fail(ToolResponse.invalidParameter("position", settled,
                Refusal.STATE_NOT_CONSTRUCTOR_ONLY));
        }
        String elsewhere = writtenOutside(decl, constructor, fieldToParameter.keySet());
        if (elsewhere != null) {
            return Preparation.fail(ToolResponse.invalidParameter("position", elsewhere,
                Refusal.STATE_NOT_CONSTRUCTOR_ONLY));
        }

        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> constructorParameters = constructor.parameters();
        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> commandParameters = command.parameters();
        Set<String> names = new LinkedHashSet<>();
        for (SingleVariableDeclaration p : constructorParameters) {
            names.add(p.getName().getIdentifier());
        }
        for (SingleVariableDeclaration p : commandParameters) {
            if (!names.add(p.getName().getIdentifier())) {
                return Preparation.fail(ToolResponse.invalidParameter("position",
                    "'" + p.getName().getIdentifier() + "' names both a constructor parameter and"
                        + " a parameter of " + command.getName().getIdentifier() + ", so the"
                        + " function's parameter list would declare it twice.",
                    Refusal.STATE_NOT_CONSTRUCTOR_ONLY));
            }
        }

        List<SearchMatch> references =
            service.getSearchService().findAllReferences(type, MAX_REFERENCES);
        if (references.size() >= MAX_REFERENCES) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                type.getElementName() + " has at least " + MAX_REFERENCES + " references, which"
                    + " is the search cap — some construction site would be left building a"
                    + " command that no longer has a constructor, unseen.",
                Refusal.REFERENCE_CAP_REACHED));
        }

        Map<ICompilationUnit, CompilationUnit> asts = new LinkedHashMap<>();
        Map<ICompilationUnit, ASTRewrite> rewrites = new LinkedHashMap<>();
        asts.put(unit, ast);
        rewrites.put(unit, ASTRewrite.create(ast.getAST()));

        int rewritten = 0;
        for (SearchMatch match : references) {
            ICompilationUnit callerUnit = match.getElement() instanceof IJavaElement e
                ? (ICompilationUnit) e.getAncestor(IJavaElement.COMPILATION_UNIT) : null;
            if (callerUnit == null) {
                continue;
            }
            CompilationUnit callerAst = asts.computeIfAbsent(callerUnit,
                IntroducedParameter::parse);
            ASTRewrite rewrite = rewrites.computeIfAbsent(callerUnit,
                u -> ASTRewrite.create(callerAst.getAST()));
            ClassInstanceCreation made = creationAt(callerAst, match.getOffset());
            if (made == null) {
                continue;   // a reference that is not a construction — an import, the declaration
            }
            if (!(made.getParent() instanceof MethodInvocation call)
                || call.getExpression() != made
                || !call.getName().getIdentifier().equals(command.getName().getIdentifier())) {
                return Preparation.fail(ToolResponse.invalidParameter("position",
                    "a use in " + callerUnit.getElementName() + " constructs "
                        + type.getElementName() + " without running it in the same expression:"
                        + " " + text(callerUnit.getSource(), made) + ". That one is holding the"
                        + " command, which is the thing a command is FOR, so this row will not"
                        + " take it away.", Refusal.CALL_SITE_NOT_CHAINED));
            }
            List<String> passed = new ArrayList<>();
            for (Object argument : made.arguments()) {
                passed.add(text(callerUnit.getSource(), (Expression) argument));
            }
            for (Object argument : call.arguments()) {
                passed.add(text(callerUnit.getSource(), (Expression) argument));
            }
            // THE QUALIFIER IS THE CALLER'S OWN, not the model's simple name. A nested command
            // is written `CommandTargets.Discount` where it is used, and IType.getElementName()
            // answers `Discount` — which does not resolve there. Reusing the text the caller
            // already wrote is the only spelling guaranteed to mean the same thing in that file,
            // which is row 55's finding about copied expressions, on the qualifier this time.
            rewrite.replace(call, rewrite.createStringPlaceholder(
                text(callerUnit.getSource(), made.getType()) + "."
                    + command.getName().getIdentifier()
                    + "(" + String.join(", ", passed) + ")", ASTNode.METHOD_INVOCATION), null);
            rewritten++;
        }

        if (rewritten == 0) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "nothing constructs " + type.getElementName() + " and runs it, so there is no"
                    + " call the function would serve. The point of this row is what the USE"
                    + " sites read like afterwards.", Refusal.NO_CALL_SITES));
        }

        // The class: the fields and the constructor go, and the command becomes a static method
        // whose parameters are the constructor's followed by its own.
        ASTRewrite own = rewrites.get(unit);
        ListRewrite members = own.getListRewrite(decl, decl.getBodyDeclarationsProperty());
        for (FieldDeclaration field : fields) {
            members.remove(field, null);
        }
        members.remove(constructor, null);
        own.getListRewrite(command, MethodDeclaration.MODIFIERS2_PROPERTY)
            .insertLast(own.getAST().newModifier(Modifier.ModifierKeyword.STATIC_KEYWORD), null);
        ListRewrite signature =
            own.getListRewrite(command, MethodDeclaration.PARAMETERS_PROPERTY);
        for (int i = constructorParameters.size() - 1; i >= 0; i--) {
            signature.insertFirst(own.createStringPlaceholder(
                text(source, constructorParameters.get(i)),
                ASTNode.SINGLE_VARIABLE_DECLARATION), null);
        }
        int reads = 0;
        for (Map.Entry<String, String> entry : fieldToParameter.entrySet()) {
            for (ASTNode read : fieldReads(command, entry.getKey())) {
                own.replace(read, own.getAST().newSimpleName(entry.getValue()), null);
                reads++;
            }
        }

        Map<IFile, List<TextEdit>> byFile = new LinkedHashMap<>();
        for (Map.Entry<ICompilationUnit, ASTRewrite> entry : rewrites.entrySet()) {
            ICompilationUnit each = entry.getKey();
            TextEdit edit = entry.getValue().rewriteAST(new Document(each.getSource()),
                FormatterOptions.forGeneratedCode(asts.get(each)));
            if (edit.hasChildren()) {
                byFile.put((IFile) each.getResource(), List.of(edit));
            }
        }

        String label = "replace command " + type.getElementName() + " with the function "
            + type.getElementName() + "." + command.getName().getIdentifier();
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("type", type.getElementName());
        extras.put("function", command.getName().getIdentifier());
        extras.put("parametersFromConstructor", fieldToParameter.values());
        extras.put("fieldReadsRewritten", reads);
        extras.put("useSitesRewritten", rewritten);
        return Preparation.of(ChangeEngine.fromFileEdits(label, byFile),
            label + " (" + fieldToParameter.size() + " field(s) became parameter(s), " + reads
                + " read(s) in the body, " + rewritten + " use site(s) rewritten). The class"
                + " remains as the function's home; move kind=method relocates it.", extras);
    }

    /** The supertype that also declares this method, or {@code null} if none does. */
    private static String declaringSupertype(IMethodBinding binding) {
        ITypeBinding owner = binding.getDeclaringClass();
        if (owner == null) {
            return null;
        }
        List<ITypeBinding> supertypes = new ArrayList<>(List.of(owner.getInterfaces()));
        if (owner.getSuperclass() != null) {
            supertypes.add(owner.getSuperclass());
        }
        for (ITypeBinding supertype : supertypes) {
            if (supertype == null || "java.lang.Object".equals(supertype.getQualifiedName())) {
                continue;
            }
            for (IMethodBinding candidate : supertype.getDeclaredMethods()) {
                if (binding.overrides(candidate)
                    || candidate.getName().equals(binding.getName())) {
                    return supertype.getName();
                }
            }
            String deeper = declaringSupertypeOf(binding, supertype);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    private static String declaringSupertypeOf(IMethodBinding binding, ITypeBinding from) {
        List<ITypeBinding> supertypes = new ArrayList<>(List.of(from.getInterfaces()));
        if (from.getSuperclass() != null) {
            supertypes.add(from.getSuperclass());
        }
        for (ITypeBinding supertype : supertypes) {
            if (supertype == null || "java.lang.Object".equals(supertype.getQualifiedName())) {
                continue;
            }
            for (IMethodBinding candidate : supertype.getDeclaredMethods()) {
                if (candidate.getName().equals(binding.getName())) {
                    return supertype.getName();
                }
            }
            String deeper = declaringSupertypeOf(binding, supertype);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    /**
     * Bind each field to the constructor parameter that assigns it, or say why one cannot be.
     *
     * @return a refusal message, or {@code null} when every field is settled
     */
    private static String settleFields(MethodDeclaration constructor, List<FieldDeclaration> fields,
                                       Map<String, String> out) {
        Set<String> declared = new LinkedHashSet<>();
        for (FieldDeclaration field : fields) {
            for (Object fragment : field.fragments()) {
                VariableDeclarationFragment f = (VariableDeclarationFragment) fragment;
                if (f.getInitializer() != null) {
                    return "the field '" + f.getName().getIdentifier() + "' has an initializer of"
                        + " its own, so it is state the constructor was not handed and a"
                        + " function's parameter list cannot carry it.";
                }
                declared.add(f.getName().getIdentifier());
            }
        }
        if (constructor.getBody() == null) {
            return "the constructor has no body to read the field assignments from.";
        }
        for (Object statement : constructor.getBody().statements()) {
            if (!(statement instanceof org.eclipse.jdt.core.dom.ExpressionStatement e)
                || !(e.getExpression() instanceof Assignment assignment)
                || assignment.getOperator() != Assignment.Operator.ASSIGN) {
                return "the constructor does more than assign its parameters to fields, so a"
                    + " function taking those parameters would have to carry that work too —"
                    + " which is code this row would be inventing rather than moving.";
            }
            String field = assignedField(assignment.getLeftHandSide());
            if (field == null || !declared.contains(field)) {
                return "the constructor assigns something other than one of this class's own"
                    + " fields, so the mapping from field to parameter is not a reading.";
            }
            if (!(assignment.getRightHandSide() instanceof SimpleName value)) {
                return "the field '" + field + "' is COMPUTED in the constructor rather than"
                    + " taken from a parameter, so a function taking the parameters would have"
                    + " to carry that computation.";
            }
            out.put(field, value.getIdentifier());
        }
        if (out.size() != declared.size()) {
            return "the constructor settles " + out.size() + " of this class's " + declared.size()
                + " field(s); the rest are state a function's parameters cannot carry.";
        }
        return null;
    }

    private static String assignedField(Expression target) {
        if (target instanceof FieldAccess access
            && access.getExpression() instanceof ThisExpression) {
            return access.getName().getIdentifier();
        }
        return target instanceof SimpleName name ? name.getIdentifier() : null;
    }

    /** A field written anywhere but the constructor means the object carries state over time. */
    private static String writtenOutside(TypeDeclaration decl, MethodDeclaration constructor,
                                         Set<String> fields) {
        String[] found = { null };
        for (Object member : decl.bodyDeclarations()) {
            if (member == constructor || !(member instanceof BodyDeclaration body)) {
                continue;
            }
            body.accept(new ASTVisitor() {
                @Override
                public boolean visit(Assignment node) {
                    String field = assignedField(node.getLeftHandSide());
                    if (found[0] == null && field != null && fields.contains(field)) {
                        found[0] = field;
                    }
                    return true;
                }
            });
        }
        return found[0] == null ? null
            : "the field '" + found[0] + "' is written outside the constructor, so the object"
                + " carries state between being built and being run — which is what a command is"
                + " for, and what a function cannot do.";
    }

    /** Every read of that field inside the command's body, however it is spelled. */
    private static List<ASTNode> fieldReads(MethodDeclaration command, String field) {
        List<ASTNode> found = new ArrayList<>();
        if (command.getBody() == null) {
            return found;
        }
        command.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(FieldAccess node) {
                if (node.getExpression() instanceof ThisExpression
                    && field.equals(node.getName().getIdentifier())) {
                    found.add(node);
                }
                return true;
            }

            @Override
            public boolean visit(SimpleName node) {
                if (field.equals(node.getIdentifier())
                    && !(node.getParent() instanceof FieldAccess)
                    && node.resolveBinding() instanceof IVariableBinding v && v.isField()) {
                    found.add(node);
                }
                return true;
            }
        });
        return found;
    }

    private static boolean isObjectOverride(MethodDeclaration m) {
        String name = m.getName().getIdentifier();
        return ("toString".equals(name) && m.parameters().isEmpty())
            || ("hashCode".equals(name) && m.parameters().isEmpty())
            || ("equals".equals(name) && m.parameters().size() == 1);
    }

    private static ClassInstanceCreation creationAt(CompilationUnit ast, int offset) {
        ASTNode node = org.eclipse.jdt.core.dom.NodeFinder.perform(ast, offset, 0);
        while (node != null && !(node instanceof ClassInstanceCreation)) {
            if (node instanceof MethodDeclaration || node instanceof TypeDeclaration) {
                return null;
            }
            node = node.getParent();
        }
        return (ClassInstanceCreation) node;
    }

    private static String text(String source, ASTNode node) {
        return source.substring(node.getStartPosition(),
            node.getStartPosition() + node.getLength());
    }
}
