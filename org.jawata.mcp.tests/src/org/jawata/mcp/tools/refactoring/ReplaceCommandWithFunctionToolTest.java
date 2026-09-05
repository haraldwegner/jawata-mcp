package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.api.ReplaceCommandWithFunctionTool;
import org.junit.jupiter.api.Assertions;
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
 * Stage 4, row 41 — Replace Command with Function, as
 * {@code change_method_signature kind=replace_command_with_function}.
 *
 * <p>The fork census set this row's priorities before it was written: of 33 command-shaped classes
 * in the corpus, 27 declare their method because a SUPERTYPE does. So the refusals carry more
 * weight here than the success, and the dispatch one carries the most.</p>
 */
class ReplaceCommandWithFunctionToolTest {

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
        targets = pkg.resolve("CommandObjectTargets.java");
        desk = pkg.resolve("CommandObjectDesk.java");
    }

    private ObjectNode argsFor(String nested) {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "replace_command_with_function");
        args.put("typeName", "com.example.CommandObjectTargets." + nested);
        return args;
    }

    @Test
    @DisplayName("the fields become parameters, the constructor goes, and BOTH uses become one "
        + "static call")
    void turnsTheCommandIntoAFunction() throws Exception {
        ToolResponse r = tool.execute(argsFor("Discount"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        String uses = Files.readString(desk, StandardCharsets.UTF_8);
        Assertions.assertAll(
            () -> assertTrue(after.contains(
                    "public static double execute(double base, int rate, int rounding)"),
                "the constructor's parameters must come FIRST and the method's own after, in"
                    + " that order, on a method that is now static:\n" + after),
            () -> assertFalse(after.contains("public Discount(double base, int rate)"),
                "the constructor must be gone — it has nothing left to settle:\n" + after),
            () -> assertFalse(after.contains("private final double base;"),
                "and so must the fields it settled:\n" + after),
            () -> assertTrue(after.contains("(base * (100 - rate) / 100.0)"),
                "while the body reads the PARAMETERS where it read the fields, unchanged in"
                    + " every other respect:\n" + after),

            () -> assertTrue(uses.contains("CommandObjectTargets.Discount.execute(base, 10, 100)"),
                "the first use must become one static call carrying both argument lists:\n"
                    + uses),
            () -> assertTrue(uses.contains("CommandObjectTargets.Discount.execute(base, 25, 10)"),
                "and so must the SECOND — one rewritten use is not evidence of a change that"
                    + " must reach every one of them:\n" + uses),
            () -> assertFalse(uses.contains("new CommandObjectTargets.Discount("),
                "no use may be left constructing a command with no constructor:\n" + uses));
    }

    @Test
    @DisplayName("REFUSES when a SUPERTYPE declares the method — the corpus's dominant case, "
        + "27 of its 33 candidates")
    void refusesADispatchedCommand() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = tool.execute(argsFor("Loud"));

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(), "the caller chose this implementation by"
                + " constructing it, and a static function has no dispatch"),
            () -> assertEquals(ReplaceCommandWithFunctionTool.Refusal.METHOD_IS_INHERITED,
                r.getError().getReason(), "got: " + r.getError()),
            () -> assertTrue(String.valueOf(r.getError()).contains("Speaker"),
                "and it must NAME the supertype, so the caller can see what they would be"
                    + " collapsing: " + r.getError()),
            () -> assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
                "a refusal modifies nothing"));
    }

    @Test
    @DisplayName("REFUSES a field the constructor COMPUTES rather than takes")
    void refusesAComputedField() throws Exception {
        ToolResponse r = tool.execute(argsFor("Computed"));

        assertFalse(r.isSuccess(), "a function taking base and tax would have to carry the"
            + " addition, which is code this row would be inventing");
        assertEquals(ReplaceCommandWithFunctionTool.Refusal.STATE_NOT_CONSTRUCTOR_ONLY,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a field written AFTER construction, because that is state over time")
    void refusesStateThatOutlivesTheConstructor() throws Exception {
        ToolResponse r = tool.execute(argsFor("Accumulating"));

        assertFalse(r.isSuccess(), "the object carries something between being built and being"
            + " run, which is what a command is for");
        assertEquals(ReplaceCommandWithFunctionTool.Refusal.STATE_NOT_CONSTRUCTOR_ONLY,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a class with two instance methods, because which one is the function "
        + "would be a choice")
    void refusesATwoMethodClass() throws Exception {
        ToolResponse r = tool.execute(argsFor("TwoJobs"));

        assertFalse(r.isSuccess(), "a class with several methods is an object");
        assertEquals(ReplaceCommandWithFunctionTool.Refusal.NOT_A_COMMAND,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES when a use RETAINS the command instead of running it in place")
    void refusesARetainedCommand() throws Exception {
        ToolResponse r = tool.execute(argsFor("Retained"));

        assertFalse(r.isSuccess(), "holding it is the thing a command is for, so this row will"
            + " not take it away");
        assertEquals(ReplaceCommandWithFunctionTool.Refusal.CALL_SITE_NOT_CHAINED,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("auto_apply=false STAGES: the files are untouched until the change is applied")
    void stagesTheChangeAndAppliesItOnDemand() throws Exception {
        String before = Files.readString(desk, StandardCharsets.UTF_8);
        ObjectNode args = argsFor("Discount");
        args.put("auto_apply", false);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertEquals(before, Files.readString(desk, StandardCharsets.UTF_8),
            "STAGING must not write");

        Object changeId = ((java.util.Map<?, ?>) r.getData()).get("changeId");
        assertNotNull(changeId, "the response carries no changeId: " + r.getData());
        ObjectNode apply = new ObjectMapper().createObjectNode();
        apply.put("action", "apply");
        apply.put("changeId", String.valueOf(changeId));
        assertTrue(lifecycle.execute(apply).isSuccess());
        assertTrue(Files.readString(desk, StandardCharsets.UTF_8)
                .contains("CommandObjectTargets.Discount.execute(base, 10, 100)"),
            "and the staged change must be the real one");
    }

    @Test
    @DisplayName("the undo handle RESTORES both files")
    void undoRestoresEverything() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        String beforeDesk = Files.readString(desk, StandardCharsets.UTF_8);
        ToolResponse r = tool.execute(argsFor("Discount"));
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
            "the command's own file is restored");
        assertEquals(beforeDesk, Files.readString(desk, StandardCharsets.UTF_8),
            "and so are its uses'");
    }

    @Test
    @DisplayName("the door routes the kind")
    void theDoorRoutesIt() {
        assertTrue(tool.publishedKinds().contains("replace_command_with_function"),
            "a kind that is dispatched and not published is invisible in tools/list");
        assertTrue(tool.delegates().get("replace_command_with_function")
                instanceof ReplaceCommandWithFunctionTool,
            "and the routing table must reach this delegate");
    }
}
