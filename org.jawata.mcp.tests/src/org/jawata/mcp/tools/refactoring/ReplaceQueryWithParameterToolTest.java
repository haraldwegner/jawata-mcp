package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.api.ReplaceQueryWithParameterTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 4, row 55 — Replace Query with Parameter, as
 * {@code change_method_signature kind=replace_query_with_parameter}.
 *
 * <p>The row wraps JDT's own engine, so what this file establishes is not that the rewrite
 * works — that is the engine's business — but that the OPERATION is what it claims: the query
 * leaves the body, the signature gains a parameter, and the caller in ANOTHER FILE evaluates
 * the query itself and passes the answer. The last of those is the half a single-file fixture
 * cannot show, and it is why the fixture has a second file nobody points at.</p>
 */
class ReplaceQueryWithParameterToolTest {

    /**
     * The WHOLE statement the row removes from the body. A whole statement is the needle
     * because no comment in the fixture contains one, which is the rule this sprint earned
     * when a cross-file assertion passed on a javadoc line quoting its own output.
     */
    private static final String ASKS = "int wanted = QueryParameterTargets.defaultTemperature();";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ChangeMethodSignatureTool tool;
    private org.jawata.mcp.tools.RefactoringTool lifecycle;
    private Path targets;
    private Path desk;
    private Path pkg;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        org.jawata.mcp.refactoring.RefactoringChangeCache cache =
            new org.jawata.mcp.refactoring.RefactoringChangeCache();
        tool = new ChangeMethodSignatureTool(() -> service, cache);
        lifecycle = new org.jawata.mcp.tools.RefactoringTool(() -> service, cache);
        pkg = service.getProjectRoot().resolve("src/main/java/com/example");
        targets = pkg.resolve("QueryParameterTargets.java");
        desk = pkg.resolve("QueryParameterDesk.java");
    }

    /** Drive the front door at the line declaring this method. */
    private ObjectNode argsAt(String declaration, String methodName) throws Exception {
        String[] lines = Files.readString(targets, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(declaration)) {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "replace_query_with_parameter");
                args.put("filePath", targets.toString());
                args.put("line", i);
                args.put("column", lines[i].indexOf(methodName));
                return args;
            }
        }
        throw new AssertionError("the fixture no longer declares: " + declaration);
    }

    @Test
    @DisplayName("the query leaves the body, the signature gains a parameter, and the caller "
        + "in another file evaluates the query itself")
    void handsTheAnswerInAsAParameter() throws Exception {
        ObjectNode args = argsAt("public int heatingPlan(", "heatingPlan");
        args.put("queryCall", "defaultTemperature");
        args.put("parameterName", "wantedTemperature");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        // EVERY assertion runs even after one fails, for the reason row 21's fork slice
        // records: JUnit stops at the first failure, so a mutation aimed at the last claim is
        // silently caught by the first and reads as verified when nothing exercised it.
        org.junit.jupiter.api.Assertions.assertAll(

            () -> {
                String after = Files.readString(targets, StandardCharsets.UTF_8);
                assertTrue(after.contains("heatingPlan(int outsideTemp, int wantedTemperature)")
                        || after.contains("heatingPlan(int wantedTemperature, int outsideTemp)"),
                    "the method must take the answer as a parameter, under the name ASKED FOR"
                        + " — which is also this row's control on a JDT ordering trap, since a"
                        + " name set at the wrong moment is silently replaced by JDT's own"
                        + " guess:\n" + after);
            },

            () -> {
                String after = Files.readString(targets, StandardCharsets.UTF_8);
                assertFalse(after.contains(ASKS),
                    "and the body must no longer ASK — the question moved to the caller. The"
                        + " needle is the WHOLE statement, which no comment in the fixture"
                        + " contains:\n" + after);
            },

            // THE CROSS-FILE HALF. Nothing pointed the tool at this file. The needle is the
            // WHOLE call, so it pins three things at once: the original argument survived, the
            // answer is passed beside it, and the query kept the qualifier that makes it
            // resolvable HERE — the precondition two earlier versions of this fixture failed.
            () -> {
                String other = Files.readString(desk, StandardCharsets.UTF_8);
                assertTrue(other.contains(
                        "targets.heatingPlan(7, QueryParameterTargets.defaultTemperature());"),
                    "the caller in another file must evaluate the query itself:\n" + other);
            });
    }

    @Test
    @DisplayName("REFUSES a query name the method never calls")
    void refusesAQueryThatIsNotThere() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ObjectNode args = argsAt("public int plainArithmetic(", "plainArithmetic");
        args.put("queryCall", "defaultTemperature");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "there is no such call to replace");
        assertEquals(ReplaceQueryWithParameterTool.Refusal.QUERY_CALL_NOT_FOUND,
            r.getError().getReason(),
            "the refusal must be the not-found PRECONDITION: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("REFUSES when the query is called twice — which one is the caller's choice")
    void refusesAnAmbiguousQuery() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ObjectNode args = argsAt("public int twoQuestions(", "twoQuestions");
        args.put("queryCall", "defaultTemperature");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "two calls, and nothing in the request says which");
        assertEquals(ReplaceQueryWithParameterTool.Refusal.AMBIGUOUS_QUERY_CALL,
            r.getError().getReason(),
            "the refusal must be the ambiguity PRECONDITION: " + r.getError());
        assertTrue(String.valueOf(r.getError()).contains("2 times"),
            "and it must report HOW MANY it found, so the caller can see what it saw: "
                + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("REFUSES a call written with no receiver — its text means something else at "
        + "a call site")
    void refusesAnImplicitReceiver() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ObjectNode args = argsAt("public int asksItself(", "asksItself");
        args.put("queryCall", "localReading");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(),
            "`localReading()` is `this.localReading()`, and the engine COPIES the text to every"
                + " call site, where `this` is somebody else");
        assertEquals(ReplaceQueryWithParameterTool.Refusal.QUERY_NOT_SELF_CONTAINED,
            r.getError().getReason(),
            "the refusal must be the self-containment PRECONDITION. MEASURED, and it is the"
                + " reason this precondition exists rather than being left to the gate:"
                + " with the check disabled the operation SUCCEEDS here, because asksItself has"
                + " no caller outside its own file — so there is no call site to break and"
                + " nothing for the compile gate to see. It would ship a method taking a"
                + " parameter nobody passes: " + r.getError());
        assertFalse(String.valueOf(r.getError()).contains("BROKE_COMPILE"),
            "and it must fire BEFORE the change is built, not after it is undone: "
                + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("REFUSES without queryCall, because the target is addressed by NAME")
    void refusesWithoutAQueryName() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = tool.execute(argsAt("public int heatingPlan(", "heatingPlan"));

        assertFalse(r.isSuccess(), "a method alone does not name an expression");
        assertEquals(ReplaceQueryWithParameterTool.Refusal.QUERY_CALL_REQUIRED,
            r.getError().getReason(),
            "the refusal must be the name PRECONDITION: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("runs from the method's SYMBOL NAME, with no file position given")
    void runsFromItsSymbolName() throws Exception {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "replace_query_with_parameter");
        args.put("symbol", "com.example.QueryParameterTargets#heatingPlan");
        args.put("queryCall", "defaultTemperature");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertFalse(Files.readString(targets, StandardCharsets.UTF_8)
                .contains(ASKS),
            "a finding names a symbol, not a caret — the name form is how the row becomes"
                + " callable straight from one");
    }

    @Test
    @DisplayName("parameterName has a DEFAULT, unlike introduce_parameter_object's className")
    void derivesAParameterNameWhenNoneIsGiven() throws Exception {
        ObjectNode args = argsAt("public int heatingPlan(", "heatingPlan");
        args.put("queryCall", "defaultTemperature");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertFalse(Files.readString(targets, StandardCharsets.UTF_8)
                .contains(ASKS),
            "the row must perform with no name supplied — the value already has a name at the"
                + " call it replaces, which is why this row differs from row 21");
    }

    @Test
    @DisplayName("auto_apply=false STAGES: the file is untouched until the change is applied")
    void stagesTheChangeAndAppliesItOnDemand() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ObjectNode args = argsAt("public int heatingPlan(", "heatingPlan");
        args.put("queryCall", "defaultTemperature");
        args.put("auto_apply", false);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "STAGING must not write: the whole point is a diff a caller reviews first");

        Object data = r.getData();
        assertTrue(data instanceof java.util.Map, "expected a data map, got: " + data);
        Object changeId = ((java.util.Map<?, ?>) data).get("changeId");
        assertNotNull(changeId, "the response carries no changeId: " + data);

        ObjectNode apply = new ObjectMapper().createObjectNode();
        apply.put("action", "apply");
        apply.put("changeId", String.valueOf(changeId));
        ToolResponse applied = lifecycle.execute(apply);
        assertTrue(applied.isSuccess(), "got: " + applied.getError());
        assertFalse(Files.readString(targets, StandardCharsets.UTF_8)
                .contains(ASKS),
            "and the staged change must be the real one");
    }

    @Test
    @DisplayName("the undo handle RESTORES both files")
    void undoRestoresEverything() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        String beforeDesk = Files.readString(desk, StandardCharsets.UTF_8);
        ObjectNode args = argsAt("public int heatingPlan(", "heatingPlan");
        args.put("queryCall", "defaultTemperature");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertFalse(before.equals(Files.readString(targets, StandardCharsets.UTF_8)),
            "the CONTROL: with nothing changed, an undo that restores nothing would pass");

        Object data = r.getData();
        assertTrue(data instanceof java.util.Map, "expected a data map, got: " + data);
        Object handle = ((java.util.Map<?, ?>) data).get("undoChangeId");
        assertNotNull(handle, "the response carries no undoChangeId: " + data);

        ObjectNode undo = new ObjectMapper().createObjectNode();
        undo.put("action", "undo");
        undo.put("undoChangeId", String.valueOf(handle));
        ToolResponse undone = lifecycle.execute(undo);
        assertTrue(undone.isSuccess(), "got: " + undone.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "the method's own file is restored");
        assertEquals(beforeDesk, Files.readString(desk, StandardCharsets.UTF_8),
            "and so is the caller's — a per-file undo that missed one would leave a call"
                + " passing an argument the method no longer takes");
    }

    @Test
    @DisplayName("the door routes the kind")
    void theDoorRoutesIt() {
        assertTrue(tool.publishedKinds().contains("replace_query_with_parameter"),
            "a kind that is dispatched and not published is invisible in tools/list");
        assertTrue(tool.delegates().get("replace_query_with_parameter")
                instanceof ReplaceQueryWithParameterTool,
            "and the routing table must reach this delegate");
    }
}
