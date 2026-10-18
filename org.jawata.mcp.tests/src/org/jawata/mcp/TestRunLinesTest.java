package org.jawata.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The live failure line of the in-framework runner (see {@link TestRunLines}). The line the
 * listener prints is built here, so what a person reads mid-run is pinned: the test, the reason,
 * and a prefix the abort budget cannot mistake for a skip.
 */
class TestRunLinesTest {

    private static final String ID =
        "[engine:junit-jupiter]/[class:org.jawata.mcp.knowledge.RetrievalLanesTest]"
            + "/[method:aLessonRankedBehindTheCodeRowsStillArrives()]";

    @Test
    void aFailedTestIsNamedWithItsClassAndItsReason() {
        String line = TestRunLines.failed(ID, new AssertionError("expected: <true> but was: <false>"));

        assertEquals("        ~~ FAILED org.jawata.mcp.knowledge.RetrievalLanesTest"
            + "#aLessonRankedBehindTheCodeRowsStillArrives(): java.lang.AssertionError: "
            + "expected: <true> but was: <false>", line);
    }

    @Test
    void aFailureIsNeverCountedAsASkipByTheAbortBudget() {
        String line = TestRunLines.failed(ID, new AssertionError("boom"));

        assertTrue(line.contains("~~ FAILED "), line);
        assertFalse(line.contains("~~ ABORTED"),
            "the abort budget greps '~~ ABORTED': a failure carrying that prefix would be budgeted away");
    }

    @Test
    void aLongMultiLineReasonIsCutToItsFirstLineAndAFewHundredCharacters() {
        String message = "first line " + "x".repeat(500) + "\nsecond line that must not appear";
        String line = TestRunLines.failed(ID, new AssertionError(message));

        assertFalse(line.contains("second line"), line);
        assertTrue(line.endsWith("..."), "a cut reason says it was cut: " + line);
        assertTrue(line.length() < 500, "one short line, not the whole answer: " + line.length());
    }

    @Test
    void aMissingCauseAndAnUnfamiliarIdStillGiveALine() {
        assertEquals("        ~~ FAILED some-other-id: (no reason given)",
            TestRunLines.failed("some-other-id", null));
        assertEquals("        ~~ FAILED null: (no reason given)", TestRunLines.failed(null, null));
    }
}
