package org.jawata.mcp.tools.data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jface.text.Document;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.PreparedRefactoring;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractRefactoringTool;
import org.jawata.mcp.tools.ToolKindDelegate;
import org.jawata.mcp.tools.shared.FormatterOptions;
import org.jawata.mcp.tools.shared.FqnTarget;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code data kind=remove_setting_method} — Fowler row 37, Remove Setting Method.
 *
 * <p>A field that should be settled at construction and never altered still has a setter, so
 * nothing about the class says the value is fixed and any caller may move it. The cure is to
 * take the setter away and let the field be {@code final}, which states the same thing in a
 * form the compiler enforces.</p>
 *
 * <h2>The precondition is the whole operation, and it is a QUESTION ABOUT CALLERS</h2>
 *
 * <p>Removing a setter is only safe where its callers are the declaring class's own
 * constructors — those become direct assignments and the class keeps working. A caller
 * anywhere else is asking to change the value after construction, which is exactly the
 * behaviour being removed, and rerouting it into a constructor is a design decision about
 * that caller's own code. So an outside caller REFUSES, with every site named. This is the
 * one refusal a reader should expect to meet, because it is the state most fields with a
 * setter are actually in.</p>
 *
 * <h2>The plan's recipe named a step that cannot do the job, and this is that finding</h2>
 *
 * <p>Sprint 28d-rescue's Stage 2 table composes this row as <em>zero-writer check → delete
 * atom → {@code add_final}</em>. Measured before building: {@code apply_cleanup
 * kind=add_final} calls JDT's {@code VariableDeclarationFixCore.createCleanUp(ast,
 * addFinalFields=false, addFinalParameters=true, addFinalLocals=true)} — fields are
 * explicitly OUT of that kind's contract, and its own javadoc says so. The third step
 * therefore cannot make the field final, and {@code apply_cleanup} belongs to Stage 3, which
 * is closed, so widening it here would break the plan's one-owner rule. The plan's standing
 * ruling covers exactly this: <em>"A chain that cannot hold together becomes written code,
 * and nothing is asked."</em> So the modifier is added here, by this row, and the deviation
 * is recorded rather than escalated.</p>
 *
 * <p><b>The delete atom is not used either, and that is a second measured call.</b>
 * {@link org.jawata.mcp.refactoring.atoms.DeleteAtom} wraps JDT's delete processor, whose
 * value is deleting an element whose REFERENCES must be considered. Here the references have
 * already been settled by the refusal above — outside callers refuse, constructor callers are
 * rewritten in the same edit — so the atom would add a second engine writing the same file
 * beside this row's own rewrite, which is the shape that produces conflicting edits. Rows 2,
 * 34 and 37 were the atom's three intended callers and all three have now declined it for
 * reasons of their own; that is a fact for the checkpoint, not something to design around
 * here.</p>
 *
 * <h2>{@code final} is applied only where it is PROVABLY safe, and reported either way</h2>
 *
 * <p>Definite assignment is the compiler's analysis, not this one's, so the rule here is
 * deliberately narrower than the language's: the modifier goes on only when every constructor
 * assigns the field exactly once as a direct statement of its own body and nothing outside
 * writes it, or when the field has an initializer and no constructor touches it. Anything
 * else — a conditional assignment, two assignments on one path, a write from another method —
 * leaves the field as it was, and the response says which case applied. A row that guessed
 * here would produce a change the compile gate throws away, and the caller would learn only
 * that it did not work.</p>
 *
 * <h2>{@link #prepare} is public because row 2 composes this row</h2>
 *
 * <p>Change Reference to Value ({@code data kind=reference_to_value}) is this operation run
 * over every setter a class has, then {@code generate kind=equals_hashcode}. A recipe step
 * owes the engine a {@link Change}, and the two {@code run…} paths on the base class apply
 * and respond rather than handing one back — so the analysis and the change-building live in
 * {@code prepare}, and the direct path is a thin wrapper over it. ONE construction, so a fix
 * to the analysis cannot reach one caller and miss the other.</p>
 */
public class RemoveSettingMethodTool extends AbstractRefactoringTool implements ToolKindDelegate {

    /** Callers are enumerated, never sampled — the refusal quotes them. */
    private static final int MAX_REFERENCES = 1000;

    /**
     * A removal that is ready to perform, or the refusal that stopped it.
     *
     * <p>Exactly one of {@code change} and {@code refusal} is non-null. {@code finalNote} is
     * null when the field became final, and otherwise says why it did not.</p>
     *
     * <p><b>{@code finalNote} has no reader today, and saying so is the point.</b> The same
     * sentence is already inside {@code label}, which every caller does read, so the field is
     * the fact offered separately in case a caller wants it apart from the prose. An earlier
     * version of this javadoc claimed row 2 folded it into its own summary; row 2 does not, and
     * an unread field described as read is how a plausible claim outlives the code it was
     * written for.</p>
     */
    public record Prepared(Change change, String label, String finalNote, ToolResponse refusal) {

        static Prepared refused(ToolResponse response) {
            return new Prepared(null, null, null, response);
        }
    }

    public RemoveSettingMethodTool(Supplier<IJdtService> serviceSupplier,
                                   RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String kindName() {
        return "remove_setting_method";
    }

    @Override
    public String kindSummary() {
        return """
            take away a field's setter so the value is settled at construction:
            each of the declaring class's own constructors that called it assigns
            the field directly instead, and the field becomes final when that is
            provably safe. Point at the SETTER (position, or
            symbol=pkg.Type#setX). Refuses a caller outside the declaring class's
            constructors — that caller wants to change the value after
            construction, which is the behaviour being removed — and names every
            such site. Refuses a method that is not a plain one-parameter field
            assignment, a static field, and a setter that overrides or is
            overridden. The field is left non-final, with the reason, when
            definite assignment is not provable here.""";
    }

    /** Structural: it takes a method off the class's published surface. */
    @Override
    public boolean isStructural() {
        return true;
    }

    @Override
    public String getName() {
        return "remove_setting_method";
    }

    @Override
    public String getDescription() {
        return "Remove Setting Method — take away a field's setter, move its constructor "
            + "callers to a direct assignment, and make the field final where that is safe. "
            + "Delegate of data.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file declaring the setter."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the setter's declaration."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column inside the setter."));
        properties.put("symbol", FqnTarget.symbolSchemaProperty("setter to remove"));
        schema.put("properties", properties);
        // Sprint 24 (D1): position OR name form, so neither is required on its own.
        schema.put("required", List.of());
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    public ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        java.util.Optional<ToolResponse> nameForm =
            FqnTarget.materializePosition(service, arguments);
        if (nameForm.isPresent()) {
            return nameForm.get();
        }
        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (filePathStr == null || filePathStr.isBlank()) {
            return ToolResponse.invalidParameter("filePath",
                "filePath is required (or name the setter with symbol=pkg.Type#setX)");
        }
        if (line < 0 || column < 0) {
            return ToolResponse.invalidCoordinates(line, column,
                "line and column are required and must be zero-based non-negative integers");
        }
        try {
            Path filePath = service.getPathUtils().resolve(filePathStr);
            IJavaElement element = service.getElementAtPosition(filePath, line, column);
            if (!(element instanceof IMethod setter)) {
                return ToolResponse.invalidParameter("position",
                    "position does not resolve to a method; got "
                        + (element == null ? "nothing" : element.getClass().getSimpleName())
                        + ". Remove Setting Method acts on the SETTER itself.");
            }
            Prepared prepared = prepare(service, setter);
            if (prepared.refusal() != null) {
                return prepared.refusal();
            }
            return runPreCheckedRefactoring(service,
                new PreparedRefactoring(prepared.change(), prepared.label()),
                "remove_setting_method", arguments);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(RemoveSettingMethodTool.class)
                .warn("remove_setting_method failed: {}", e.toString(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Analyse the setter and build the removal, without performing it.
     *
     * <p>The whole operation lives here so that the direct path and row 2's recipe step run
     * the same analysis and the same edit. See the class javadoc.</p>
     */
    public Prepared prepare(IJdtService service, IMethod setter) throws Exception {
        IType declaring = setter.getDeclaringType();
        ICompilationUnit unit = setter.getCompilationUnit();
        if (declaring == null || unit == null) {
            return Prepared.refused(ToolResponse.symbolNotFound(
                "'" + setter.getElementName() + "' has no source declaring type here."));
        }
        CompilationUnit ast = parse(unit);
        MethodDeclaration declaration = declarationOf(ast, setter);
        AbstractTypeDeclaration owner = declaration == null ? null : ownerOf(declaration);
        if (declaration == null || declaration.getBody() == null || owner == null) {
            return Prepared.refused(ToolResponse.symbolNotFound("could not locate the body of "
                + setter.getElementName() + " in its own source."));
        }

        IVariableBinding field = assignedField(declaration);
        if (field == null) {
            return Prepared.refused(ToolResponse.invalidParameter("position",
                "'" + setter.getElementName() + "' is not a setting method: its body is not a"
                    + " single assignment of its one parameter to a field of this class."
                    + " Remove Setting Method takes away a plain setter; a method that does"
                    + " anything else is doing something this operation cannot reason about."));
        }
        if (Modifier.isStatic(field.getModifiers())) {
            return Prepared.refused(ToolResponse.invalidParameter("position",
                "'" + field.getName() + "' is STATIC. A static field settled at class"
                    + " initialization is global_data's subject rather than this one, and its"
                    + " setter's callers are not constructors of anything."));
        }
        String overrideRefusal = overrideRefusal(service, setter, declaring);
        if (overrideRefusal != null) {
            return Prepared.refused(
                ToolResponse.invalidParameter("position", overrideRefusal));
        }

        // THE PRECONDITION. Every reference, enumerated — a caller outside this class's own
        // constructors is asking to change the value after construction.
        List<SearchMatch> references = service.getSearchService()
            .findAllReferences(setter, MAX_REFERENCES);
        if (references.size() >= MAX_REFERENCES) {
            return Prepared.refused(ToolResponse.invalidParameter("position",
                "'" + setter.getElementName() + "' has at least " + MAX_REFERENCES
                    + " references, which is the search cap — so the list is a SAMPLE and the"
                    + " precondition is a statement about every caller. It refuses rather than"
                    + " deciding from a capped list."));
        }
        List<String> outsiders = new ArrayList<>();
        int inConstructors = 0;
        for (SearchMatch match : references) {
            if (match.getElement() instanceof IMethod caller && caller.isConstructor()
                    && declaring.equals(caller.getDeclaringType())) {
                inConstructors++;
            } else {
                outsiders.add(describe(match));
            }
        }
        if (!outsiders.isEmpty()) {
            return Prepared.refused(ToolResponse.invalidParameter("position",
                "'" + setter.getElementName() + "' is called from outside "
                    + declaring.getElementName() + "'s own constructors, so removing it would"
                    + " take away a change those callers make on purpose: " + outsiders
                    + ". Route each of them through a constructor first — that is a decision"
                    + " about their code, not about this class."));
        }

        // The constructor calls this row rewrites, matched against what the search found: a
        // reference inside a constructor that is NOT a plain call statement (a method
        // reference, say) cannot be rewritten, and leaving it would break the build.
        List<ExpressionStatement> calls = constructorCalls(owner, declaration.resolveBinding());
        if (calls.size() != inConstructors) {
            return Prepared.refused(ToolResponse.invalidParameter("position",
                "a constructor mentions '" + setter.getElementName() + "' in a form this"
                    + " operation cannot rewrite (found " + inConstructors + " reference(s)"
                    + " and " + calls.size() + " plain call statement(s)) — a method reference"
                    + " such as this::" + setter.getElementName() + " is the usual cause."));
        }

        FieldDeclaration fieldDeclaration = fieldNamed(owner, field.getName());
        if (fieldDeclaration == null) {
            return Prepared.refused(ToolResponse.symbolNotFound(
                "could not locate the declaration of '" + field.getName() + "' in "
                    + declaring.getElementName() + "."));
        }

        String fieldName = field.getName();
        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());

        // Each constructor's `setX(v);` becomes `this.x = v;`. Written as `this.` always,
        // because a constructor parameter of the field's own name is the commonest shape and
        // the qualified form is correct whether or not it shadows.
        for (ExpressionStatement statement : calls) {
            MethodInvocation invocation = (MethodInvocation) statement.getExpression();
            Object argument = invocation.arguments().get(0);
            rewrite.replace(statement, rewrite.createStringPlaceholder(
                "this." + fieldName + " = " + argument + ";", ASTNode.EXPRESSION_STATEMENT),
                null);
        }

        // The setter itself.
        rewrite.remove(declaration, null);

        String finalNote = finalRefusal(service, owner, declaring, field, fieldDeclaration,
            declaration, calls);
        if (finalNote == null) {
            ListRewrite modifiers = rewrite.getListRewrite(fieldDeclaration,
                FieldDeclaration.MODIFIERS2_PROPERTY);
            modifiers.insertLast(ast.getAST().newModifier(
                Modifier.ModifierKeyword.FINAL_KEYWORD), null);
        }

        List<TextEdit> edits = new ArrayList<>();
        edits.add(rewrite.rewriteAST(new Document(unit.getSource()),
            FormatterOptions.forGeneratedCode(ast)));
        Map<IFile, List<TextEdit>> byFile = new LinkedHashMap<>();
        byFile.put((IFile) unit.getResource(), edits);

        String label = "remove setting method " + declaring.getElementName() + "."
            + setter.getElementName() + " (" + calls.size() + " constructor call(s) became a"
            + " direct assignment; " + fieldName + (finalNote == null ? " is now final"
                : " left non-final — " + finalNote) + ")";
        return new Prepared(ChangeEngine.fromFileEdits(label, byFile), label, finalNote, null);
    }

    /**
     * Why {@code final} is NOT safe here, or null when it is.
     *
     * <p>Narrower than the language's definite-assignment rule on purpose — see the class
     * javadoc. The two shapes it accepts are the two that need no flow analysis: every
     * constructor assigns the field once at the top level of its own body, or the declaration
     * initializes it and no constructor writes it.</p>
     *
     * <p><b>It counts the constructor AS THIS CHANGE WILL LEAVE IT, not as it stands.</b> The
     * commonest shape by far is a constructor that calls the setter rather than assigning —
     * that is why the setter is reachable from a constructor at all — and on the tree being
     * read those statements are INVOCATIONS. Counting only assignments would answer "a
     * constructor does not assign it" for exactly the canonical case, and the field would be
     * left non-final on the one shape the row exists to fix. So the calls about to become
     * assignments are counted with them.</p>
     */
    private String finalRefusal(IJdtService service, AbstractTypeDeclaration ownerType,
                                IType declaring,
                                IVariableBinding field, FieldDeclaration fieldDeclaration,
                                MethodDeclaration setter,
                                List<ExpressionStatement> becomingAssignments) throws Exception {
        if (Modifier.isFinal(field.getModifiers())) {
            return "it already is";
        }
        if (fieldDeclaration.fragments().size() != 1) {
            return "its declaration declares more than one field, and final would take them"
                + " all";
        }
        // A write anywhere but this class's constructors (or the setter we are deleting)
        // means the value is not settled at construction, whatever the setter did.
        IMethod setterElement = setterElement(declaring, setter);
        List<SearchMatch> writes = service.getSearchService()
            .findWriteAccesses(declaring.getField(field.getName()), MAX_REFERENCES);
        if (writes.size() >= MAX_REFERENCES) {
            return "it has at least " + MAX_REFERENCES + " writers, which is the search cap,"
                + " so the list is a sample and 'nothing outside writes it' cannot be said";
        }
        for (SearchMatch write : writes) {
            if (write.getElement() instanceof IMethod owner
                    && declaring.equals(owner.getDeclaringType())
                    && (owner.isConstructor() || owner.equals(setterElement))) {
                continue;
            }
            return "it is also written at " + describe(write);
        }

        VariableDeclarationFragment fragment =
            (VariableDeclarationFragment) fieldDeclaration.fragments().get(0);
        List<MethodDeclaration> constructors = constructors(ownerType);
        if (fragment.getInitializer() != null) {
            return constructors.stream().anyMatch(c ->
                    assignsAtTopLevel(c, field) + willAssign(c, becomingAssignments) > 0)
                ? "its declaration initializes it AND a constructor assigns it, so one of the"
                    + " two writes is already dead — decide which before making it final"
                : null;
        }
        if (constructors.isEmpty()) {
            return "nothing assigns it: the class declares no constructor and the field has no"
                + " initializer, so final would not compile";
        }
        for (MethodDeclaration constructor : constructors) {
            int assignments = assignsAtTopLevel(constructor, field)
                + willAssign(constructor, becomingAssignments);
            if (assignments != 1) {
                return assignments == 0
                    ? "a constructor does not assign it"
                    : "a constructor assigns it more than once at the top level";
            }
        }
        return null;
    }

    private static IMethod setterElement(IType declaring, MethodDeclaration setter)
            throws Exception {
        for (IMethod method : declaring.getMethods()) {
            if (method.getElementName().equals(setter.getName().getIdentifier())
                    && method.getNumberOfParameters() == setter.parameters().size()) {
                return method;
            }
        }
        return null;
    }

    /**
     * How many of the setter calls this change rewrites are DIRECT statements of this
     * constructor's body — the assignments it is about to have.
     */
    private static int willAssign(MethodDeclaration constructor,
                                  List<ExpressionStatement> becomingAssignments) {
        Block body = constructor.getBody();
        if (body == null) {
            return 0;
        }
        int found = 0;
        for (ExpressionStatement statement : becomingAssignments) {
            if (body.statements().contains(statement)) {
                found++;
            }
        }
        return found;
    }

    /** How many times this constructor assigns the field as a DIRECT statement of its body. */
    private static int assignsAtTopLevel(MethodDeclaration constructor, IVariableBinding field) {
        Block body = constructor.getBody();
        if (body == null) {
            return 0;
        }
        int found = 0;
        for (Object each : body.statements()) {
            if (each instanceof ExpressionStatement statement
                    && statement.getExpression() instanceof Assignment assignment
                    && assignment.getOperator() == Assignment.Operator.ASSIGN
                    && sameField(assignment.getLeftHandSide(), field)) {
                found++;
            }
        }
        return found;
    }

    /**
     * The field a plain setter assigns, or null when the method is not one.
     *
     * <p>The shape is exact: one parameter, void, and a body of exactly one statement that
     * assigns that parameter — unaltered — to a field of this class. Anything else is a method
     * that happens to write a field, and this operation would be removing behaviour rather
     * than a setter.</p>
     */
    static IVariableBinding assignedField(MethodDeclaration declaration) {
        if (declaration.parameters().size() != 1
                || declaration.getBody() == null
                || declaration.getReturnType2() == null
                || !"void".equals(declaration.getReturnType2().toString())) {
            return null;
        }
        List<?> statements = declaration.getBody().statements();
        if (statements.size() != 1
                || !(statements.get(0) instanceof ExpressionStatement statement)
                || !(statement.getExpression() instanceof Assignment assignment)
                || assignment.getOperator() != Assignment.Operator.ASSIGN) {
            return null;
        }
        SingleVariableDeclaration parameter =
            (SingleVariableDeclaration) declaration.parameters().get(0);
        if (!(assignment.getRightHandSide() instanceof SimpleName value)
                || !value.getIdentifier().equals(parameter.getName().getIdentifier())) {
            return null;
        }
        IVariableBinding binding = fieldOf(assignment.getLeftHandSide());
        return binding != null && binding.isField() ? binding : null;
    }

    private static IVariableBinding fieldOf(Expression expression) {
        return switch (expression) {
            case SimpleName name when name.resolveBinding() instanceof IVariableBinding v -> v;
            case FieldAccess access -> access.resolveFieldBinding();
            case null, default -> null;
        };
    }

    private static boolean sameField(Expression expression, IVariableBinding field) {
        IVariableBinding binding = fieldOf(expression);
        return binding != null && binding.isField()
            && binding.getName().equals(field.getName());
    }

    /** Plain `setX(v);` statements inside THIS type's own constructors. */
    private static List<ExpressionStatement> constructorCalls(AbstractTypeDeclaration owner,
                                                              IMethodBinding setter) {
        List<ExpressionStatement> found = new ArrayList<>();
        if (setter == null) {
            return found;
        }
        for (MethodDeclaration constructor : constructors(owner)) {
            constructor.accept(new ASTVisitor() {
                @Override
                public boolean visit(ExpressionStatement statement) {
                    if (statement.getExpression() instanceof MethodInvocation invocation
                            && invocation.arguments().size() == 1
                            && setter.isEqualTo(invocation.resolveMethodBinding())) {
                        found.add(statement);
                    }
                    return true;
                }
            });
        }
        return found;
    }

    /** This type's own constructors — its direct members, never a nested type's. */
    private static List<MethodDeclaration> constructors(AbstractTypeDeclaration owner) {
        List<MethodDeclaration> found = new ArrayList<>();
        for (Object member : owner.bodyDeclarations()) {
            if (member instanceof MethodDeclaration method && method.isConstructor()) {
                found.add(method);
            }
        }
        return found;
    }

    /**
     * Why an overriding relationship refuses, or null.
     *
     * <p>A setter a subclass overrides cannot be deleted without deleting the override, and a
     * setter that implements a supertype's contract cannot be deleted at all — the class would
     * stop satisfying an interface it declares. Both are real code that would stop compiling,
     * and both are outside what one class's own change can decide.</p>
     */
    private String overrideRefusal(IJdtService service, IMethod setter, IType declaring)
            throws Exception {
        List<SearchMatch> overriders =
            service.getSearchService().findOverridingMethods(setter, MAX_REFERENCES);
        if (!overriders.isEmpty()) {
            return "'" + setter.getElementName() + "' is overridden by a subclass ("
                + describe(overriders.get(0)) + "), so removing it here would leave the"
                + " override implementing nothing.";
        }
        for (IType supertype : service.getSearchService().getAllSupertypes(declaring)) {
            for (IMethod inherited : supertype.getMethods()) {
                if (inherited.getElementName().equals(setter.getElementName())
                        && inherited.getNumberOfParameters() == setter.getNumberOfParameters()) {
                    return "'" + setter.getElementName() + "' overrides "
                        + supertype.getElementName() + "." + inherited.getElementName()
                        + ", so it is part of a contract this class declares rather than a"
                        + " setter this class is free to take away.";
                }
            }
        }
        return null;
    }

    private static String describe(SearchMatch match) {
        IJavaElement element = match.getElement() instanceof IJavaElement e ? e : null;
        String where = element == null ? "?" : element.getElementName();
        IJavaElement type = element == null ? null : element.getAncestor(IJavaElement.TYPE);
        return (type == null ? where : type.getElementName() + "." + where)
            + " (offset " + match.getOffset() + ")";
    }

    /** A field DECLARED BY this type — not one of the same name in a sibling nested class. */
    private static FieldDeclaration fieldNamed(AbstractTypeDeclaration owner, String name) {
        for (Object member : owner.bodyDeclarations()) {
            if (!(member instanceof FieldDeclaration declaration)) {
                continue;
            }
            for (Object fragment : declaration.fragments()) {
                if (fragment instanceof VariableDeclarationFragment declared
                        && declared.getName().getIdentifier().equals(name)) {
                    return declaration;
                }
            }
        }
        return null;
    }

    /**
     * The declaration of THIS method, found by its own source range rather than by its name.
     *
     * <p>Name and arity do not identify a method inside a compilation unit, and the earlier
     * version of this lookup asserted that they did. Two sibling nested classes may each
     * declare {@code setX(int)}; the architect's counter-example compiles. A name search then
     * returns whichever comes first in the file, so the precondition can be checked against one
     * class and the edit built against another — the same substitution-of-a-proxy defect this
     * row's own checkpoint found three times over. The element's range is the method, so there
     * is nothing left to be ambiguous about.</p>
     */
    static MethodDeclaration declarationOf(CompilationUnit ast, IMethod method) throws Exception {
        ISourceRange range = method.getNameRange();
        if (range == null || range.getOffset() < 0) {
            return null;
        }
        ASTNode node = NodeFinder.perform(ast, range.getOffset(), range.getLength());
        while (node != null && !(node instanceof MethodDeclaration)) {
            node = node.getParent();
        }
        return (MethodDeclaration) node;
    }

    /** The type that DECLARES this member — its immediate enclosing type declaration. */
    private static AbstractTypeDeclaration ownerOf(BodyDeclaration member) {
        ASTNode node = member.getParent();
        while (node != null && !(node instanceof AbstractTypeDeclaration)) {
            node = node.getParent();
        }
        return (AbstractTypeDeclaration) node;
    }

    static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
