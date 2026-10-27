package org.jawata.mcp.change;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 28g S3 / D7f — HOW a diff is presented to a caller, in one place.
 *
 * <p>The field report measured two responses this product sent that the client could not
 * display: a {@code rename_symbol} answered with <strong>167,785 characters</strong> — the
 * full 144-reference diff — which the client spilled to a file, losing the success it was
 * reporting; and a findings page at 86,584. A response whose size defeats the client is a
 * response that did not arrive, however correct it was.</p>
 *
 * <p>So every apply-shaped response now carries a {@code diffSummary} — one row per file
 * with its hunk and line counts — which is the part a caller reads to decide anything, and
 * the diff text itself is capped. Capping without summarising would have been worse than
 * either: a caller would hold a truncated diff and no account of what was cut.</p>
 *
 * <p><strong>The cap is the house figure rather than a new one.</strong> 24,000 characters
 * is what {@code inspect kind=source} already uses, under a comment saying it is what every
 * current client accepts. Inventing a second number for the same question is how two
 * answers to one question start drifting apart.</p>
 *
 * <p>The full text stays available on request ({@code fullDiff: true}), and for a STAGED
 * change it is also still in the change cache, which {@code refactoring action=inspect}
 * reads — so nothing is destroyed by the cap, only left out of the default answer.</p>
 */
public record DiffView(String diff, List<DiffRenderer.FileSummary> files,
                       boolean truncated, int fullLength, List<String> placeholders) {

    /** The house cap, shared with {@code inspect kind=source} rather than chosen again. */
    public static final int DEFAULT_MAX_CHARS = 24_000;

    /**
     * Summarises the diff and caps its text unless the caller asked for all of it.
     *
     * @param diff the rendered unified diff; an empty string is a legitimate input (a move
     *             or a rename contributes no diff text at all, which is
     *             {@link DiffRenderer}'s own documented behaviour)
     * @param full {@code true} when the caller passed {@code fullDiff}
     * @return the view to write into a response
     */
    public static DiffView of(String diff, boolean full) {
        String text = diff == null ? "" : diff;
        List<DiffRenderer.FileSummary> files = DiffRenderer.summarize(text);
        if (full || text.length() <= DEFAULT_MAX_CHARS) {
            return new DiffView(text, files, false, text.length(), DiffRenderer.addedPlaceholders(text));
        }
        return new DiffView(text.substring(0, DEFAULT_MAX_CHARS), files, true, text.length(), DiffRenderer.addedPlaceholders(text));
    }

    /** Reads the caller's {@code fullDiff} flag; absent means capped. */
    public static boolean fullRequested(JsonNode arguments) {
        return arguments != null && arguments.has("fullDiff")
            && arguments.get("fullDiff").asBoolean(false);
    }

    /**
     * Says which files a staged change touches that this diff does not show. A change that CREATES a file (an extracted interface, a
     * generated enum) has no old text to diff, so the file is in the change but not in the diff; the answer then read as an empty diff
     * and the reviewer could not tell a change that does nothing from one that adds a file (Stage 11, dogfood).
     *
     * @param data     the response data to add {@code filesNotInDiff} and its note to; left alone when every file is in the diff
     * @param affected every file the change touches, as {@code ChangeEngine.affectedFilePaths} names them
     */
    public void putUnshownInto(Map<String, Object> data, List<String> affected) {
        List<String> unshown = new ArrayList<>();
        for (String file : affected) {
            if (files.stream().noneMatch(shown -> sameFile(file, shown.filePath()))) {
                unshown.add(file);
            }
        }
        if (!unshown.isEmpty()) {
            data.put("filesNotInDiff", unshown);
            data.put("filesNotInDiffNote", unshown.size() + " file(s) this change creates or moves are not in the diff: there is no"
                + " old text to compare, and their content exists only once the change is applied. Apply to read them; undo removes them.");
        }
    }

    /** Whether two paths, one possibly relative to a project and the other absolute, name the same file. */
    private static boolean sameFile(String a, String b) {
        String x = a.replace('\\', '/');
        String y = b.replace('\\', '/');
        return x.equals(y) || x.endsWith("/" + y) || y.endsWith("/" + x);
    }

    /**
     * Writes {@code diff} and {@code diffSummary} into a response's data map, plus
     * {@code truncated} and {@code diffLength} when the text was capped.
     *
     * <p>{@code truncated} is written ONLY when it is true, which is deliberate: a caller
     * that has to read a boolean to learn nothing happened is a caller doing the tool's
     * work, and the {@code reason} field on a refusal set that precedent in this codebase
     * already.</p>
     */
    public void putInto(Map<String, Object> data) {
        data.put("diff", diff);
        List<Map<String, Object>> summary = new ArrayList<>();
        for (DiffRenderer.FileSummary file : files) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("filePath", file.filePath());
            row.put("hunks", file.hunks());
            row.put("added", file.added());
            row.put("removed", file.removed());
            summary.add(row);
        }
        data.put("diffSummary", summary);
        // REL44-25: a placeholder the engine wrote is SAID, not left to be found in the diff —
        // and read off the WHOLE diff, so a cut text still reports what lies below the cut.
        if (!placeholders.isEmpty()) {
            data.put("placeholdersAdded", placeholders);
            data.put("placeholdersNote", placeholders.size() + " line(s) this change adds carry"
                + " a to-do marker the engine wrote (an empty javadoc tag, a generated stub)."
                + " They compile; they are not finished. Fill or remove each before keeping it.");
        }
        if (truncated) {
            data.put("truncated", true);
            data.put("diffLength", fullLength);
            data.put("diffNote", "The diff is " + fullLength + " characters and was cut at "
                + DEFAULT_MAX_CHARS + ". diffSummary accounts for ALL " + files.size()
                + " file(s), including the ones below the cut. Pass fullDiff:true for the "
                + "whole text, or read a staged change with refactoring action=inspect.");
        }
    }
}
