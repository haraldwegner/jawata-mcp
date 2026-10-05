package org.jawata.mcp.refactoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Sprint 14b — minimal unified-diff renderer for refactoring responses.
 *
 * <p>Line-based, 3 context lines, classic {@code --- a/ +++ b/ @@} framing.
 * The middle region (after common prefix/suffix trimming) is diffed with an
 * LCS table when small enough; beyond {@link #MAX_LCS_CELLS} it degrades to a
 * single whole-region replace hunk — bounded memory on pathological inputs,
 * still a correct (just non-minimal) diff.</p>
 */
public final class DiffRenderer {

    private static final int CONTEXT_LINES = 3;
    private static final long MAX_LCS_CELLS = 1_000_000L;

    private DiffRenderer() {}

    /** One file's before/after content pair. */
    public record FileDiff(String filePath, String oldContent, String newContent) {}

    /** What one file contributed to a diff: how many hunks, and how many lines each way. */
    public record FileSummary(String filePath, int hunks, int added, int removed) {}

    /**
     * Sprint 28g S3 / D7f — the per-file account of a diff THIS class rendered.
     *
     * <p>It lives here rather than beside its caller because the format is this class's:
     * a reader elsewhere would be a second implementation of the header and hunk shapes,
     * which is the kind of copy that is right until one of the two moves.</p>
     *
     * <p><strong>A header is recognised as a PAIR</strong> — {@code --- a/<path>}
     * immediately followed by {@code +++ b/<path>} naming the SAME file. Two things about
     * that are worth stating precisely, because a mutation caught the first version of this
     * paragraph claiming the wrong one:</p>
     *
     * <ul>
     *   <li>What stops an ADDED line being read as a header is the {@code --- a/} half, not
     *       the pair. A Java line {@code ++ b/scale;} renders as {@code +++ b/scale;} and
     *       does look like half a header — but a file is only ever opened at a line starting
     *       {@code --- a/}, so that line is never a candidate. Weakening the pair check to a
     *       prefix check leaves this case correct, which is how the mutation stayed green.</li>
     *   <li>What the pair check DOES buy is rejecting {@code --- a/X} followed by anything
     *       that is not {@code +++ b/X} — a removed source line of the shape {@code -- a/X}
     *       sitting anywhere in a body.</li>
     * </ul>
     *
     * <p><strong>And the residual ambiguity is named rather than claimed closed:</strong> a
     * removed {@code -- a/X} immediately followed by an added {@code ++ b/X} renders as a
     * byte-identical header pair, and nothing in this format can tell the two apart without
     * an index line the renderer does not emit. The pair check is strictly stronger than a
     * prefix check and costs nothing; it is not a proof.</p>
     *
     * @param unifiedDiff a diff produced by this class; empty is a legitimate input, since
     *                    a move or a rename contributes no diff text
     * @return one row per file, in the order the diff presents them
     */
    public static List<FileSummary> summarize(String unifiedDiff) {
        if (unifiedDiff == null || unifiedDiff.isEmpty()) {
            return List.of();
        }
        List<FileSummary> files = new ArrayList<>();
        String[] lines = unifiedDiff.split("\n", -1);
        String path = null;
        int hunks = 0;
        int added = 0;
        int removed = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String header = headerPathAt(lines, i);
            if (header != null) {
                if (path != null) {
                    files.add(new FileSummary(path, hunks, added, removed));
                }
                path = header;
                hunks = 0;
                added = 0;
                removed = 0;
                i++;                        // the '+++ b/' half of the pair, already read
            } else if (line.startsWith("@@")) {
                hunks++;
            } else if (line.startsWith("+")) {
                added++;
            } else if (line.startsWith("-")) {
                removed++;
            }
        }
        if (path != null) {
            files.add(new FileSummary(path, hunks, added, removed));
        }
        return List.copyOf(files);
    }

    /** The path a header pair names at {@code i}, or null when {@code i} is not one. */
    private static String headerPathAt(String[] lines, int i) {
        if (!lines[i].startsWith("--- a/") || i + 1 >= lines.length) {
            return null;
        }
        String path = lines[i].substring("--- a/".length());
        return lines[i + 1].equals("+++ b/" + path) ? path : null;
    }

    /** Concatenated unified diffs; files with identical content are skipped. */
    public static String unifiedDiff(List<FileDiff> files) {
        StringBuilder sb = new StringBuilder();
        for (FileDiff file : files) {
            sb.append(unifiedDiff(file.filePath(), file.oldContent(), file.newContent()));
        }
        return sb.toString();
    }

    /** Unified diff for one file; empty string when contents are identical. */
    public static String unifiedDiff(String filePath, String oldContent, String newContent) {
        if (Objects.equals(oldContent, newContent)) {
            return "";
        }
        String[] a = splitLines(oldContent);
        String[] b = splitLines(newContent);
        List<Op> ops = diffOps(a, b);
        return renderHunks(filePath, ops);
    }

    // ========== diff computation ==========

    /** type: ' ' keep, '-' delete, '+' add. Indices are 0-based line numbers. */
    private record Op(char type, int aIndex, int bIndex, String line) {}

    private static String[] splitLines(String content) {
        // Splitting on "\n" alone left a trailing \r on every line of a CRLF
        // file, and those bytes went straight into the rendered diff — where a
        // reader cannot see them, so a file appears to differ from itself.
        // Line endings are a host concern; the boundary owns them.
        return org.jawata.core.host.HostText.splitLines(content);
    }

    private static List<Op> diffOps(String[] a, String[] b) {
        int prefix = 0;
        while (prefix < a.length && prefix < b.length && a[prefix].equals(b[prefix])) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.length - prefix && suffix < b.length - prefix
                && a[a.length - 1 - suffix].equals(b[b.length - 1 - suffix])) {
            suffix++;
        }

        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < prefix; i++) {
            ops.add(new Op(' ', i, i, a[i]));
        }

        int aMid = a.length - prefix - suffix;
        int bMid = b.length - prefix - suffix;
        if ((long) aMid * (long) bMid <= MAX_LCS_CELLS) {
            ops.addAll(lcsOps(a, b, prefix, aMid, bMid));
        } else {
            // Degenerate fallback: one replace block for the whole middle.
            for (int i = 0; i < aMid; i++) {
                ops.add(new Op('-', prefix + i, prefix + bMid, a[prefix + i]));
            }
            for (int j = 0; j < bMid; j++) {
                ops.add(new Op('+', prefix + aMid, prefix + j, b[prefix + j]));
            }
        }

        for (int k = 0; k < suffix; k++) {
            int ai = a.length - suffix + k;
            int bi = b.length - suffix + k;
            ops.add(new Op(' ', ai, bi, a[ai]));
        }
        return ops;
    }

    private static List<Op> lcsOps(String[] a, String[] b, int offset, int n, int m) {
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (a[offset + i].equals(b[offset + j])) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }
        List<Op> ops = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a[offset + i].equals(b[offset + j])) {
                ops.add(new Op(' ', offset + i, offset + j, a[offset + i]));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add(new Op('-', offset + i, offset + j, a[offset + i]));
                i++;
            } else {
                ops.add(new Op('+', offset + i, offset + j, b[offset + j]));
                j++;
            }
        }
        while (i < n) {
            ops.add(new Op('-', offset + i, offset + j, a[offset + i]));
            i++;
        }
        while (j < m) {
            ops.add(new Op('+', offset + i, offset + j, b[offset + j]));
            j++;
        }
        return ops;
    }

    // ========== rendering ==========

    private static String renderHunks(String filePath, List<Op> ops) {
        List<int[]> hunkRanges = new ArrayList<>();
        int firstChange = -1;
        int lastChange = -1;
        for (int k = 0; k < ops.size(); k++) {
            if (ops.get(k).type() == ' ') {
                continue;
            }
            if (firstChange == -1) {
                firstChange = k;
            } else if (k - lastChange > 2 * CONTEXT_LINES) {
                hunkRanges.add(hunkRange(ops, firstChange, lastChange));
                firstChange = k;
            }
            lastChange = k;
        }
        if (firstChange == -1) {
            return "";
        }
        hunkRanges.add(hunkRange(ops, firstChange, lastChange));

        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(filePath).append('\n');
        sb.append("+++ b/").append(filePath).append('\n');
        for (int[] range : hunkRanges) {
            appendHunk(sb, ops, range[0], range[1]);
        }
        return sb.toString();
    }

    private static int[] hunkRange(List<Op> ops, int firstChange, int lastChange) {
        return new int[]{
            Math.max(0, firstChange - CONTEXT_LINES),
            Math.min(ops.size() - 1, lastChange + CONTEXT_LINES)
        };
    }

    private static void appendHunk(StringBuilder sb, List<Op> ops, int from, int to) {
        int aLen = 0;
        int bLen = 0;
        for (int k = from; k <= to; k++) {
            char type = ops.get(k).type();
            if (type != '+') aLen++;
            if (type != '-') bLen++;
        }
        Op first = ops.get(from);
        // Unified-diff convention: a zero-length side points at the line
        // BEFORE the hunk (0-based index), a non-empty side at its first
        // line (1-based).
        int aStart = aLen == 0 ? first.aIndex() : first.aIndex() + 1;
        int bStart = bLen == 0 ? first.bIndex() : first.bIndex() + 1;
        sb.append("@@ -").append(aStart).append(',').append(aLen)
          .append(" +").append(bStart).append(',').append(bLen)
          .append(" @@\n");
        for (int k = from; k <= to; k++) {
            Op op = ops.get(k);
            sb.append(op.type()).append(op.line()).append('\n');
        }
    }
}
