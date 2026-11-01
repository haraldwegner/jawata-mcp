package org.jawata.mcp.tools.shared;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.ltk.core.refactoring.Change;
import org.jawata.mcp.change.ChangeContext;
import org.jawata.mcp.change.ChangeEngine;
import org.jawata.mcp.java.JavaRanges;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The edits a staged refactoring would make, as its answer carries them (Sprint 28h, S16c): for each file the edits with their
 * positions, so an editor can apply them to its own buffers where the engine writes nothing. They are the leaves of the change the
 * engine already computed, so a rename answered to an editor is the rename an agent gets, not a second one.
 */
public final class StagedEdits {

    private StagedEdits() {
    }

    /** Whether the call asks for the edits ({@code edits: true}); a staged call that does is applied by the editor, so the change is not kept. */
    public static boolean requested(JsonNode arguments) {
        return arguments != null && arguments.path("edits").asBoolean(false);
    }

    /**
     * One row per file the change edits, each {@code {filePath, edits: [{line, column, endLine, endColumn, newText}]}}, positions
     * zero-based in the text the change starts from. A file the change only renames or moves has no row.
     */
    public static List<Map<String, Object>> of(Change change, ChangeContext context) {
        List<Map<String, Object>> files = new ArrayList<>();
        for (ChangeEngine.FileEdits file : ChangeEngine.textEdits(change, context)) {
            if (file.edits().isEmpty()) {
                continue;
            }
            JavaRanges.Lines lines = JavaRanges.linesOf(file.content());
            List<Map<String, Object>> edits = new ArrayList<>();
            for (ChangeEngine.SpanEdit edit : file.edits()) {
                Map<String, Object> row = new LinkedHashMap<>(lines.span(edit.offset(), edit.length()).asMap());
                row.put("newText", edit.text());
                edits.add(row);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("filePath", file.file() == null ? file.path() : file.file().toString());
            row.put("edits", edits);
            files.add(row);
        }
        return files;
    }
}
