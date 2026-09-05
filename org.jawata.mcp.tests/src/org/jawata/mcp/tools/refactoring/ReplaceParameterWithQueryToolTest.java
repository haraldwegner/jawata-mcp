package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.api.ReplaceParameterWithQueryTool;
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
 * Stage 4, row 53 — Replace Parameter with Query, as
 * {@code change_method_signature kind=replace_parameter_with_query}.
 *
 * <p>The row's whole safety argument is UNANIMITY among the callers, so the fixture carries a
 * method whose callers agree and one whose callers do not, and both are asserted. The second is
 * the more important: a row that inferred the query from the first caller it happened to read
 * would rewrite the disagreeing one into something it never asked for.</p>
 */
class ReplaceParameterWithQueryToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ChangeMethodSignatureTool tool;
    private org.jawata.mcp.tools.RefactoringTool lifecycle;
    private Path targets;
    private Path desk;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        org.jawata.mcp.refactoring.RefactoringChangeCache cache =
            new org.jawata.mcp.refactoring.RefactoringChangeCache();
        tool = new ChangeMethodSignatureTool(() -> service, cache);
        lifecycle = new org.jawata.mcp.tools.RefactoringTool(() -> service, cache);
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example");
        targets = pkg.resolve("DerivedParameterTargets.java");
        desk = pkg.resolve("DerivedParameterDesk.java");
    }

    private ObjectNode argsFor(String method) {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "replace_parameter_with_query");
        args.put("symbol", "com.example.DerivedParameterTargets#" + method);
        args.put("parameter", "rate");
        return args;
    }

    @Test
    @DisplayName("the parameter goes, the body asks for it, and BOTH agreeing callers shorten")
    void replacesTheParameterWithTheQuery() throws Exception {
        ToolResponse r = tool.execute(argsFor("discounted"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        org.junit.jupiter.api.Assertions.assertAll(

            () -> {
                String after = Files.readString(targets, StandardCharsets.UTF_8);
                assertTrue(after.contains("public double discounted(Order order)"),
                    "the signature must lose the derived parameter:\n" + after);
                assertTrue(after.contains("(100 - order.rate())"),
                    "and the body must ask the object for it instead:\n" + after);
            },

            // BOTH call sites, because one is not evidence of a rewrite that must reach every
            // caller — a row that shortened only the first would leave the second uncompilable,
            // and the compile gate would refuse the whole change rather than this assertion
            // catching it. Asserting both is what makes the claim about REACH rather than luck.
            () -> {
                String other = Files.readString(desk, StandardCharsets.UTF_8);
                assertEquals(2, other.split("targets\\.discounted\\(order\\)", -1).length - 1,
                    "both agreeing callers must now pass the order alone:\n" + other);
            });
    }

    @Test
    @DisplayName("REFUSES when one caller derives the parameter differently")
    void refusesWhenCallersDisagree() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = tool.execute(argsFor("contested"));

        assertFalse(r.isSuccess(),
            "one caller passes a literal, which no query on the order could reproduce");
        assertEquals(ReplaceParameterWithQueryTool.Refusal.CALLERS_DISAGREE,
            r.getError().getReason(),
            "the refusal must be the unanimity PRECONDITION: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("REFUSES when two callers each derive it plausibly but DIFFERENTLY — the case "
        + "that reaches the unanimity check at all")
    void refusesWhenCallersDeriveItDifferently() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = tool.execute(argsFor("disputed"));

        assertFalse(r.isSuccess(),
            "one caller passes order.rate() and the other order.bonus(); picking either would"
                + " change what the other asked for");
        assertEquals(ReplaceParameterWithQueryTool.Refusal.CALLERS_DISAGREE,
            r.getError().getReason(),
            "the refusal must be the unanimity PRECONDITION: " + r.getError());
        assertTrue(String.valueOf(r.getError()).contains("rate")
                && String.valueOf(r.getError()).contains("bonus"),
            "and it must NAME both derivations it saw, so the caller can see the conflict"
                + " rather than being told there is one: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("REFUSES when the body reads the parameter twice, because a query put in its "
        + "place would be evaluated twice")
    void refusesARepeatedRead() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = tool.execute(argsFor("compounded"));

        assertFalse(r.isSuccess(), "both callers agree, so the only thing left to refuse on is"
            + " that one evaluation would become two");
        assertEquals(ReplaceParameterWithQueryTool.Refusal.PARAMETER_READ_REPEATEDLY,
            r.getError().getReason(),
            "and it must NOT be the unanimity refusal — the callers agree here: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("REFUSES a single read INSIDE a loop, which the read count alone cannot see")
    void refusesAReadInsideALoop() throws Exception {
        ToolResponse r = tool.execute(argsFor("accrued"));

        assertFalse(r.isSuccess(), "one read in a loop is evaluated once per iteration");
        assertEquals(ReplaceParameterWithQueryTool.Refusal.PARAMETER_READ_REPEATEDLY,
            r.getError().getReason(), "got: " + r.getError());
        assertTrue(String.valueOf(r.getError()).contains("loop"),
            "and the refusal must name the SHAPE it saw rather than the count, which is 1 here"
                + " and would make the message false: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a parameter the method does not declare")
    void refusesAnUnknownParameter() throws Exception {
        ObjectNode args = argsFor("discounted");
        args.put("parameter", "nosuch");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "there is no such parameter to replace");
        assertEquals(ReplaceParameterWithQueryTool.Refusal.PARAMETER_NOT_FOUND,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES without a parameter, because there is no default for which to remove")
    void refusesWithoutAParameter() throws Exception {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "replace_parameter_with_query");
        args.put("symbol", "com.example.DerivedParameterTargets#discounted");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "a method alone does not name a parameter");
        assertEquals(ReplaceParameterWithQueryTool.Refusal.PARAMETER_REQUIRED,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("auto_apply=false STAGES: the file is untouched until the change is applied")
    void stagesTheChangeAndAppliesItOnDemand() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ObjectNode args = argsFor("discounted");
        args.put("auto_apply", false);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "STAGING must not write");

        Object changeId = ((java.util.Map<?, ?>) r.getData()).get("changeId");
        assertNotNull(changeId, "the response carries no changeId: " + r.getData());
        ObjectNode apply = new ObjectMapper().createObjectNode();
        apply.put("action", "apply");
        apply.put("changeId", String.valueOf(changeId));
        assertTrue(lifecycle.execute(apply).isSuccess());
        assertTrue(Files.readString(targets, StandardCharsets.UTF_8)
                .contains("public double discounted(Order order)"),
            "and the staged change must be the real one");
    }

    @Test
    @DisplayName("the undo handle RESTORES both files")
    void undoRestoresEverything() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        String beforeDesk = Files.readString(desk, StandardCharsets.UTF_8);
        ToolResponse r = tool.execute(argsFor("discounted"));
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertFalse(before.equals(Files.readString(targets, StandardCharsets.UTF_8)),
            "the CONTROL: with nothing changed, an undo that restores nothing would pass");

        Object handle = ((java.util.Map<?, ?>) r.getData()).get("undoChangeId");
        assertNotNull(handle, "the response carries no undoChangeId: " + r.getData());
        ObjectNode undo = new ObjectMapper().createObjectNode();
        undo.put("action", "undo");
        undo.put("undoChangeId", String.valueOf(handle));
        assertTrue(lifecycle.execute(undo).isSuccess());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "the method's own file is restored");
        assertEquals(beforeDesk, Files.readString(desk, StandardCharsets.UTF_8),
            "and so are the callers'");
    }

    @Test
    @DisplayName("the door routes the kind")
    void theDoorRoutesIt() {
        assertTrue(tool.publishedKinds().contains("replace_parameter_with_query"),
            "a kind that is dispatched and not published is invisible in tools/list");
        assertTrue(tool.delegates().get("replace_parameter_with_query")
                instanceof ReplaceParameterWithQueryTool,
            "and the routing table must reach this delegate");
    }
}
