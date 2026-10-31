package org.jawata.mcp.java.lsp;

import java.util.ArrayList;
import java.util.List;

import org.jawata.mcp.java.JavaRanges;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Applies the edits of a {@code textDocument/didChange} to the text the server holds (Sprint 28h, S16a). An editor sends either the
 * whole new text or, for each edit, a range and what replaces it; the edits of one notification apply in order, each to the text the
 * previous one produced, as the protocol defines.
 *
 * <p>Positions are zero-based lines and UTF-16 columns, which is how {@link JavaRanges} counts, so no conversion is made here: the
 * position becomes an offset through the one locator.</p>
 */
public final class DocumentChanges {

    /** One edit: the range it replaces, or null for a replacement of the whole text, and the text that goes in. */
    public record Change(JavaRanges.Range range, String text) {
    }

    private DocumentChanges() {
    }

    /** The edits of a {@code contentChanges} array. A missing or non-array value has no edits. */
    public static List<Change> parse(JsonNode contentChanges) {
        List<Change> changes = new ArrayList<>();
        if (contentChanges == null || !contentChanges.isArray()) {
            return changes;
        }
        for (JsonNode change : contentChanges) {
            JsonNode range = change.get("range");
            JavaRanges.Range parsed = range == null || range.isNull() ? null : new JavaRanges.Range(
                range.path("start").path("line").asInt(), range.path("start").path("character").asInt(),
                range.path("end").path("line").asInt(), range.path("end").path("character").asInt());
            changes.add(new Change(parsed, change.path("text").asText("")));
        }
        return changes;
    }

    /** The text after every edit has been applied in order. */
    public static String apply(String text, List<Change> changes) {
        String current = text;
        for (Change change : changes) {
            if (change.range() == null) {
                current = change.text();
                continue;
            }
            JavaRanges.Lines lines = JavaRanges.linesOf(current);
            int start = lines.offsetOf(change.range().line(), change.range().column());
            int end = Math.max(start, lines.offsetOf(change.range().endLine(), change.range().endColumn()));
            current = current.substring(0, start) + change.text() + current.substring(end);
        }
        return current;
    }
}
