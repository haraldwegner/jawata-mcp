package org.jawata.core.host;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Sprint 28a (1b, step M10) — the boundary's text contract.
 *
 * <p>Environment-independent by construction: the line endings are in the
 * fixtures, not in the runner, so a CRLF file is exercised from any host.</p>
 */
class HostTextTest {

    @Test
    @DisplayName("splitting handles CRLF, CR and LF alike — no carriage return survives onto a line")
    void everyTerminatorSplitsCleanly() {
        assertArrayEquals(new String[] {"a", "b", "c"}, HostText.splitLines("a\r\nb\r\nc"));
        assertArrayEquals(new String[] {"a", "b", "c"}, HostText.splitLines("a\rb\rc"));
        assertArrayEquals(new String[] {"a", "b", "c"}, HostText.splitLines("a\nb\nc"));

        for (String line : HostText.splitLines("x\r\ny\r\n")) {
            assertFalse(line.contains("\r"),
                "a stray \\r on a line is the invisible byte that broke 19 parity tests");
        }
    }

    @Test
    @DisplayName("a trailing terminator is not an extra line")
    void aTrailingTerminatorIsNotALine() {
        assertArrayEquals(new String[] {"a", "b"}, HostText.splitLines("a\nb\n"));
        assertArrayEquals(new String[] {"a", "b"}, HostText.splitLines("a\r\nb\r\n"));
        assertArrayEquals(new String[0], HostText.splitLines(""));
        assertArrayEquals(new String[0], HostText.splitLines(null));
    }

    @Test
    @DisplayName("a file with no terminator at all is one line")
    void noTerminatorIsOneLine() {
        assertArrayEquals(new String[] {"only"}, HostText.splitLines("only"));
    }

    @Test
    @DisplayName("canonicalising makes every terminator LF, the form everything above the boundary uses")
    void canonicalFormIsLf() {
        assertEquals("a\nb\nc", HostText.canonicalizeToLf("a\r\nb\rc"));
        assertEquals("a\nb\n", HostText.canonicalizeToLf("a\r\nb\r\n"));
        assertNull(HostText.canonicalizeToLf(null));
    }

    @Test
    @DisplayName("the terminator a file uses is detectable, so a rewrite can put back what it found")
    void theExistingTerminatorIsDetectable() {
        // Writing a user's CRLF file back as LF rewrites every line of it: a
        // one-method refactoring that arrives in review as the whole file.
        assertEquals("\r\n", HostText.eolOf("a\r\nb", "\n"));
        assertEquals("\n", HostText.eolOf("a\nb", "\r\n"));
        assertEquals("\r", HostText.eolOf("a\rb", "\n"));
        assertEquals("\n", HostText.eolOf("no terminator here", "\n"), "the fallback answers");
        assertEquals("\r\n", HostText.eolOf(null, "\r\n"));
    }
}
