package org.jawata.mcp.tools;

import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S3 / D7a — what the caller is told when the extract engine THROWS.
 *
 * <p>The defect: on one file in a foreign project every selection answered
 * {@code INTERNAL_ERROR: IllegalStateException: source not specified at
 * ASTParser.createAST(ASTParser.java:931)} — one frame, no phase. The engine is driven in two
 * phases, {@code checkAllConditions} and {@code createChange}, and nothing in that response
 * said which of them had no source, so the fault could not be located from the client side at
 * all. The record's own prescribed fix is quoted in {@code ExtractMethodTool.engineThrew}:
 * <em>"Catch it at the tool and say which parse threw."</em></p>
 *
 * <p><b>What this test can and cannot hold, stated rather than left to be assumed.</b> It pins
 * the MESSAGE FORMAT: that the phase is named, that the exception type and message survive, and
 * that the originating frames are carried. It does NOT prove the branch fires on a real throw —
 * no fixture reaches it, because the only input known to produce it is a file this repository
 * does not own. The wiring is held by the compiler instead: {@code engineThrew} is called from
 * the catch around each engine phase and from nowhere else.</p>
 */
class AnEngineThrowNamesItsPhaseTest {

    @Test
    @DisplayName("the response names WHICH phase threw, the exception, and the frame")
    void theResponseNamesThePhaseAndTheFrame() {
        IllegalStateException thrown = new IllegalStateException("source not specified");
        thrown.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("org.eclipse.jdt.core.dom.ASTParser", "createAST",
                "ASTParser.java", 931),
            new StackTraceElement("org.eclipse.jdt.internal.corext.refactoring.util."
                + "RefactoringASTParser", "parse", "RefactoringASTParser.java", 63),
        });

        ToolResponse r = ExtractMethodTool.engineThrew(
            "checkAllConditions", thrown, "src/com/example/Target.java", 1200, 1260);

        assertFalse(r.isSuccess(), "an engine throw is not a success");
        assertNotNull(r.getError());
        assertEquals("EXTRACT_ENGINE_FAILED", r.getError().getCode());

        String message = r.getError().getMessage();
        assertTrue(message.contains("checkAllConditions"),
            "the PHASE is the thing the old response lacked; got: " + message);
        assertTrue(message.contains("IllegalStateException"),
            "the exception type must survive; got: " + message);
        assertTrue(message.contains("source not specified"),
            "the exception message must survive; got: " + message);
        assertTrue(message.contains("ASTParser.java:931"),
            "the originating FRAME is what the client never saw; got: " + message);
        assertTrue(message.contains("RefactoringASTParser.java:63"),
            "more than the top frame, or the caller cannot see which parse; got: " + message);

        String hint = r.getError().getHint();
        assertTrue(hint.contains("1200") && hint.contains("1260"),
            "the selection offsets let a reader reproduce it; got: " + hint);
        assertTrue(hint.contains("No files were modified"),
            "a throw must say nothing was written; got: " + hint);
    }

    @Test
    @DisplayName("the OTHER phase is named when it is the one that threw — not a fixed string")
    void theOtherPhaseIsNamedToo() {
        // The control. Without it, a hardcoded "checkAllConditions" would satisfy the test
        // above and the phase — the whole point of the change — would be decorative.
        ToolResponse r = ExtractMethodTool.engineThrew(
            "createChange", new RuntimeException("boom"), "src/com/example/Target.java", 0, 10);

        String message = r.getError().getMessage();
        assertTrue(message.contains("createChange"), "got: " + message);
        assertFalse(message.contains("checkAllConditions"),
            "the phase must be the one that threw, not a constant; got: " + message);
    }
}
