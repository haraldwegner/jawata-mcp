package org.jawata.mcp.java.lsp;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.jawata.mcp.java.JavaRanges;
import org.jawata.mcp.tools.shared.SourceFormatting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The language server's formatting requests: the whole document, a range, and the line just typed (Sprint 28h, S16e d). */
final class LspFormatting {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LspEdits lspEdits;

    LspFormatting(LspEdits lspEdits) {
        this.lspEdits = lspEdits;
    }

    /** The edits that lay the whole document out as the project's formatter settings say; none when it is laid out already. */
    Object formatting(JsonNode params) {
        return formatted(params.path("textDocument").path("uri").asText(), null);
    }

    /** The same for the lines the editor selected: only the text in the range is touched. */
    Object rangeFormatting(JsonNode params) {
        return formatted(params.path("textDocument").path("uri").asText(), params.path("range"));
    }

    /** The layout of the line just typed: the line a closing brace or a semicolon ends, or the one a new line follows. */
    Object onTypeFormatting(JsonNode params) {
        int line = params.path("position").path("line").asInt();
        if ("\n".equals(params.path("ch").asText()) && line > 0) {
            line--;
        }
        ObjectNode range = JSON.createObjectNode();
        range.putObject("start").put("line", line).put("character", 0);
        range.putObject("end").put("line", line + 1).put("character", 0);
        return formatted(params.path("textDocument").path("uri").asText(), range);
    }

    private List<Object> formatted(String uri, JsonNode range) {
        ICompilationUnit unit = lspEdits.unit(uri);
        if (unit == null) {
            return List.of();
        }
        try {
            String text = unit.getSource();
            JavaRanges.Lines lines = JavaRanges.linesOf(text);
            int from = range == null ? 0 : lines.offsetOf(range.path("start").path("line").asInt(), range.path("start").path("character").asInt());
            int to = range == null ? text.length() : lines.offsetOf(range.path("end").path("line").asInt(), range.path("end").path("character").asInt());
            TextEdit edit = SourceFormatting.editFor(unit, text, from, to - from, text.contains("\r\n") ? "\r\n" : "\n");
            List<Object> edits = new ArrayList<>();
            if (edit != null) {
                collect(edit, text, lines, edits);
            }
            return edits;
        } catch (Exception e) {
            throw new IllegalStateException("the document could not be formatted: " + e.getMessage(), e);
        }
    }

    /** The leaves of a formatter edit tree as protocol edits, in the text they were computed on; a leaf that changes nothing is left out. */
    private void collect(TextEdit edit, String text, JavaRanges.Lines lines, List<Object> out) {
        if (edit.hasChildren()) {
            for (TextEdit child : edit.getChildren()) {
                collect(child, text, lines, out);
            }
            return;
        }
        if (edit instanceof ReplaceEdit replace) {
            lspEdits.add(out, text, lines, replace.getOffset(), replace.getLength(), replace.getText());
        } else if (edit instanceof InsertEdit insert) {
            lspEdits.add(out, text, lines, insert.getOffset(), 0, insert.getText());
        } else if (edit instanceof DeleteEdit delete) {
            lspEdits.add(out, text, lines, delete.getOffset(), delete.getLength(), "");
        }
    }
}
