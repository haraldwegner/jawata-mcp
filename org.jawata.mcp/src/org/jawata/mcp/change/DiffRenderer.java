package org.jawata.mcp.change;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Sprint 14b — minimal unified-diff renderer for refactoring responses.
 *
 * <p>Line-based, 3 context lines, classic {@code --- a/ +++ b/ @@} framing.
 * The middle region (after common prefix/suffix trimming) is diffed with an
 * LCS table when small enough. Beyond {@link #MAX_LCS_CELLS} it is aligned by
 * Myers' shortest-edit search instead, whose cost grows with the number of
 * CHANGED lines rather than with the region; only when even that exceeds
 * {@link #MAX_EDITS} does it degrade to a single whole-region replace hunk —
 * bounded memory on pathological inputs, still a correct (just non-minimal) diff.</p>
 *
 * <p>REL44-15 is why the middle tier exists. Two edits far apart in a large
 * file make a large middle even when almost nothing changed: a staged
 * change_method_signature touching one declaration, one call site and one
 * javadoc line rendered as 1,561 lines added and 1,560 removed, which no one
 * can review — and reviewing is what staging is for.</p>
 */
public final class DiffRenderer {

    private static final int CONTEXT_LINES = 3;
    private static final long MAX_LCS_CELLS = 1_000_000L;

    /**
     * At most this many changed lines are aligned by {@link #myersOps}. Its trace keeps
     * {@code 2d+1} ints for each step {@code d}, so this many steps cost about
     * {@code MAX_EDITS²} ints — the same memory the LCS table is held to.
     */
    private static final int MAX_EDITS = (int) Math.sqrt(MAX_LCS_CELLS);

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

    /**
     * REL44-25 — the lines a diff ADDS that carry a to-do marker, each as
     * {@code "<path>: <line>"}. (Spelled out here rather than written as the marker itself,
     * which the IDE would read as a task in THIS file.)
     *
     * <p>An engine can write a placeholder into the code it changes: JDT's change-signature
     * wrote an {@code @param tally} tag with a to-do marker for its text, and the answer said
     * nothing about it. A caller reviewing the summary cannot see that, so it is read off the
     * diff, which is the one thing every apply-shaped answer already carries.</p>
     *
     * <p>A marker the change only MOVES is not one it wrote: an added line whose text a removed
     * line anywhere in the same diff also carries is cancelled against it, once, so code moved
     * between files is not reported as a new stub.</p>
     *
     * @param unifiedDiff a diff produced by this class
     * @return the added placeholder lines, in diff order; empty when there are none
     */
    public static List<String> addedPlaceholders(String unifiedDiff) {
        if (unifiedDiff == null || unifiedDiff.isEmpty()) {
            return List.of();
        }
        String[] lines = unifiedDiff.split("\n", -1);
        java.util.Map<String, Integer> removed = new java.util.HashMap<>();
        List<String[]> added = new ArrayList<>();
        String path = null;
        for (int i = 0; i < lines.length; i++) {
            String header = headerPathAt(lines, i);
            if (header != null) {
                path = header;
                i++;                        // the '+++ b/' half of the pair, already read
                continue;
            }
            String line = lines[i];
            if (path == null || line.startsWith("@@")) {
                continue;
            }
            if (line.startsWith("-")) {
                removed.merge(line.substring(1).trim(), 1, Integer::sum);
            } else if (line.startsWith("+") && line.contains("TODO")) {
                added.add(new String[] {path, line.substring(1).trim()});
            }
        }
        List<String> out = new ArrayList<>();
        for (String[] candidate : added) {
            Integer moved = removed.get(candidate[1]);
            if (moved != null && moved > 0) {
                removed.put(candidate[1], moved - 1);
                continue;
            }
            out.add(candidate[0] + ": " + candidate[1]);
        }
        return List.copyOf(out);
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
        List<Op> aligned = (long) aMid * (long) bMid <= MAX_LCS_CELLS
            ? lcsOps(a, b, prefix, aMid, bMid)
            : myersOps(a, b, prefix, aMid, bMid);
        if (aligned != null) {
            ops.addAll(aligned);
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

    /**
     * Myers' greedy shortest-edit search over the middle region, for when it is too large
     * for the LCS table (REL44-15).
     *
     * <p>Each step {@code d} records, per diagonal {@code k = x - y}, how far along the old
     * text a path of {@code d} edits reaches; the first step whose path reaches the end of
     * both texts is the shortest edit script, and the recorded steps are walked back to
     * recover it. The ops come out in the same shape {@link #lcsOps} produces — a deletion
     * before the insertion that replaces it — so the hunk rendering cannot tell them apart.</p>
     *
     * @return the ops, or {@code null} when the region differs by more than {@link #MAX_EDITS}
     *     lines, or when the walk back does not account for exactly the region — the caller
     *     then falls back to the whole-region replace, which is never wrong
     */
    private static List<Op> myersOps(String[] a, String[] b, int offset, int n, int m) {
        int max = Math.min(n + m, MAX_EDITS);
        int off = max + 1;
        int[] v = new int[2 * max + 3];
        List<int[]> trace = new ArrayList<>();
        int found = -1;
        int endX = -1;
        int endY = -1;
        search:
        for (int d = 0; d <= max; d++) {
            for (int k = -d; k <= d; k += 2) {
                int x = (k == -d || (k != d && v[off + k - 1] < v[off + k + 1]))
                    ? v[off + k + 1]
                    : v[off + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a[offset + x].equals(b[offset + y])) {
                    x++;
                    y++;
                }
                v[off + k] = x;
                if (x >= n && y >= m) {
                    found = d;
                    endX = x;
                    endY = y;
                    break search;
                }
            }
            trace.add(java.util.Arrays.copyOfRange(v, off - d, off + d + 1));
        }
        if (found < 0 || endX != n || endY != m) {
            return null;
        }

        List<Op> reversed = new ArrayList<>();
        int x = n;
        int y = m;
        for (int d = found; d > 0; d--) {
            int[] prev = trace.get(d - 1);          // diagonals -(d-1) .. d-1
            int k = x - y;
            boolean down = k == -d
                || (k != d && prev[k - 1 + (d - 1)] < prev[k + 1 + (d - 1)]);
            int prevK = down ? k + 1 : k - 1;
            int prevX = prev[prevK + (d - 1)];
            int prevY = prevX - prevK;
            int startX = down ? prevX : prevX + 1;
            int startY = down ? prevY + 1 : prevY;
            while (x > startX && y > startY) {
                x--;
                y--;
                reversed.add(new Op(' ', offset + x, offset + y, a[offset + x]));
            }
            if (prevX < 0 || prevY < 0 || (down ? prevY >= m : prevX >= n)) {
                return null;
            }
            reversed.add(down
                ? new Op('+', offset + prevX, offset + prevY, b[offset + prevY])
                : new Op('-', offset + prevX, offset + prevY, a[offset + prevX]));
            x = prevX;
            y = prevY;
        }
        while (x > 0 && y > 0) {
            x--;
            y--;
            reversed.add(new Op(' ', offset + x, offset + y, a[offset + x]));
        }
        if (x != 0 || y != 0) {
            return null;
        }
        java.util.Collections.reverse(reversed);
        return reversed;
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
