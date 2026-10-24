package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.change.ChangeEngine;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 19 (Kerievsky) — <b>Replace State-Altering Conditionals with State</b>
 * (toward pattern). A method that switches on an {@code int} type-code state field
 * becomes a delegation to a State object. Generated as <b>nested classes</b> of the
 * context — a {@code <Context>State} interface plus one inner class per case — so
 * the moved case bodies keep direct access to the context's members (no
 * back-reference plumbing, no visibility breakage).
 *
 * <p>Deliberately conservative so the result always compiles; it refuses anything
 * outside the safe envelope: the switch selector must be a private {@code int}
 * field used only as the selector, its initializer, and {@code field = LABEL}
 * transitions; every case label a {@code static final int} constant of the context;
 * each case body ends in {@code break}/{@code return} with no fall-through, no
 * in-body state assignment, and no {@code this}; the switch is its method's sole
 * statement. Applies as one {@link ChangeEngine#fromFileEdits} change (single file,
 * one undo). Find candidates via {@code find_quality_issue(kind=switch_statements)}.</p>
 */
public class RefactorToStateTool extends AbstractApplyingRefactoringTool
        implements ToolKindDelegate {

    /** Reached as {@code refactor_to_pattern kind=refactor_to_state}. */
    @Override
    public String kindName() {
        return "refactor_to_state";
    }

    /** The bullet a client reads under {@code refactor_to_pattern} — from the door (M5). */
    @Override
    public String kindSummary() {
        return """
            TOWARD: a method that branches on state becomes delegation to
            nested State classes (a <Context>State interface + one inner
            class per state). TWO SHAPES.
            A switch on an int state field: the labels name the states, the
            field is retyped and every `field = LABEL` transition is
            rewritten to construct the right state.
            An IF-CHAIN testing the NULLITY of the context's own fields —
            which is what a class that never had a state field looks like,
            and the shape real code has. There the state is COMPUTED by a
            generated state() evaluating the chain's own conditions in the
            chain's own order, so nothing outside the method is rewritten:
            no field to retype, no transition site to find or miss. It needs
            `states`, one name per branch, because nothing in the code names
            them.
            Needs: line, column on/inside the switch or the chain.
            Conservative — refuses unsafe shapes (see errors).
            (find_quality_issue kind=switch_statements locates the switch
            shape; nothing yet reports the nullable-field one.)""";
    }


    public RefactorToStateTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "refactor_to_state";
    }

    @Override
    public String getDescription() {
        return "Replace State-Altering Conditionals with State — a switch on an int state field becomes "
            + "delegation to nested State classes. Conservative (refuses unsafe shapes). Delegate of "
            + "refactor_to_pattern.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "filePath", Map.of("type", "string", "description", "Source file."),
            "line", Map.of("type", "integer",
                "description", "Zero-based line on/inside the switch statement, or on/inside "
                    + "the if-chain for the nullable-field shape."),
            "column", Map.of("type", "integer",
                "description", "Zero-based column on/inside the switch or the if-chain."),
            "states", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "THE NULLABLE-FIELD SHAPE ONLY, and required there: one state "
                    + "NAME per branch in source order, plus one for the final else if there "
                    + "is one. Unlike the switch shape there is no constant to read a label "
                    + "off — the states are implicit in which fields are null — so saying what "
                    + "each one MEANS is the content of the change and has no default. Ignored "
                    + "on a switch, whose labels name its states already.")));
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath", "Required"));
        }
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (line < 0 || column < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("line/column", "Must be >= 0"));
        }

        Path path = Path.of(filePath);
        ICompilationUnit cu = service.getCompilationUnit(path);
        if (cu == null) {
            return Preparation.fail(ToolResponse.fileNotFound(filePath));
        }
        String source = cu.getSource();
        CompilationUnit ast = parse(cu);
        int offset = ast.getPosition(line + 1, column);
        if (offset < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("position", "Invalid position"));
        }
        ASTNode covering = new NodeFinder(ast, offset, 0).getCoveringNode();
        SwitchStatement sw = enclosing(covering, SwitchStatement.class);
        if (sw == null) {
            // Sprint 28g S5 / D9 change 4 — THE SHAPE REAL CODE HAS. Measured on the sprint's
            // own case before a line of this was written: `execution.Slot` declares NO int
            // state field and no state enum at all. Its state lives across nullable fields —
            // position, pendingEntry, activeOrder, algo, tpOrder, tpOrderCancelling,
            // heldPlacement, freedByStop, flattenTerms — read through predicates like isFree(),
            // hasPosition() and isExitInProgress(). So the switch path above could never reach
            // it, and asking for a switch is asking for a shape the code does not have.
            IfStatement chain = enclosing(covering, IfStatement.class);
            if (chain != null) {
                return prepareFromNullability(service, arguments, cu, ast, source, chain);
            }
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "No switch statement and no if-chain at position. This performs two shapes: a "
                    + "switch on an int state field, and an if-else chain whose branches test "
                    + "the NULLITY of the context's own fields — which is what code that never "
                    + "had a state field looks like."));
        }
        MethodDeclaration method = enclosing(sw, MethodDeclaration.class);
        TypeDeclaration context = enclosing(sw, TypeDeclaration.class);
        if (method == null || context == null) {
            return Preparation.fail(ToolResponse.invalidParameter("selection", "Switch must be inside a method of a class"));
        }
        // The switch must be the method's sole statement (clean delegation).
        if (method.getBody() == null || method.getBody().statements().size() != 1
            || method.getBody().statements().get(0) != sw) {
            return Preparation.fail(ToolResponse.invalidParameter("method",
                "refactor_to_state (v1.4) requires the switch to be the method's only statement."));
        }
        // Selector must be a private int field of the context.
        if (!(sw.getExpression() instanceof SimpleName selector)
            || !(selector.resolveBinding() instanceof IVariableBinding fieldBinding)
            || !fieldBinding.isField() || !Modifier.isPrivate(fieldBinding.getModifiers())
            || !"int".equals(fieldBinding.getType().getName())) {
            return Preparation.fail(ToolResponse.invalidParameter("switch",
                "The switch selector must be a private int state field."));
        }
        String fieldName = selector.getIdentifier();

        // Parse the cases.
        List<Case> cases = new ArrayList<>();
        String pending = null;
        boolean pendingDefault = false;
        List<Statement> body = new ArrayList<>();
        boolean started = false;
        for (Object o : sw.statements()) {
            if (o instanceof SwitchCase sc) {
                if (started) {
                    cases.add(new Case(pending, pendingDefault, body));
                }
                started = true;
                body = new ArrayList<>();
                if (sc.isDefault()) {
                    pending = null;
                    pendingDefault = true;
                } else {
                    List<?> exprs = sc.expressions();
                    if (exprs.size() != 1 || !(exprs.get(0) instanceof SimpleName labelName)
                        || !isTypeCodeConstant(labelName, context)) {
                        return Preparation.fail(ToolResponse.invalidParameter("switch",
                            "Each case label must be a single static-final int constant of the class."));
                    }
                    pending = labelName.getIdentifier();
                    pendingDefault = false;
                }
            } else if (o instanceof Statement s) {
                body.add(s);
            }
        }
        if (started) {
            cases.add(new Case(pending, pendingDefault, body));
        }
        if (cases.size() < 2) {
            return Preparation.fail(ToolResponse.invalidParameter("switch", "Need >= 2 cases."));
        }

        // Validate every f-usage is safe (selector / initializer / `f = LABEL`), collect transitions.
        List<Assignment> transitions = new ArrayList<>();
        String usageError = validateFieldUsage(context, fieldBinding, selector, transitions);
        if (usageError != null) {
            return Preparation.fail(ToolResponse.invalidParameter("field", usageError));
        }
        // Field declaration + its initial label.
        VariableDeclarationFragment fragment = findFieldFragment(context, fieldName);
        if (fragment == null || !(fragment.getInitializer() instanceof SimpleName initLabel)
            || !isTypeCodeConstant(initLabel, context)) {
            return Preparation.fail(ToolResponse.invalidParameter("field",
                "The state field must be initialized to one of the case-label constants."));
        }

        // Build case bodies (strip trailing break; refuse fall-through / this / f-assignment inside).
        String contextName = context.getName().getIdentifier();
        String stateType = contextName + "State";
        List<GeneratedState> states = new ArrayList<>();
        for (Case c : cases) {
            String bodyText = caseBodyText(c, source, fieldBinding);
            if (bodyText == null) {
                return Preparation.fail(ToolResponse.invalidParameter("switch",
                    "Each case must end with break/return, no fall-through, no `this`, no state assignment."));
            }
            String className = (c.isDefault ? "Default" : pascal(c.label)) + "State";
            states.add(new GeneratedState(className, bodyText, c.label, c.isDefault));
        }

        // Method signature for the state interface.
        String returnType = method.getReturnType2() != null ? method.getReturnType2().toString() : "void";
        String methodName = method.getName().getIdentifier();
        String paramDecls = paramDecls(method);
        String paramArgs = paramArgs(method);
        boolean isVoid = "void".equals(returnType);

        // ---- edits ----
        List<TextEdit> edits = new ArrayList<>();
        String indent = "    ";
        String memberIndent = indent + indent;
        String bodyIndent = memberIndent + indent;

        // 1. nested interface + inner state classes, inserted before the context's closing brace.
        StringBuilder nested = new StringBuilder("\n");
        nested.append(memberIndent).append("interface ").append(stateType).append(" {\n")
            .append(bodyIndent).append(returnType).append(" ").append(methodName).append("(").append(paramDecls).append(");\n")
            .append(memberIndent).append("}\n");
        for (GeneratedState st : states) {
            nested.append(memberIndent).append("final class ").append(st.className)
                .append(" implements ").append(stateType).append(" {\n")
                .append(bodyIndent).append("public ").append(returnType).append(" ").append(methodName)
                .append("(").append(paramDecls).append(") {\n")
                .append(reindent(st.bodyText, bodyIndent + indent))
                .append("\n").append(bodyIndent).append("}\n")
                .append(memberIndent).append("}\n");
        }
        int insertAt = context.getStartPosition() + context.getLength() - 1; // just before the class '}'
        edits.add(new InsertEdit(insertAt, nested.toString()));

        // 2. field declaration: `private int f = INIT;` -> `private <State> f = new <Init>State();`
        FieldDeclaration fieldDecl = enclosing(fragment, FieldDeclaration.class);
        String initClass = pascal(initLabel.getIdentifier()) + "State";
        String newField = "private " + stateType + " " + fieldName + " = new " + initClass + "();";
        edits.add(new ReplaceEdit(fieldDecl.getStartPosition(), fieldDecl.getLength(), newField));

        // 3. transitions: `f = LABEL` -> `f = new <Label>State()`
        for (Assignment a : transitions) {
            String label = ((SimpleName) a.getRightHandSide()).getIdentifier();
            String repl = fieldName + " = new " + pascal(label) + "State()";
            edits.add(new ReplaceEdit(a.getStartPosition(), a.getLength(), repl));
        }

        // 4. the switch -> delegation
        String delegate = (isVoid ? "" : "return ") + fieldName + "." + methodName + "(" + paramArgs + ");";
        edits.add(new ReplaceEdit(sw.getStartPosition(), sw.getLength(), delegate));

        IFile file = (IFile) cu.getResource();
        Change change = ChangeEngine.fromFileEdits("refactor to state " + contextName, Map.of(file, edits));

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("contextClass", contextName);
        extras.put("stateInterface", stateType);
        extras.put("states", states.stream().map(s -> s.className).toList());
        extras.put("transitionsRewritten", transitions.size());
        String summary = "refactor to state: " + contextName + " -> " + stateType + " with "
            + states.size() + " state classes";
        return Preparation.of(change, summary, extras);
    }

    // ---------- the nullability path (Sprint 28g S5 / D9 change 4) ----------

    /**
     * One branch of the if-chain: the condition's own source text, the statements it guards, and
     * the name the caller gave the state it represents.
     */
    private record NullBranch(String conditionText, List<Statement> body, String stateName) {
    }

    /**
     * THE SHAPE REAL CODE HAS: state spread over nullable fields, branched on with null tests.
     *
     * <p>This differs from the switch path in exactly two places, and everything else — the
     * nested interface, one class per state, the moved bodies, the delegation — is the same
     * generation. First, the states are not labelled: there is no constant set to read, so the
     * CALLER NAMES THEM, one per branch in order, for the reason {@code compose_method} takes a
     * name per section and {@code introduce_parameter_object} requires a class name. Naming the
     * state is the content of the change; a generated {@code State1} would do the mechanics and
     * deliver none of it.</p>
     *
     * <p>Second, and this is the design: with an int field the state can be STORED and assigned
     * at each transition. Here there is no field to store it in and no transition to hook — the
     * state is a FUNCTION of the fields, so it is COMPUTED by a generated {@code state()} that
     * evaluates the same conditions in the same order and returns the matching state object.
     * That makes this path safer than the switch path rather than harder: nothing outside the
     * method is rewritten, so there are no transition sites to find and none to miss.</p>
     *
     * <p>The safety argument is the ORDER. The generated {@code state()} tests the conditions in
     * the chain's own source order, so a reader does not have to believe the branches are
     * mutually exclusive — overlapping conditions resolve exactly as they did before. That is
     * why the refusals below are about what a condition may CONTAIN rather than about how the
     * conditions relate to each other.</p>
     */
    private Preparation prepareFromNullability(IJdtService service, JsonNode arguments,
                                               ICompilationUnit cu, CompilationUnit ast,
                                               String source, IfStatement chain) throws Exception {
        MethodDeclaration method = enclosing(chain, MethodDeclaration.class);
        TypeDeclaration context = enclosing(chain, TypeDeclaration.class);
        if (method == null || context == null) {
            return Preparation.fail(ToolResponse.invalidParameter("selection",
                "The if-chain must be inside a method of a class."));
        }
        // The same clean-delegation rule the switch path applies: the chain is the whole method,
        // so the method can become one delegating call and nothing is left stranded beside it.
        if (method.getBody() == null || method.getBody().statements().size() != 1
                || method.getBody().statements().get(0) != chain) {
            return Preparation.fail(ToolResponse.invalidParameter("method",
                "the if-chain must be the method's only statement, so that the method can "
                    + "become a single delegating call. Extract the chain into its own method "
                    + "first (extract kind=method)."));
        }

        List<String> names = new ArrayList<>();
        if (arguments.has("states") && arguments.get("states").isArray()) {
            arguments.get("states").forEach(n -> names.add(n.asText()));
        }

        // Walk the chain, collecting one branch per `if`/`else if`, plus the final `else`.
        List<NullBranch> branches = new ArrayList<>();
        Statement cursor = chain;
        List<Statement> elseBody = null;
        while (cursor instanceof IfStatement step) {
            String conditionError = conditionRefusal(step.getExpression(), context);
            if (conditionError != null) {
                return Preparation.fail(ToolResponse.invalidParameter("condition", conditionError));
            }
            int index = branches.size();
            branches.add(new NullBranch(
                source.substring(step.getExpression().getStartPosition(),
                    step.getExpression().getStartPosition() + step.getExpression().getLength()),
                statementsOf(step.getThenStatement()),
                index < names.size() ? names.get(index) : null));
            Statement otherwise = step.getElseStatement();
            if (otherwise instanceof IfStatement nested) {
                cursor = nested;
            } else {
                elseBody = otherwise == null ? null : statementsOf(otherwise);
                cursor = null;
            }
        }
        if (branches.size() < 2) {
            return Preparation.fail(ToolResponse.invalidParameter("condition",
                "Need at least two branches testing field nullity; one `if` is a guard, not a "
                    + "state machine."));
        }

        boolean isVoid = method.getReturnType2() == null
            || "void".equals(method.getReturnType2().toString());
        if (elseBody == null && !isVoid) {
            return Preparation.fail(ToolResponse.invalidParameter("method",
                "the chain has no final `else` and the method returns "
                    + method.getReturnType2() + ", so there is no state to delegate to when "
                    + "every condition is false. Add the else branch that case already needs."));
        }

        int expected = branches.size() + (elseBody == null ? 0 : 1);
        if (names.size() != expected) {
            return Preparation.fail(ToolResponse.invalidParameter("states",
                "required: one state NAME per branch, in source order — " + expected
                    + " of them here" + (elseBody == null ? "" : ", the last for the final else")
                    + ", and " + names.size() + " given. There is no constant to read a label "
                    + "off, so the names are the caller's: saying what each state MEANS is the "
                    + "content of this change, and a generated State1 would deliver none of it."));
        }
        if (elseBody != null) {
            branches.add(new NullBranch(null, elseBody, names.get(names.size() - 1)));
        }

        // A body that writes one of the fields the conditions test would change which state it
        // is in halfway through its own method — the switch path refuses an in-body state
        // assignment for the same reason, and this is that rule over a set of fields.
        for (NullBranch branch : branches) {
            String writeError = bodyWriteRefusal(branch, context, branches);
            if (writeError != null) {
                return Preparation.fail(ToolResponse.invalidParameter("body", writeError));
            }
        }

        String contextName = context.getName().getIdentifier();
        String stateType = contextName + "State";
        String returnType = isVoid ? "void" : method.getReturnType2().toString();
        String methodName = method.getName().getIdentifier();
        String paramDecls = paramDecls(method);
        String paramArgs = paramArgs(method);

        String indent = "    ";
        String memberIndent = indent + indent;
        String bodyIndent = memberIndent + indent;

        StringBuilder nested = new StringBuilder("\n");
        nested.append(memberIndent).append("interface ").append(stateType).append(" {\n")
            .append(bodyIndent).append(returnType).append(" ").append(methodName)
            .append("(").append(paramDecls).append(");\n")
            .append(memberIndent).append("}\n");
        for (NullBranch branch : branches) {
            String className = pascal(branch.stateName) + "State";
            nested.append(memberIndent).append("final class ").append(className)
                .append(" implements ").append(stateType).append(" {\n")
                .append(bodyIndent).append("public ").append(returnType).append(" ")
                .append(methodName).append("(").append(paramDecls).append(") {\n")
                .append(reindent(bodyTextOf(branch, source), bodyIndent + indent))
                .append("\n").append(bodyIndent).append("}\n")
                .append(memberIndent).append("}\n");
        }

        // The computed selector. The conditions are emitted in the chain's own order, which is
        // what makes this exactly equivalent to the code it replaces.
        nested.append("\n").append(memberIndent).append("private ").append(stateType)
            .append(" state() {\n");
        for (NullBranch branch : branches) {
            if (branch.conditionText == null) {
                nested.append(bodyIndent).append("return new ").append(pascal(branch.stateName))
                    .append("State();\n");
            } else {
                nested.append(bodyIndent).append("if (").append(branch.conditionText)
                    .append(") {\n")
                    .append(bodyIndent).append(indent).append("return new ")
                    .append(pascal(branch.stateName)).append("State();\n")
                    .append(bodyIndent).append("}\n");
            }
        }
        if (branches.get(branches.size() - 1).conditionText != null) {
            // Void and no final else: every condition false did nothing before, so the fallback
            // state does nothing now. Named for what it is rather than given a caller's name.
            nested.append(bodyIndent).append("return new NoStateState();\n");
        }
        nested.append(memberIndent).append("}\n");
        if (branches.get(branches.size() - 1).conditionText != null) {
            nested.append("\n").append(memberIndent).append("final class NoStateState implements ")
                .append(stateType).append(" {\n")
                .append(bodyIndent).append("public ").append(returnType).append(" ")
                .append(methodName).append("(").append(paramDecls).append(") {\n")
                .append(bodyIndent).append(indent)
                .append("// every condition false: the original chain did nothing here\n")
                .append(bodyIndent).append("}\n")
                .append(memberIndent).append("}\n");
        }

        List<TextEdit> edits = new ArrayList<>();
        edits.add(new InsertEdit(context.getStartPosition() + context.getLength() - 1,
            nested.toString()));
        String delegate = (isVoid ? "" : "return ") + "state()." + methodName
            + "(" + paramArgs + ");";
        edits.add(new ReplaceEdit(chain.getStartPosition(), chain.getLength(), delegate));

        IFile file = (IFile) cu.getResource();
        Change change = ChangeEngine.fromFileEdits(
            "refactor to state " + contextName, Map.of(file, edits));

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("contextClass", contextName);
        extras.put("stateInterface", stateType);
        extras.put("states", branches.stream().map(b -> pascal(b.stateName) + "State").toList());
        extras.put("selector", "computed");
        extras.put("selectorNote", "the state is a FUNCTION of the fields rather than a stored "
            + "value, so nothing outside this method was rewritten: there is no state field to "
            + "replace and no transition site to find. state() evaluates the chain's own "
            + "conditions in the chain's own order.");
        extras.put("transitionsRewritten", 0);
        String summary = "refactor to state: " + contextName + " -> " + stateType + " with "
            + branches.size() + " state classes, selected by a computed state()";
        return Preparation.of(change, summary, extras);
    }

    /** The statements of a branch, whether or not it is a block. */
    private static List<Statement> statementsOf(Statement statement) {
        List<Statement> out = new ArrayList<>();
        if (statement instanceof Block block) {
            for (Object o : block.statements()) {
                out.add((Statement) o);
            }
        } else if (statement != null) {
            out.add(statement);
        }
        return out;
    }

    /** A branch's source text, from its first statement to its last. */
    private static String bodyTextOf(NullBranch branch, String source) {
        if (branch.body.isEmpty()) {
            return "";
        }
        Statement first = branch.body.get(0);
        Statement last = branch.body.get(branch.body.size() - 1);
        int from = first.getStartPosition();
        int to = last.getStartPosition() + last.getLength();
        return source.substring(from, to);
    }

    /**
     * Why this condition cannot carry a state, or null when it can. Only a null test on a field
     * of the context, or an {@code &&}/{@code ||} tree of them, qualifies — because the
     * generated {@code state()} evaluates the condition a second time, and anything that can
     * have a side effect or a different answer on the second evaluation would change behaviour.
     * A method call is refused for exactly the reason row 53 refuses one in a derived
     * expression: purity is not decidable here.
     */
    private static String conditionRefusal(Expression condition, TypeDeclaration context) {
        if (condition instanceof ParenthesizedExpression parens) {
            return conditionRefusal(parens.getExpression(), context);
        }
        if (condition instanceof InfixExpression infix) {
            InfixExpression.Operator op = infix.getOperator();
            if (op == InfixExpression.Operator.CONDITIONAL_AND
                    || op == InfixExpression.Operator.CONDITIONAL_OR) {
                String left = conditionRefusal(infix.getLeftOperand(), context);
                if (left != null) {
                    return left;
                }
                String right = conditionRefusal(infix.getRightOperand(), context);
                if (right != null) {
                    return right;
                }
                for (Object extra : infix.extendedOperands()) {
                    String more = conditionRefusal((Expression) extra, context);
                    if (more != null) {
                        return more;
                    }
                }
                return null;
            }
            if (op == InfixExpression.Operator.EQUALS || op == InfixExpression.Operator.NOT_EQUALS) {
                boolean leftNull = infix.getLeftOperand() instanceof NullLiteral;
                boolean rightNull = infix.getRightOperand() instanceof NullLiteral;
                if (!leftNull && !rightNull) {
                    return "`" + infix + "` compares two values rather than testing a field "
                        + "against null. This performs the nullable-field shape; a comparison "
                        + "encodes state some other way.";
                }
                Expression operand = leftNull ? infix.getRightOperand() : infix.getLeftOperand();
                return fieldRefusal(operand, context);
            }
        }
        return "`" + condition + "` is not a null test. Each branch's condition must be a field "
            + "null test, or an && / || tree of them — the generated state() evaluates it a "
            + "SECOND time to decide which state it is in, so a condition that can have a side "
            + "effect or answer differently would change what the method does.";
    }

    /** Null when this operand is a field of the context; a refusal otherwise. */
    private static String fieldRefusal(Expression operand, TypeDeclaration context) {
        Expression target = operand;
        if (target instanceof FieldAccess access && access.getExpression() instanceof ThisExpression) {
            target = access.getName();
        }
        if (!(target instanceof SimpleName name)
                || !(name.resolveBinding() instanceof IVariableBinding binding)
                || !binding.isField()) {
            return "`" + operand + "` is not a field of " + context.getName()
                + ". A state computed from something other than this object's own fields is not "
                + "this object's state.";
        }
        return null;
    }

    /**
     * Whether a branch body writes a field some condition tests. Such a write changes which
     * state the object is in halfway through the state's own method, which is the same thing the
     * switch path refuses as an in-body state assignment.
     */
    private static String bodyWriteRefusal(NullBranch branch, TypeDeclaration context,
                                           List<NullBranch> all) {
        List<String> tested = new ArrayList<>();
        for (NullBranch other : all) {
            if (other.conditionText != null) {
                for (String word : other.conditionText.split("[^A-Za-z0-9_$]+")) {
                    if (!word.isBlank() && !tested.contains(word)) {
                        tested.add(word);
                    }
                }
            }
        }
        final String[] offender = {null};
        for (Statement statement : branch.body) {
            statement.accept(new ASTVisitor() {
                @Override
                public boolean visit(Assignment assignment) {
                    Expression lhs = assignment.getLeftHandSide();
                    String written = lhs instanceof SimpleName n ? n.getIdentifier()
                        : lhs instanceof FieldAccess f ? f.getName().getIdentifier() : null;
                    if (written != null && tested.contains(written) && offender[0] == null) {
                        offender[0] = written;
                    }
                    return true;
                }
            });
        }
        if (offender[0] != null) {
            return "the `" + branch.stateName + "` branch assigns `" + offender[0]
                + "`, which a condition tests — so the object changes state inside the state's "
                + "own method. Which state it becomes is a transition, and moving a transition "
                + "into the state that is being left is a design decision rather than a "
                + "mechanical move. Split the assignment out first.";
        }
        return null;
    }

    // ---------- helpers ----------

    private record Case(String label, boolean isDefault, List<Statement> body) {
    }

    private record GeneratedState(String className, String bodyText, String label, boolean isDefault) {
    }

    /** Body source minus a trailing break; null if fall-through / `this` / assigns the state field. */
    private static String caseBodyText(Case c, String source, IVariableBinding fieldBinding) {
        List<Statement> stmts = new ArrayList<>(c.body);
        if (stmts.isEmpty()) {
            return null;
        }
        Statement last = stmts.get(stmts.size() - 1);
        boolean endsWithBreak = last instanceof BreakStatement;
        boolean endsWithReturn = last instanceof ReturnStatement;
        if (!endsWithBreak && !endsWithReturn) {
            return null; // fall-through or non-terminating
        }
        if (endsWithBreak) {
            stmts.remove(stmts.size() - 1);
            if (stmts.isEmpty()) {
                return "// (empty)";
            }
        }
        // reject `this` or assignment to the state field inside the body
        boolean[] unsafe = {false};
        for (Statement s : stmts) {
            s.accept(new ASTVisitor() {
                @Override
                public boolean visit(org.eclipse.jdt.core.dom.ThisExpression node) {
                    unsafe[0] = true;
                    return true;
                }

                @Override
                public boolean visit(Assignment node) {
                    if (node.getLeftHandSide() instanceof SimpleName n
                        && n.resolveBinding() instanceof IVariableBinding b && b.isEqualTo(fieldBinding)) {
                        unsafe[0] = true;
                    }
                    return true;
                }
            });
        }
        if (unsafe[0]) {
            return null;
        }
        int start = stmts.get(0).getStartPosition();
        Statement lastKept = stmts.get(stmts.size() - 1);
        int end = lastKept.getStartPosition() + lastKept.getLength();
        return source.substring(start, end);
    }

    /** Ensure the state field is used only as selector / initializer / `f = LABEL`; collect transitions. */
    private static String validateFieldUsage(TypeDeclaration context, IVariableBinding fieldBinding,
                                             SimpleName selector, List<Assignment> transitions) {
        String[] error = {null};
        context.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (error[0] != null) {
                    return false;
                }
                if (!(node.resolveBinding() instanceof IVariableBinding b) || !b.isEqualTo(fieldBinding)) {
                    return true;
                }
                if (node == selector) {
                    return true; // the switch selector
                }
                ASTNode parent = node.getParent();
                // field declaration name
                if (parent instanceof VariableDeclarationFragment vdf && vdf.getName() == node) {
                    return true;
                }
                // `f = LABEL` transition (LABEL a static-final int constant of the context)
                if (parent instanceof Assignment a && a.getLeftHandSide() == node
                    && a.getRightHandSide() instanceof SimpleName rhs && isTypeCodeConstant(rhs, context)) {
                    transitions.add(a);
                    return true;
                }
                error[0] = "state field '" + node.getIdentifier() + "' is used outside the safe set "
                    + "(selector / initializer / `field = LABEL`); refusing to change its type.";
                return false;
            }
        });
        return error[0];
    }

    private static boolean isTypeCodeConstant(SimpleName name, TypeDeclaration context) {
        if (!(name.resolveBinding() instanceof IVariableBinding b) || !b.isField()) {
            return false;
        }
        int mods = b.getModifiers();
        return Modifier.isStatic(mods) && Modifier.isFinal(mods)
            && "int".equals(b.getType().getName())
            && b.getDeclaringClass() != null
            && b.getDeclaringClass().isEqualTo(context.resolveBinding());
    }

    private static VariableDeclarationFragment findFieldFragment(TypeDeclaration context, String fieldName) {
        for (FieldDeclaration f : context.getFields()) {
            for (Object frag : f.fragments()) {
                if (frag instanceof VariableDeclarationFragment vdf
                    && fieldName.equals(vdf.getName().getIdentifier())) {
                    return vdf;
                }
            }
        }
        return null;
    }

    private static String paramDecls(MethodDeclaration m) {
        List<String> parts = new ArrayList<>();
        for (Object p : m.parameters()) {
            SingleVariableDeclaration v = (SingleVariableDeclaration) p;
            parts.add(v.getType().toString() + " " + v.getName().getIdentifier());
        }
        return String.join(", ", parts);
    }

    private static String paramArgs(MethodDeclaration m) {
        List<String> parts = new ArrayList<>();
        for (Object p : m.parameters()) {
            parts.add(((SingleVariableDeclaration) p).getName().getIdentifier());
        }
        return String.join(", ", parts);
    }

    /** Re-indent multi-line body text to the given indent (trimming each line first). */
    private static String reindent(String bodyText, String indent) {
        String[] lines = bodyText.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(indent).append(lines[i].trim());
        }
        return sb.toString();
    }

    private static String pascal(String label) {
        StringBuilder sb = new StringBuilder();
        for (String part : label.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(part.charAt(0)))
                .append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    private static <T extends ASTNode> T enclosing(ASTNode node, Class<T> type) {
        while (node != null && !type.isInstance(node)) {
            node = node.getParent();
        }
        return type.cast(node);
    }

    private static CompilationUnit parse(ICompilationUnit cu) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
