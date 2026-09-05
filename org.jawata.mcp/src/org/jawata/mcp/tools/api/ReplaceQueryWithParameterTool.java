package org.jawata.mcp.tools.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.internal.corext.refactoring.code.IntroduceParameterRefactoring;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.CheckedChange;
import org.jawata.mcp.refactoring.JdtRefactoringEngine;
import org.jawata.mcp.refactoring.PreparedRefactoring;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.refactoring.RefactoringEngine;
import org.jawata.mcp.tools.AbstractApplyingRefactoringTool;
import org.jawata.mcp.tools.ToolKindDelegate;
import org.jawata.mcp.tools.shared.FqnTarget;
import org.jawata.mcp.tools.shared.HeadlessJdtConfig;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code change_method_signature kind=replace_query_with_parameter} — Fowler row 55, Replace
 * Query with Parameter.
 *
 * <p>A method that asks somebody else a question in the middle of its own work has a dependency
 * it did not declare: it cannot be called without that collaborator, cannot be tested without
 * standing one up, and gives a different answer when the collaborator's state changes. Handing
 * the ANSWER in as a parameter moves the question to the caller, who already knows the context
 * it should be asked in — and the method becomes a function of its arguments.</p>
 *
 * <h2>WRAPPED, not written — this is JDT's own engine</h2>
 *
 * <p>{@link IntroduceParameterRefactoring} is what the IDE runs behind Refactor → Introduce
 * Parameter. It adds the parameter, replaces the selected expression in the body with it, and
 * rewrites every call site to evaluate that expression and pass it — across files. Stage 1 of
 * this sprint lists this row as a wrap for that reason, and measured: the engine ships in
 * {@code org.eclipse.jdt.core.manipulation}, which this bundle already names in
 * {@code Require-Bundle}, so no packaging change was needed.</p>
 *
 * <h2>The target is NAMED, not selected — and that is the whole addressing decision</h2>
 *
 * <p>The engine takes a character RANGE. This row does not publish one. A range is not
 * something a caller can produce reliably and it is not something a finding carries, and the
 * per-row contract of this sprint is that a row is callable straight from the finding that
 * names it. So the input is a pair of NAMES: the method (by {@code symbol}, or by position),
 * and {@code queryCall} — the simple name of the call inside it. The range is derived.</p>
 *
 * <p>The cost is stated rather than hidden: an expression that is not a method call — a field
 * read, an arithmetic expression — cannot be addressed by this row at all. Fowler's row is
 * about a QUERY, so that is the shape it takes, and a range-addressed general form would be a
 * different operation with a different name.</p>
 *
 * <h2>{@code parameterName} is optional, which is the opposite of row 21's decision</h2>
 *
 * <p>{@link IntroduceParameterObjectTool} REFUSES without a {@code className}, because naming a
 * new class is the refactoring — somebody recognised what a group of arguments is together, and
 * a generated name delivers the mechanics and withholds the point. Here the new name is a
 * PARAMETER's, the value already has a name at the call it replaces, and JDT derives one from
 * that call. So the default carries the meaning instead of losing it, and the two rows differ
 * for a reason rather than by inconsistency.</p>
 *
 * <h2>What it refuses</h2>
 *
 * <ul>
 *   <li><b>No call of that name in the method.</b> Named rather than silently doing nothing.</li>
 *   <li><b>More than one call of that name.</b> Which one becomes the parameter is a choice
 *       only the caller can make, and picking the first would quietly rewrite the wrong line.
 *       The refusal reports how many were found.</li>
 *   <li><b>Anything JDT's own preconditions reject</b> — an expression that is not an r-value,
 *       a type not visible at the call sites. The engine's message is returned as it stands.</li>
 * </ul>
 */
public class ReplaceQueryWithParameterTool extends AbstractApplyingRefactoringTool
        implements ToolKindDelegate {

    private final RefactoringEngine engine = new JdtRefactoringEngine();

    /** WHICH precondition declined — see {@link org.jawata.mcp.models.ErrorInfo}. */
    public static final class Refusal {

        /** The position or name does not resolve to a method. */
        public static final String NOT_A_METHOD = "NOT_A_METHOD";
        /** {@code queryCall} was absent, and the target cannot be derived without it. */
        public static final String QUERY_CALL_REQUIRED = "QUERY_CALL_REQUIRED";
        /** No invocation of that name occurs in the method's body. */
        public static final String QUERY_CALL_NOT_FOUND = "QUERY_CALL_NOT_FOUND";
        /** Several invocations of that name occur; which one is the caller's choice. */
        public static final String AMBIGUOUS_QUERY_CALL = "AMBIGUOUS_QUERY_CALL";
        /** The call has no written receiver, so its text means something else at a call site. */
        public static final String QUERY_NOT_SELF_CONTAINED = "QUERY_NOT_SELF_CONTAINED";

        private Refusal() {
        }
    }

    public ReplaceQueryWithParameterTool(Supplier<IJdtService> serviceSupplier,
                                         RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String kindName() {
        return "replace_query_with_parameter";
    }

    @Override
    public String getName() {
        return "replace_query_with_parameter";
    }

    @Override
    public String getDescription() {
        return """
            Replace Query with Parameter — a method that asks a collaborator a question mid-work
            takes the ANSWER as a parameter instead, so it stops depending on that collaborator
            and every call site evaluates the query itself. Name the METHOD (symbol=pkg.Type#m,
            or a position) and the call inside it (queryCall=selectedTemperature); the range is
            derived, because a finding names symbols and never a character offset. Wraps JDT's
            own engine, so the body and every call site across files are rewritten together.
            Optional: parameterName — unlike introduce_parameter_object's className it has a
            default, because the value already has a name at the call being replaced. Refuses a
            query name that occurs zero times, or more than once.""";
    }

    /** Structural: the signature changes and every call site with it. */
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
            "description", "Source file declaring the method."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the method's declaration."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column on that line."));
        properties.put("symbol", FqnTarget.symbolSchemaProperty("method that asks the query"));
        properties.put("queryCall", Map.of("type", "string",
            "description", "REQUIRED. Simple name of the call inside the method that becomes a "
                + "parameter, e.g. 'selectedTemperature'. Refused if it occurs zero times or "
                + "more than once."));
        properties.put("parameterName", Map.of("type", "string",
            "description", "Name for the new parameter (optional; JDT derives one from the "
                + "call being replaced)."));
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

        String queryCall = getStringParam(arguments, "queryCall");
        if (queryCall == null || queryCall.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("queryCall",
                "queryCall is required: it names the call inside the method that becomes a"
                    + " parameter. This row addresses its target by NAME rather than by a"
                    + " character range, so that a finding can drive it.",
                Refusal.QUERY_CALL_REQUIRED));
        }

        String filePath = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath",
                "filePath is required (or name the method with symbol=pkg.Type#method)"));
        }

        IJavaElement element = service.getElementAtPosition(Path.of(filePath), line, column);
        if (!(element instanceof IMethod method)) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the position does not resolve to a method.", Refusal.NOT_A_METHOD));
        }

        ICompilationUnit unit = method.getCompilationUnit();
        List<MethodInvocation> calls = callsNamed(parse(unit), method, queryCall);
        if (calls.isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("queryCall",
                "'" + method.getElementName() + "' contains no call to '" + queryCall + "'.",
                Refusal.QUERY_CALL_NOT_FOUND));
        }
        if (calls.size() > 1) {
            return Preparation.fail(ToolResponse.invalidParameter("queryCall",
                "'" + method.getElementName() + "' calls '" + queryCall + "' " + calls.size()
                    + " times, and WHICH of them becomes the parameter is a choice only you can"
                    + " make. Taking the first would rewrite a line you did not name.",
                Refusal.AMBIGUOUS_QUERY_CALL));
        }
        MethodInvocation target = calls.get(0);

        // THE ENGINE COPIES THE EXPRESSION'S TEXT TO EVERY CALL SITE, so the text has to mean
        // the same thing there. A call with NO written receiver does not: `localReading()` is
        // an implicit `this`, and pasted into a caller it rebinds to whoever holds the call —
        // which is either a compile error or, worse, a different method of the same name.
        // Without this the compile gate catches it and undoes the change, correctly but under
        // a name that says only that something did not compile.
        if (target.getExpression() == null) {
            return Preparation.fail(ToolResponse.invalidParameter("queryCall",
                "'" + queryCall + "' is called with no receiver written, so it reads as"
                    + " `this." + queryCall + "()`. This row moves the CALL to the call sites,"
                    + " where `this` is somebody else — write the receiver the callers can also"
                    + " name (a class for a static query, or a reachable object).",
                Refusal.QUERY_NOT_SELF_CONTAINED));
        }

        HeadlessJdtConfig.ensureInitialized();

        IntroduceParameterRefactoring refactoring = new IntroduceParameterRefactoring(
            unit, target.getStartPosition(), target.getLength());

        // THE ORDER IS LOAD-BEARING, AND IT COST TWO RUNS TO GET RIGHT. setParameterName writes
        // through to a ParameterInfo that JDT does not build until checkInitialConditions has
        // run — calling it before throws NullPointerException out of the engine — and
        // checkInitialConditions REBUILDS it, so a name set before the last condition check is
        // silently discarded and JDT's guess ships instead. Stage 6 row 49 recorded the same
        // shape on a different engine, which is what makes it a trap rather than an accident.
        //
        // RefactoringEngine.propose runs checkAllConditions, which is both checks, so there is
        // no window inside it. The window is opened here instead: run the conditions, name the
        // parameter, build the change, and hand the BUILT change to the pipeline through
        // PreparedRefactoring — the seam Stage 6 promoted for exactly this, so the compile
        // gate, the parity check and the undo handle are unchanged.
        RefactoringStatus conditions =
            refactoring.checkInitialConditions(new NullProgressMonitor());
        String requestedName = getStringParam(arguments, "parameterName");
        if (!conditions.hasFatalError() && requestedName != null && !requestedName.isBlank()) {
            refactoring.setParameterName(requestedName);
        }
        if (!conditions.hasFatalError()) {
            conditions.merge(refactoring.checkFinalConditions(new NullProgressMonitor()));
        }
        if (conditions.hasFatalError()) {
            return Preparation.fail(ToolResponse.error("REFACTORING_FAILED",
                "replace_query_with_parameter refused: " + conditions.getMessageMatchingSeverity(
                    RefactoringStatus.FATAL),
                "JDT's own preconditions declined — the selected call is not an r-value, or its"
                    + " type is not visible at every call site. Nothing was modified."));
        }
        CheckedChange checked = engine.propose(
            new PreparedRefactoring(refactoring.createChange(new NullProgressMonitor()),
                "replace query " + queryCall + "()"),
            "replace query " + queryCall + "() with a parameter of " + method.getElementName());
        if (checked.isRefused()) {
            return Preparation.fail(ToolResponse.error("REFACTORING_FAILED",
                "replace_query_with_parameter refused: " + checked.messages(),
                "JDT's own preconditions declined — the selected call is not an r-value, or its"
                    + " type is not visible at every call site. Nothing was modified."));
        }

        Change change = checked.change();
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("method", method.getElementName());
        extras.put("queryCall", queryCall);
        extras.put("parameterName", refactoring.getAddedParameterInfo() == null
            ? requestedName : refactoring.getAddedParameterInfo().getNewName());
        extras.put("filesAffected", ChangeEngine.affectedFilePaths(change, service).size());
        if (checked.hasWarnings()) {
            extras.put("warnings", checked.messages());
        }

        String summary = "replace query " + queryCall + "() with a parameter of "
            + method.getElementName();
        return Preparation.of(change, summary, extras);
    }

    /**
     * Every invocation of {@code name} lexically inside {@code method}'s own body.
     *
     * <p>The method is located by its ELEMENT's own source range rather than by searching the
     * file for its name — the identity join this sprint adopted after a name key resolved to a
     * sibling class declaring the same member. Nested lambdas and anonymous classes are NOT
     * excluded: a query asked inside a lambda is still a query this method asks, and JDT
     * decides for itself whether the expression can be lifted.</p>
     */
    private static List<MethodInvocation> callsNamed(CompilationUnit ast, IMethod method,
                                                     String name) throws Exception {
        List<MethodInvocation> found = new ArrayList<>();
        ISourceRange range = method.getSourceRange();
        if (range == null || range.getOffset() < 0) {
            return found;
        }
        ASTNode declaration = NodeFinder.perform(ast, range.getOffset(), range.getLength());
        if (declaration == null) {
            return found;
        }
        declaration.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                if (name.equals(node.getName().getIdentifier())) {
                    found.add(node);
                }
                return true;
            }
        });
        return found;
    }

    private static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
