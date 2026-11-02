package org.jawata.mcp.java.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.engine.ToolNotFoundException;
import org.jawata.mcp.java.JavaRanges;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.shared.SourceFormatting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The language server's editing requests (Sprint 28h, S16c): rename and the check before it. A rename is the rename the agent gets:
 * the gate stages {@code rename_symbol} with {@code edits: true}, and the answer's edits are translated into the protocol's workspace
 * edit. Nothing is written by the server, and the editor applies the edit to its own buffers.
 *
 * <p>A rename that would change a file the editor has open with unsaved text is refused naming that file: the staged edits are
 * positions in the file's saved text, so applying them over the typed text would land in the wrong places. The person saves or closes
 * the file first, the same rule the write guard applies when a tool writes disk.</p>
 */
final class LspEditing {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCalls tools;
    private final CallerIdentity caller;
    private final Function<String, ICompilationUnit> unitOf;
    private final Predicate<String> unsaved;

    /**
     * @param unitOf  the unit a request reads for a document: its working copy when open, else the saved unit; null for a file the model
     *                does not hold
     * @param unsaved whether the document has text in the editor that is not saved
     */
    LspEditing(ToolCalls tools, CallerIdentity caller, Function<String, ICompilationUnit> unitOf, Predicate<String> unsaved) {
        this.tools = tools;
        this.caller = caller;
        this.unitOf = unitOf;
        this.unsaved = unsaved;
    }

    /**
     * The name under the cursor and its range, which is what the editor selects and then renames; null where the cursor is not on a
     * name the model knows.
     */
    Object prepareRename(JsonNode params) {
        String uri = params.path("textDocument").path("uri").asText();
        ICompilationUnit unit = unitOf.apply(uri);
        if (unit == null) {
            return null;
        }
        try {
            String text = unit.getSource();
            JavaRanges.Lines lines = JavaRanges.linesOf(text);
            int offset = lines.offsetOf(params.path("position").path("line").asInt(), params.path("position").path("character").asInt());
            int start = offset;
            while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
                start--;
            }
            int end = offset;
            while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
                end++;
            }
            if (start == end || !Character.isJavaIdentifierStart(text.charAt(start)) || unit.codeSelect(start, end - start).length == 0) {
                return null;
            }
            JavaRanges.Range span = lines.span(start, end - start);
            return Map.of("range", Map.of("start", Map.of("line", span.line(), "character", span.column()),
                    "end", Map.of("line", span.endLine(), "character", span.endColumn())),
                "placeholder", text.substring(start, end));
        } catch (Exception e) {
            throw new IllegalStateException("the name under the cursor could not be read: " + e.getMessage(), e);
        }
    }

    /** The rename as a workspace edit, from the edits the gate stages; a refused rename is answered with the gate's reason. */
    Object rename(JsonNode params) throws ToolNotFoundException {
        ObjectNode arguments = JSON.createObjectNode()
            .put("filePath", pathOf(params.path("textDocument").path("uri").asText()).toString())
            .put("line", params.path("position").path("line").asInt())
            .put("column", params.path("position").path("character").asInt())
            .put("newName", params.path("newName").asText())
            .put("auto_apply", false)
            .put("edits", true);
        ToolResponse response = tools.callTool("rename_symbol", arguments, caller);
        if (!response.isSuccess()) {
            throw new IllegalStateException("The rename was refused: " + response);
        }
        JsonNode data = JSON.valueToTree(response.getData());
        Map<String, List<Object>> changes = new LinkedHashMap<>();
        List<Object> documents = new ArrayList<>();
        for (JsonNode file : data.path("edits")) {
            Path path = Path.of(file.path("filePath").asText());
            String uri = path.toUri().toString();
            if (unsaved.test(uri)) {
                throw new IllegalStateException("The rename changes " + path.getFileName() + ", which has unsaved text in the editor: "
                    + "save or close it first. Nothing was changed.");
            }
            List<Object> textEdits = new ArrayList<>();
            for (JsonNode edit : file.path("edits")) {
                textEdits.add(Map.of("range", rangeOf(edit), "newText", edit.path("newText").asText()));
            }
            changes.put(uri, textEdits);
            documents.add(Map.of("textDocument", Map.of("uri", uri), "edits", textEdits));
        }
        List<Object> renames = new ArrayList<>();
        for (JsonNode rename : data.path("fileRenames")) {
            renames.add(Map.of("kind", "rename", "oldUri", Path.of(rename.path("from").asText()).toUri().toString(),
                "newUri", Path.of(rename.path("to").asText()).toUri().toString()));
        }
        if (renames.isEmpty()) {
            return Map.of("changes", changes);
        }
        // a file renamed with its type: the text is edited where it is, then the file is renamed
        documents.addAll(renames);
        return Map.of("documentChanges", documents);
    }

    /** The edits that lay the whole document out as the project's formatter settings say; none when it is laid out already. */
    Object formatting(JsonNode params) {
        return formatted(params.path("textDocument").path("uri").asText(), null);
    }

    /** The same for the lines the editor selected: only the text in the range is touched. */
    Object rangeFormatting(JsonNode params) {
        return formatted(params.path("textDocument").path("uri").asText(), params.path("range"));
    }

    private List<Object> formatted(String uri, JsonNode range) {
        ICompilationUnit unit = unitOf.apply(uri);
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
    private static void collect(TextEdit edit, String text, JavaRanges.Lines lines, List<Object> out) {
        if (edit.hasChildren()) {
            for (TextEdit child : edit.getChildren()) {
                collect(child, text, lines, out);
            }
            return;
        }
        if (edit instanceof ReplaceEdit replace) {
            add(out, text, lines, replace.getOffset(), replace.getLength(), replace.getText());
        } else if (edit instanceof InsertEdit insert) {
            add(out, text, lines, insert.getOffset(), 0, insert.getText());
        } else if (edit instanceof DeleteEdit delete) {
            add(out, text, lines, delete.getOffset(), delete.getLength(), "");
        }
    }

    private static void add(List<Object> out, String text, JavaRanges.Lines lines, int offset, int length, String newText) {
        if (text.startsWith(newText, offset) && newText.length() == length) {
            return;
        }
        JavaRanges.Range span = lines.span(offset, length);
        out.add(Map.of("range", Map.of("start", Map.of("line", span.line(), "character", span.column()),
                "end", Map.of("line", span.endLine(), "character", span.endColumn())),
            "newText", newText));
    }

    private static final int MOST_QUICK_FIXES = 8;

    /**
     * The fixes the editor can offer at the cursor: the quick fixes of its line, each as the edits the gate computes for it, and the
     * import organizer when it would change the file. A file with unsaved editor text is offered none, because the gate reads the saved
     * text and its positions would not fit the typed one.
     */
    Object codeAction(JsonNode params) throws ToolNotFoundException {
        String uri = params.path("textDocument").path("uri").asText();
        ICompilationUnit unit = unitOf.apply(uri);
        List<Object> actions = new ArrayList<>();
        if (unit == null || unsaved.test(uri)) {
            return actions;
        }
        JsonNode only = params.path("context").path("only");
        String filePath = pathOf(uri).toString();
        if (wanted(only, "quickfix")) {
            quickFixes(uri, filePath, unit, params.path("range").path("start").path("line").asInt(), actions);
        }
        if (wanted(only, "source.organizeImports")) {
            organizeImports(filePath, actions);
        }
        return actions;
    }

    /** Whether the editor asked for this kind of action, or for every kind. */
    private static boolean wanted(JsonNode only, String kind) {
        if (!only.isArray() || only.isEmpty()) {
            return true;
        }
        for (JsonNode asked : only) {
            if (kind.equals(asked.asText()) || kind.startsWith(asked.asText() + ".")) {
                return true;
            }
        }
        return false;
    }

    private void quickFixes(String uri, String filePath, ICompilationUnit unit, int line, List<Object> actions) throws ToolNotFoundException {
        ToolResponse listed = tools.callTool("quick_fix",
            JSON.createObjectNode().put("action", "list").put("filePath", filePath).put("line", line), caller);
        if (!listed.isSuccess()) {
            return;
        }
        String text;
        try {
            text = unit.getSource();
        } catch (Exception e) {
            throw new IllegalStateException("the text of the document could not be read: " + e.getMessage(), e);
        }
        JavaRanges.Lines lines = JavaRanges.linesOf(text);
        int offered = 0;
        for (JsonNode fix : JSON.valueToTree(listed.getData()).path("fixes")) {
            if (offered == MOST_QUICK_FIXES) {
                break;
            }
            ToolResponse applied = tools.callTool("quick_fix", JSON.createObjectNode().put("action", "apply").put("filePath", filePath)
                .put("fixId", fix.path("fixId").asText()).put("line", line), caller);
            if (!applied.isSuccess()) {
                continue;
            }
            List<Object> edits = new ArrayList<>();
            for (JsonNode edit : JSON.valueToTree(applied.getData()).path("edits")) {
                int start = edit.has("offset") ? edit.path("offset").asInt() : edit.path("startOffset").asInt();
                int end = edit.has("endOffset") ? edit.path("endOffset").asInt() : start;
                add(edits, text, lines, start, end - start, edit.path("newText").asText(""));
            }
            if (!edits.isEmpty()) {
                actions.add(Map.of("title", fix.path("label").asText(), "kind", "quickfix", "edit", Map.of("changes", Map.of(uri, edits))));
                offered++;
            }
        }
    }

    private void organizeImports(String filePath, List<Object> actions) throws ToolNotFoundException {
        ToolResponse staged = tools.callTool("organize_imports",
            JSON.createObjectNode().put("scope", "file").put("filePath", filePath).put("auto_apply", false).put("edits", true), caller);
        if (!staged.isSuccess()) {
            return;
        }
        Map<String, List<Object>> changes = new LinkedHashMap<>();
        for (JsonNode file : JSON.valueToTree(staged.getData()).path("edits")) {
            List<Object> edits = new ArrayList<>();
            for (JsonNode edit : file.path("edits")) {
                edits.add(Map.of("range", rangeOf(edit), "newText", edit.path("newText").asText()));
            }
            if (!edits.isEmpty()) {
                changes.put(Path.of(file.path("filePath").asText()).toUri().toString(), edits);
            }
        }
        if (!changes.isEmpty()) {
            actions.add(Map.of("title", "Organize imports", "kind", "source.organizeImports", "edit", Map.of("changes", changes)));
        }
    }

    private static Map<String, Object> rangeOf(JsonNode edit) {
        return Map.of("start", Map.of("line", edit.path("line").asInt(), "character", edit.path("column").asInt()),
            "end", Map.of("line", edit.path("endLine").asInt(), "character", edit.path("endColumn").asInt()));
    }

    private static Path pathOf(String uri) {
        return Path.of(URI.create(uri));
    }
}
