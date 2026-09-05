package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.api.ReplaceExceptionWithPrecheckTool;
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
 * Stage 4, row 47 — Replace Exception with Precheck, as
 * {@code change_method_signature kind=replace_exception_with_precheck}.
 *
 * <p>The row's three derivations each get a case, and so does each refusal. The refusals matter
 * more than the successes here: this row's whole claim is that the check it writes means exactly
 * what the handler was catching, and every refusal is a shape where that would stop being true.</p>
 */
class ReplaceExceptionWithPrecheckToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ChangeMethodSignatureTool tool;
    private org.jawata.mcp.tools.RefactoringTool lifecycle;
    private Path targets;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        org.jawata.mcp.refactoring.RefactoringChangeCache cache =
            new org.jawata.mcp.refactoring.RefactoringChangeCache();
        tool = new ChangeMethodSignatureTool(() -> service, cache);
        lifecycle = new org.jawata.mcp.tools.RefactoringTool(() -> service, cache);
        targets = service.getProjectRoot()
            .resolve("src/main/java/com/example/PrecheckTargets.java");
    }

    private ObjectNode argsFor(String method) {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "replace_exception_with_precheck");
        args.put("symbol", "com.example.PrecheckTargets#" + method);
        return args;
    }

    /** One method's text, so "gone from HERE" is expressible — row 61 earned this helper. */
    private String bodyOf(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "the fixture no longer declares " + signature + ":\n" + source);
        int next = source.indexOf("\n    public ", at + signature.length());
        int end = next < 0 ? source.length() : next;
        return source.substring(at, end);
    }

    @Test
    @DisplayName("an array index: the catch becomes a guard on the index and the length")
    void checksTheIndexInsteadOfCatching() throws Exception {
        ToolResponse r = tool.execute(argsFor("readingAt"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String body = bodyOf(Files.readString(targets, StandardCharsets.UTF_8),
            "public double readingAt(int slot)");
        Assertions.assertAll(
            () -> assertTrue(body.contains("if (slot < 0 || slot >= readings.length)"),
                "the derived check must name the index AND the array it indexes:\n" + body),
            () -> assertFalse(body.contains("catch"),
                "and the handler it replaces must be gone from THIS method:\n" + body),
            () -> assertTrue(body.contains("return readings[slot];"),
                "while the guarded statement survives unchanged:\n" + body),

            // THE HANDLER'S BODY IS INDENTED WHERE IT NOW SITS, and this assertion exists
            // because its absence let a real defect ship past twelve green tests. The first
            // version spliced the handler's SOURCE TEXT into the guard, carrying the deeper
            // indentation it had inside the catch. Everything above still passed — a contains
            // cannot see whitespace — and the result compiled, so no gate below could see it
            // either. Only reading the recorded output did.
            () -> assertTrue(body.contains("\n        if (slot < 0 || slot >= readings.length) {"
                    + "\n            return 0.0;\n        }\n"),
                "the guard must be indented at the method's own level, braces and all — the"
                    + " handler moved OUT of a catch and must not keep that depth:\n" + body));
    }

    @Test
    @DisplayName("a null receiver: the catch becomes a null check on the thing dereferenced")
    void checksTheReceiverInsteadOfCatching() throws Exception {
        ToolResponse r = tool.execute(argsFor("labelOf"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String body = bodyOf(Files.readString(targets, StandardCharsets.UTF_8),
            "public String labelOf(Reading reading)");
        assertTrue(body.contains("if (reading == null)"),
            "the check must name the RECEIVER, which is the thing that can be absent:\n" + body);
        assertFalse(body.contains("catch"), "and the handler is gone:\n" + body);
    }

    @Test
    @DisplayName("integer division: the catch becomes a check on the divisor")
    void checksTheDivisorInsteadOfCatching() throws Exception {
        ToolResponse r = tool.execute(argsFor("share"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String body = bodyOf(Files.readString(targets, StandardCharsets.UTF_8),
            "public int share(int total, int people)");
        assertTrue(body.contains("if (people == 0)"),
            "the check must name the DIVISOR, not the dividend:\n" + body);
        assertFalse(body.contains("catch"), "and the handler is gone:\n" + body);
    }

    @Test
    @DisplayName("REFUSES a guarded statement that CALLS something — the call could raise the "
        + "same exception from inside itself")
    void refusesAGuardedCall() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = tool.execute(argsFor("firstOf"));

        assertFalse(r.isSuccess(), "a precheck on the visible index would not stop the exception"
            + " escaping from inside the call");
        assertEquals(ReplaceExceptionWithPrecheckTool.Refusal.NO_PRECHECK_DERIVABLE,
            r.getError().getReason(), "got: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("REFUSES a handler that READS the exception, because afterwards there is none")
    void refusesAHandlerThatReadsTheException() throws Exception {
        ToolResponse r = tool.execute(argsFor("readingOrReport"));

        assertFalse(r.isSuccess(), "the handler's message comes from the exception object");
        assertEquals(ReplaceExceptionWithPrecheckTool.Refusal.HANDLER_CANNOT_MOVE,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a handler that FALLS THROUGH, because a precheck runs before the code "
        + "it guards")
    void refusesAHandlerThatFallsThrough() throws Exception {
        ToolResponse r = tool.execute(argsFor("readingOrZero"));

        assertFalse(r.isSuccess(), "moved to the front, that handler would run and then continue"
            + " into the statement it was handling");
        assertEquals(ReplaceExceptionWithPrecheckTool.Refusal.HANDLER_CANNOT_MOVE,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES floating-point division, which answers infinity rather than throwing")
    void refusesFloatingPointDivision() throws Exception {
        ToolResponse r = tool.execute(argsFor("ratio"));

        assertFalse(r.isSuccess(), "there is no exception here to precheck for — a zero divisor"
            + " gives infinity, so a guard would CHANGE the answer rather than preserve it");
        assertEquals(ReplaceExceptionWithPrecheckTool.Refusal.NO_PRECHECK_DERIVABLE,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES two try statements in one body, and naming the exception resolves it")
    void refusesAnAmbiguousTryAndAcceptsTheNamedOne() throws Exception {
        ToolResponse ambiguous = tool.execute(argsFor("either"));
        assertFalse(ambiguous.isSuccess(), "acting on the first one read would be a coin toss");
        assertEquals(ReplaceExceptionWithPrecheckTool.Refusal.AMBIGUOUS_TRY,
            ambiguous.getError().getReason(), "got: " + ambiguous.getError());

        // THE CONTROL. Without it the refusal above could be caused by anything about this
        // method, and the exceptionType parameter would be asserted by nothing.
        ObjectNode named = argsFor("either");
        named.put("exceptionType", "ArithmeticException");
        ToolResponse r = tool.execute(named);
        assertTrue(r.isSuccess(), "naming one must resolve the ambiguity: " + r.getError());
        assertTrue(bodyOf(Files.readString(targets, StandardCharsets.UTF_8),
                "public double either(int slot, int people)").contains("if (people == 0)"),
            "and it must act on the one NAMED, not on the other");
    }

    @Test
    @DisplayName("REFUSES a method with no try at all")
    void refusesAMethodWithNoTry() throws Exception {
        ToolResponse r = tool.execute(argsFor("neighbour"));

        assertFalse(r.isSuccess(), "there is no handler to replace");
        assertEquals(ReplaceExceptionWithPrecheckTool.Refusal.NO_TRY_TO_REPLACE,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("auto_apply=false STAGES: the file is untouched until the change is applied")
    void stagesTheChangeAndAppliesItOnDemand() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ObjectNode args = argsFor("readingAt");
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
                .contains("if (slot < 0 || slot >= readings.length)"),
            "and the staged change must be the real one");
    }

    @Test
    @DisplayName("the undo handle RESTORES the file")
    void undoRestoresTheFile() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = tool.execute(argsFor("readingAt"));
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
            "the file is restored");
    }

    @Test
    @DisplayName("the door routes the kind")
    void theDoorRoutesIt() {
        assertTrue(tool.publishedKinds().contains("replace_exception_with_precheck"),
            "a kind that is dispatched and not published is invisible in tools/list");
        assertTrue(tool.delegates().get("replace_exception_with_precheck")
                instanceof ReplaceExceptionWithPrecheckTool,
            "and the routing table must reach this delegate");
    }
}
