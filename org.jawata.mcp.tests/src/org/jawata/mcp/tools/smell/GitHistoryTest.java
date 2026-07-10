package org.jawata.mcp.tools.smell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 17 — {@link GitHistory} parser. Deterministic: feeds a synthetic
 * {@code git log} payload (no live git) and checks the per-file commit counts
 * and co-change area counts. The 0x1F byte marks each commit boundary.
 */
class GitHistoryTest {

    private static final char US = '\u001F';

    @Test
    @DisplayName("parses commit blocks, counts commits per file")
    void commitCounts() {
        String raw = US + "h1\ndir1/A.java\ndir2/B.java\n"
                   + US + "h2\ndir1/A.java\ndir3/C.java\n";
        GitHistory h = GitHistory.fromGitLog(raw);
        assertTrue(h.available());
        assertEquals(2, h.commitCount("dir1/A.java"));
        assertEquals(1, h.commitCount("dir2/B.java"));
        assertEquals(1, h.commitCount("dir3/C.java"));
        assertEquals(0, h.commitCount("nope/X.java"));
    }

    @Test
    @DisplayName("counts distinct co-change areas (parent dirs of co-changed files)")
    void coChangeAreas() {
        String raw = US + "h1\ndir1/A.java\ndir2/B.java\n"
                   + US + "h2\ndir1/A.java\ndir3/C.java\n";
        GitHistory h = GitHistory.fromGitLog(raw);
        // A.java co-changed with dir2/* and dir3/* → 2 distinct areas
        assertEquals(2, h.coChangeAreaCount("dir1/A.java"));
    }

    @Test
    @DisplayName("an unavailable history reports zero for everything")
    void unavailable() {
        GitHistory h = GitHistory.unavailable();
        assertFalse(h.available());
        assertEquals(0, h.commitCount("anything.java"));
        assertEquals(0, h.coChangeAreaCount("anything.java"));
    }
}
