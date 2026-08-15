package org.jawata.core.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

}
