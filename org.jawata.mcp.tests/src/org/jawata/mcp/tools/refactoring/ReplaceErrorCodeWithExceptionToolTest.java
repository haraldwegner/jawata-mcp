package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.api.ReplaceErrorCodeWithExceptionTool;
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
 * Stage 4, row 46 — Replace Error Code with Exception, as
 * {@code change_method_signature kind=replace_error_code_with_exception}.
 *
 * <p>This is the one row in the stage that deliberately CHANGES BEHAVIOUR in the failure case,
 * which is the refactoring rather than a defect. So the assertions come in two halves: that the
 * returns became a throw the compiler will police, and that a caller ALREADY handling the failure
 * is refused rather than quietly left with a test that can no longer be true.</p>
 */
class ReplaceErrorCodeWithExceptionToolTest {

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
            .resolve("src/main/java/com/example/ErrorCodeTargets.java");
    }

    private ObjectNode argsFor(String method) {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "replace_error_code_with_exception");
        args.put("symbol", "com.example.ErrorCodeTargets#" + method);
        args.put("errorValue", "-1");
        args.put("exceptionType", "IllegalStateException");
        return args;
    }

    /** One method's text, so "gone from HERE" is expressible — row 61 earned this helper. */
    private String bodyOf(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "the fixture no longer declares " + signature + ":\n" + source);
        int next = source.indexOf("\n    public ", at + signature.length());
        return source.substring(at, next < 0 ? source.length() : next);
    }

    @Test
    @DisplayName("the sentinel return becomes a throw, the successful return is untouched, and "
        + "the exception is declared")
    void turnsTheErrorCodeIntoAThrow() throws Exception {
        ToolResponse r = tool.execute(argsFor("readingAt"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String body = bodyOf(Files.readString(targets, StandardCharsets.UTF_8),
            "public int readingAt(int slot)");
        Assertions.assertAll(
            () -> assertTrue(body.contains("throw new IllegalStateException();"),
                "the sentinel return must become the throw:\n" + body),
            () -> assertFalse(body.contains("return -1;"),
                "and be gone from THIS method — a whole-file search would find the sentinel in"
                    + " the three sibling methods and pass over nothing:\n" + body),
            () -> assertTrue(body.contains("return readings[slot];"),
                "while the SUCCESSFUL return is untouched, which is what distinguishes this from"
                    + " a method that only fails:\n" + body),
            () -> assertTrue(body.contains("throws IllegalStateException"),
                "and the exception must be DECLARED — with a checked one that declaration is the"
                    + " whole safety mechanism, so the row writes it either way:\n" + body));
    }

    @Test
    @DisplayName("the response SAYS the behaviour changed, because every other row in this stage "
        + "preserves it")
    void saysThatBehaviourChanged() throws Exception {
        ToolResponse r = tool.execute(argsFor("readingAt"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        java.util.Map<?, ?> data = (java.util.Map<?, ?>) r.getData();
        String summary = String.valueOf(data);
        Assertions.assertAll(
            () -> assertTrue(summary.contains("CHANGES BEHAVIOUR"),
                "a caller that carried on with the sentinel now propagates, and a reader who"
                    + " assumed this row was like its neighbours would be wrong: " + summary),

            // THE CHECKED/UNCHECKED SENTENCE IS UNCONDITIONAL, so an assertion on the word
            // CHECKED is entailed by the one above and can detect nothing it does not — a
            // C4 audit found that by reading the production line rather than by mutating,
            // and it was right. What follows are the parts of the response that DO vary with
            // the input, which is what makes them worth asserting.
            // READ OFF THE FIELD, not searched for in the rendered response. The first
            // version of this assertion searched `summary` for "-1" and could not fail: the
            // response carries a unified diff whose hunk header reads @@ -18,9 +18,9 @@, so
            // the needle is in the text whatever the row did with the value. That is the
            // very defect this assertion was written to REPLACE, reproduced inside its own
            // repair, and a round-2 audit measured it by stripping the value from the map
            // and watching the check still pass.
            () -> assertEquals("-1", data.get("errorValue"),
                "the response must carry back the value the caller named, because that is"
                    + " what the row could not infer and had to be told: " + data),
            () -> assertEquals(1, data.get("returnsReplaced"),
                "readingAt has exactly one sentinel return; a count that drifts means the row"
                    + " rewrote more or less of the method than the fixture declares: " + data),
            () -> assertEquals(2, data.get("callersChecked"),
                "ErrorCodeDesk calls readingAt twice and neither tests the result — the count"
                    + " is the evidence for the claim that none of them did: " + data));
    }

    @Test
    @DisplayName("REFUSES when a caller compares the CALL to the sentinel")
    void refusesACallerThatTestsTheCallDirectly() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = tool.execute(argsFor("checkedReadingAt"));

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(), "that caller is handling the failure today"),
            () -> assertEquals(ReplaceErrorCodeWithExceptionTool.Refusal.CALLER_TESTS_THE_CODE,
                r.getError().getReason(), "got: " + r.getError()),
            () -> assertTrue(String.valueOf(r.getError()).contains("== -1"),
                "and it must QUOTE the test it found, so the caller can go and look at it: "
                    + r.getError()),
            () -> assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
                "a refusal modifies nothing"));
    }

    @Test
    @DisplayName("REFUSES when a caller holds the result and compares the LOCAL — the commoner "
        + "shape, and the one a check on the call alone would miss")
    void refusesACallerThatTestsAHeldValue() throws Exception {
        ToolResponse r = tool.execute(argsFor("heldReadingAt"));

        assertFalse(r.isSuccess(), "storing it first does not make the caller unaware of the"
            + " failure — it is the ordinary way to write the same test");
        assertEquals(ReplaceErrorCodeWithExceptionTool.Refusal.CALLER_TESTS_THE_CODE,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a method whose EVERY return is the sentinel")
    void refusesAMethodThatOnlyFails() throws Exception {
        ToolResponse r = tool.execute(argsFor("alwaysFails"));

        assertFalse(r.isSuccess(), "there is no success path to distinguish a failure from, so"
            + " there is no error CODE");
        assertEquals(ReplaceErrorCodeWithExceptionTool.Refusal.ALL_RETURNS_ARE_THE_ERROR,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a value the method never returns, matched AS WRITTEN")
    void refusesAValueTheMethodNeverReturns() throws Exception {
        ObjectNode args = argsFor("readingAt");
        args.put("errorValue", "- 1");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "the value is matched as the source spells it, so `- 1` is"
            + " not `-1` — the caller names what they can see");
        assertEquals(ReplaceErrorCodeWithExceptionTool.Refusal.NO_SUCH_RETURN,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES without an errorValue, because nothing can infer which value fails")
    void refusesWithoutAnErrorValue() throws Exception {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "replace_error_code_with_exception");
        args.put("symbol", "com.example.ErrorCodeTargets#readingAt");
        args.put("exceptionType", "IllegalStateException");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "most sentinel-looking returns are ordinary control flow");
        assertEquals(ReplaceErrorCodeWithExceptionTool.Refusal.ERROR_VALUE_REQUIRED,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES without an exceptionType, because naming the failure IS the change")
    void refusesWithoutAnExceptionType() throws Exception {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "replace_error_code_with_exception");
        args.put("symbol", "com.example.ErrorCodeTargets#readingAt");
        args.put("errorValue", "-1");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "a generated name would deliver none of the point");
        assertEquals(ReplaceErrorCodeWithExceptionTool.Refusal.EXCEPTION_TYPE_REQUIRED,
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
                .contains("throw new IllegalStateException();"),
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
        assertTrue(tool.publishedKinds().contains("replace_error_code_with_exception"),
            "a kind that is dispatched and not published is invisible in tools/list");
        assertTrue(tool.delegates().get("replace_error_code_with_exception")
                instanceof ReplaceErrorCodeWithExceptionTool,
            "and the routing table must reach this delegate");
    }
}
