package org.jawata.mcp.refactoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.jawata.mcp.change.DiffRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiffRendererTest {

    @Test
    @DisplayName("identical content renders an empty diff")
    void identicalContent_emptyDiff() {
        assertEquals("", DiffRenderer.unifiedDiff("A.java", "a\nb\n", "a\nb\n"));
    }

    @Test
    @DisplayName("single replaced line — golden unified diff")
    void singleReplacedLine_golden() {
        String oldContent = "a\nb\nc\nd\ne\n";
        String newContent = "a\nb\nX\nd\ne\n";

        String expected = """
            --- a/A.java
            +++ b/A.java
            @@ -1,5 +1,5 @@
             a
             b
            -c
            +X
             d
             e
            """;
        assertEquals(expected, DiffRenderer.unifiedDiff("A.java", oldContent, newContent));
    }

    @Test
    @DisplayName("insertion — golden unified diff")
    void insertion_golden() {
        String oldContent = "a\nb\n";
        String newContent = "a\nX\nb\n";

        String expected = """
            --- a/A.java
            +++ b/A.java
            @@ -1,2 +1,3 @@
             a
            +X
             b
            """;
        assertEquals(expected, DiffRenderer.unifiedDiff("A.java", oldContent, newContent));
    }

    @Test
    @DisplayName("deletion — golden unified diff")
    void deletion_golden() {
        String oldContent = "a\nX\nb\n";
        String newContent = "a\nb\n";

        String expected = """
            --- a/A.java
            +++ b/A.java
            @@ -1,3 +1,2 @@
             a
            -X
             b
            """;
        assertEquals(expected, DiffRenderer.unifiedDiff("A.java", oldContent, newContent));
    }

    @Test
    @DisplayName("distant changes split into separate hunks")
    void distantChanges_splitIntoHunks() {
        StringBuilder oldContent = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            oldContent.append("line").append(i).append('\n');
        }
        String newContent = oldContent.toString()
            .replace("line2\n", "LINE2\n")
            .replace("line18\n", "LINE18\n");

        String diff = DiffRenderer.unifiedDiff("A.java", oldContent.toString(), newContent);

        long hunkCount = diff.lines().filter(l -> l.startsWith("@@")).count();
        assertEquals(2, hunkCount, "changes 16 lines apart must not merge into one hunk:\n" + diff);
        assertTrue(diff.contains("-line2\n+LINE2"), diff);
        assertTrue(diff.contains("-line18\n+LINE18"), diff);
    }

    @Test
    @DisplayName("multi-file diff concatenates per-file blocks, skipping identical files")
    void multiFile_concatenatesAndSkipsIdentical() {
        String diff = DiffRenderer.unifiedDiff(List.of(
            new DiffRenderer.FileDiff("A.java", "a\n", "b\n"),
            new DiffRenderer.FileDiff("Same.java", "x\n", "x\n"),
            new DiffRenderer.FileDiff("B.java", "1\n", "2\n")
        ));

        assertTrue(diff.contains("--- a/A.java"), diff);
        assertTrue(diff.contains("--- a/B.java"), diff);
        assertFalse(diff.contains("Same.java"), "identical file must be skipped:\n" + diff);
    }

    @Test
    @DisplayName("empty old content renders an all-additions diff")
    void emptyOldContent_allAdditions() {
        String diff = DiffRenderer.unifiedDiff("A.java", "", "a\nb\n");
        assertTrue(diff.contains("@@ -0,0 +1,2 @@"), diff);
        assertTrue(diff.contains("+a\n+b\n"), diff);
    }

    /**
     * REL44-15. A staged change_method_signature on ExperienceRetrieval#fits — one declaration,
     * one call site, one @param line — rendered as ONE hunk of 1,561 added and 1,560 removed lines,
     * because the region between the first and the last change was too large for the LCS table
     * and fell back to replacing all of it. A diff that restates the file cannot be reviewed,
     * which is the whole point of staging one.
     */
    @Test
    @DisplayName("REL44-15: three edits far apart in a large file render as three edited lines")
    void farApartChangesInALargeFile_renderAsThemselves() {
        String oldText = numbered(1600);
        String newText = oldText
            .replace("    statement20;\n", "    statement20(tally);\n")
            .replace("    statement1580;\n", "    statement1580;\n    added;\n")
            .replace("    statement1584;\n", "    statement1584(tally);\n");

        String diff = DiffRenderer.unifiedDiff("Big.java", oldText, newText);

        assertEquals(List.of(new DiffRenderer.FileSummary("Big.java", 2, 3, 2)),
            DiffRenderer.summarize(diff),
            () -> "two hunks, three lines added and two removed — not the whole file:\n"
                + diff.lines().limit(12).reduce("", (x, y) -> x + y + "\n"));
        assertEquals(newText, applied(oldText, diff), "and the diff must still say what changed");
    }

    @Test
    @DisplayName("REL44-15: scattered edits over a large file apply back to the new text")
    void scatteredEditsInALargeFile_applyBack() {
        String oldText = numbered(2000);
        List<String> lines = new ArrayList<>(oldText.lines().toList());
        java.util.Random random = new java.util.Random(4415);
        int edited = 30;
        for (int e = 0; e < edited; e++) {
            int at = random.nextInt(lines.size());
            switch (random.nextInt(3)) {
                case 0 -> lines.add(at, "    inserted" + e + ";");
                case 1 -> lines.remove(at);
                default -> lines.set(at, "    replaced" + e + ";");
            }
        }
        String newText = String.join("\n", lines) + "\n";

        String diff = DiffRenderer.unifiedDiff("Big.java", oldText, newText);

        assertEquals(newText, applied(oldText, diff), "the diff must reproduce the new text");
        DiffRenderer.FileSummary s = DiffRenderer.summarize(diff).get(0);
        int changed = s.added() + s.removed();
        int bound = 2 * edited;
        assertTrue(changed <= bound,
            () -> edited + " edits may change at most " + bound + " lines; the diff changed "
                + changed + " — it restated the file instead of the edits");
    }

    /** {@code n} distinct lines, the way a large Java file is distinct line by line. */
    private static String numbered(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            sb.append("    statement").append(i).append(";\n");
        }
        return sb.toString();
    }

    /**
     * Applies a diff this class rendered to {@code oldText}, strictly: every context and removed
     * line must be the old line it claims to be. A renderer that miscounted a hunk would be
     * caught here rather than by a reader.
     */
    private static String applied(String oldText, String diff) {
        List<String> a = oldText.lines().toList();
        List<String> out = new ArrayList<>();
        java.util.regex.Pattern hunk =
            java.util.regex.Pattern.compile("^@@ -(\\d+),(\\d+) \\+(\\d+),(\\d+) @@$");
        int next = 0;
        for (String line : diff.split("\n")) {
            java.util.regex.Matcher m = hunk.matcher(line);
            if (m.matches()) {
                int aStart = Integer.parseInt(m.group(1));
                int from = Integer.parseInt(m.group(2)) == 0 ? aStart : aStart - 1;
                while (next < from) {
                    out.add(a.get(next++));
                }
            } else if (line.startsWith("--- a/") || line.startsWith("+++ b/")) {
                continue;
            } else if (line.startsWith(" ") || line.startsWith("-")) {
                assertEquals(a.get(next), line.substring(1), "a context or removed line must be the old line");
                if (line.startsWith(" ")) {
                    out.add(a.get(next));
                }
                next++;
            } else if (line.startsWith("+")) {
                out.add(line.substring(1));
            }
        }
        while (next < a.size()) {
            out.add(a.get(next++));
        }
        return out.isEmpty() ? "" : String.join("\n", out) + "\n";
    }
}
