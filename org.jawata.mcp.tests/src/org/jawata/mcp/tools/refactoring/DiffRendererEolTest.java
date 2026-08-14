package org.jawata.mcp.tools.refactoring;

import org.jawata.mcp.refactoring.DiffRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28a (1b, step M10) — a diff over a CRLF project must not carry
 * carriage returns into its output.
 *
 * <p>Characterization test, written RED. {@code DiffRenderer} split on
 * {@code "\n"} alone, so every line of a CRLF file arrived with a trailing
 * {@code \r} still attached. The renderer then emitted those bytes inside the
 * diff, where a reader sees them as nothing at all — until a comparison, a
 * patch application, or a second diff disagrees for reasons invisible on
 * screen.</p>
 *
 * <p>This is the user-facing half of the same defect that failed nineteen
 * parity tests on the first Windows CI run. That half was fixed by pinning
 * line endings at checkout, which protects OUR fixtures; it does nothing for a
 * user whose project genuinely uses CRLF, which is the normal case on
 * Windows.</p>
 */
class DiffRendererEolTest {

    private static final String CRLF_OLD = "class A {\r\n    void a() {}\r\n}\r\n";
    private static final String CRLF_NEW = "class A {\r\n    void b() {}\r\n}\r\n";

    @Test
    @DisplayName("a CRLF project yields a diff with no stray carriage returns")
    void crlfContentProducesCleanDiffLines() {
        String diff = DiffRenderer.unifiedDiff("A.java", CRLF_OLD, CRLF_NEW);

        assertFalse(diff.contains("\r"),
            "the diff carries a carriage return the reader cannot see: "
                + diff.replace("\r", "<CR>"));
    }

    @Test
    @DisplayName("the CRLF diff still reports the real change, not a whole-file rewrite")
    void crlfDiffIsMinimal() {
        String diff = DiffRenderer.unifiedDiff("A.java", CRLF_OLD, CRLF_NEW);

        // If the terminators were part of the compared lines, EVERY line would
        // differ from itself and the diff would claim the whole file changed.
        long changed = diff.lines()
            .filter(l -> (l.startsWith("+") || l.startsWith("-"))
                && !l.startsWith("+++") && !l.startsWith("---"))
            .count();
        assertTrue(changed <= 2, "one changed line should produce one -/+ pair, got " + changed
            + ":\n" + diff);
        assertTrue(diff.contains("void a") && diff.contains("void b"), diff);
    }

    @Test
    @DisplayName("an LF project is unaffected — the parity goldens must not move")
    void lfContentIsUnchanged() {
        String lfOld = CRLF_OLD.replace("\r\n", "\n");
        String lfNew = CRLF_NEW.replace("\r\n", "\n");

        String diff = DiffRenderer.unifiedDiff("A.java", lfOld, lfNew);

        assertFalse(diff.contains("\r"), diff);
        assertTrue(diff.contains("void a") && diff.contains("void b"), diff);
    }
}
