package org.jawata.mcp.tools.refactoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jawata-mcp#68 — A GOLDEN COMPARES SOURCE, NOT THE PLATFORM'S WAY OF ENDING A LINE.
 *
 * <p>On the Windows matrix job at the v4.1.0 tag, one parity golden diverged from its archive
 * by line endings ALONE: carriage-return-plus-newline on the produced side, newline in the
 * golden. It was the only failure on that job.</p>
 *
 * <p><b>The checkout was measured NOT to be the cause, which is what makes this the right
 * layer.</b> {@code git ls-files --eol} reports the fixture as
 * {@code i/lf w/lf attr/text=auto eol=lf}, and no file in the repository carries carriage
 * returns in its stored blob — {@code .gitattributes} has pinned that since Sprint 28a, added
 * after the first-ever Windows run failed nineteen of these tests on exactly that cause. The
 * INPUTS were already normalised; what was not was the output of one Eclipse engine, which
 * generates with the platform's separator where our own rewrites keep the document's.</p>
 *
 * <h2>Why this test can exist on Linux at all, and why that is not a cheat</h2>
 *
 * <p>The failing platform is not available here, so the divergence is CONSTRUCTED: an existing
 * golden is read and handed back with its endings converted. That is the same text by every
 * measure a golden is for, and the comparison must accept it. The second case is the control
 * that keeps the first from being vacuous — genuinely different content must still fail, or
 * this would be asserting that the comparison has stopped comparing.</p>
 *
 * <p><b>What this does NOT claim:</b> that a Windows user should or should not get carriage
 * returns written into a file that has none. That is a product question about somebody's real
 * workspace and it is open — nothing in this product sets a line-delimiter policy at all.</p>
 */
class ParityIgnoresLineEndingConventionTest {

    /** Any recorded source golden serves; this one is small and stable. */
    private static final String TOOL = "data";
    private static final String ID = "row-hide_delegate";

    private String golden() throws Exception {
        Path path = ParitySupport.goldenDir(TOOL).resolve(ID + ".golden");
        assertTrue(Files.exists(path), () ->
            "PROOF OF LIFE: this test needs a recorded golden to convert; " + path
                + " is missing, so nothing below would be measuring the comparison.");
        String text = Files.readString(path);
        assertTrue(text.contains("\n"), "a single-line golden could not show an ending change");
        return text;
    }

    @Test
    @DisplayName("the same source with the other platform's line endings still matches its golden")
    void theOtherConventionStillMatches() throws Exception {
        String asRecorded = golden();
        String asWindowsWouldWriteIt = asRecorded.replace("\n", "\r\n");
        assertNotEquals(asRecorded, asWindowsWouldWriteIt,
            "PROOF OF LIFE: the conversion must actually change the text, or the assertion "
                + "below passes without exercising anything");

        // The claim: this does not throw. Before the fix it threw, and the Windows job's
        // whole failure was this one comparison.
        ParitySupport.assertSourceParity(TOOL, ID, asWindowsWouldWriteIt);
    }

    @Test
    @DisplayName("and a real content change still fails — the comparison did not stop comparing")
    void aRealDifferenceStillFails() throws Exception {
        String changed = golden() + "\nclass SomethingTheRefactoringNeverProduced { }\n";

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
            () -> ParitySupport.assertSourceParity(TOOL, ID, changed),
            "normalising line endings must not make the golden accept different source");
        assertTrue(String.valueOf(failure.getMessage()).contains("PARITY DIVERGENCE"),
            "and it must fail as the parity gate, naming what diverged: " + failure.getMessage());
    }

    @Test
    @DisplayName("a lone carriage return is normalised too, not just the pair")
    void theOldMacConventionIsCoveredAsWell() throws Exception {
        // Not a platform anyone ships today; it is here because the implementation handles it
        // and an unexercised branch reads exactly like a handled one.
        String asLoneCarriageReturns = golden().replace("\n", "\r");
        assertEquals(golden().length(), asLoneCarriageReturns.length(),
            "PROOF OF LIFE: this conversion swaps characters rather than adding any");
        ParitySupport.assertSourceParity(TOOL, ID, asLoneCarriageReturns);
    }
}
