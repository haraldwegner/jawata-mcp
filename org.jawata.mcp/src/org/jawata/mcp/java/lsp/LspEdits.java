package org.jawata.mcp.java.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.jawata.mcp.java.JavaRanges;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What the language server's editing requests share (Sprint 28h, S16e d): a change to a text as a protocol edit, a staged change of the
 * engine as protocol edits, the check that refuses a file with unsaved editor text, and the conversions between a document's uri and its path.
 */
final class LspEdits {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LspEdits() { }

    static void add(List<Object> out, String text, JavaRanges.Lines lines, int offset, int length, String newText) {
        if (text.startsWith(newText, offset) && newText.length() == length) {
            return;
        }
        JavaRanges.Range span = lines.span(offset, length);
        out.add(Map.of("range", Map.of("start", Map.of("line", span.line(), "character", span.column()),
                "end", Map.of("line", span.endLine(), "character", span.endColumn())),
            "newText", newText));
    }

    static Map<String, Object> rangeOf(JsonNode edit) {
        return Map.of("start", Map.of("line", edit.path("line").asInt(), "character", edit.path("column").asInt()),
            "end", Map.of("line", edit.path("endLine").asInt(), "character", edit.path("endColumn").asInt()));
    }

    static Path pathOf(String uri) {
        return Path.of(URI.create(uri));
    }

    /** Refuses a change to a file the editor holds unsaved text for, naming the file, because the positions answered are in the saved text. */
    static void refuseIfUnsaved(Predicate<String> unsaved, String uri, Path file, String what) {
        if (unsaved.test(uri)) {
            throw new IllegalStateException("The " + what + " changes " + file.getFileName() + ", which has unsaved text in the editor: "
                + "save or close it first. Nothing was changed.");
        }
    }

    /** Adds the staged edits of a change to the edits answered, refusing when one of the files has unsaved editor text. */
    static void addStaged(Predicate<String> unsaved, ToolResponse staged, Map<String, List<Object>> changes, String what) {
        for (JsonNode file : JSON.valueToTree(staged.getData()).path("edits")) {
            Path path = Path.of(file.path("filePath").asText());
            String uri = path.toUri().toString();
            refuseIfUnsaved(unsaved, uri, path, what);
            List<Object> edits = changes.computeIfAbsent(uri, key -> new ArrayList<>());
            for (JsonNode edit : file.path("edits")) {
                edits.add(Map.of("range", rangeOf(edit), "newText", edit.path("newText").asText()));
            }
        }
    }
}
