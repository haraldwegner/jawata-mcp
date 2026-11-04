package org.jawata.mcp.java.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.ICompilationUnit;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.engine.ToolNotFoundException;
import org.jawata.mcp.java.JavaRanges;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The language server's rename requests (Sprint 28h, S16c and S16f 1): the check before a rename and the rename itself. A rename is the
 * rename the agent gets: the gate stages {@code rename_symbol} with {@code edits: true}, and the answer's edits are translated into the
 * protocol's workspace edit. Nothing is written by the server, and the editor applies the edits to its own buffers. A rename that would
 * change a file the editor has open with unsaved text is refused naming that file, because the staged edits are positions in the file's
 * saved text.
 */
final class LspRename {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCalls tools;
    private final CallerIdentity caller;
    private final LspEdits edits;
    private final MovedFileEdits moved;

    LspRename(ToolCalls tools, CallerIdentity caller, LspEdits edits, MovedFileEdits moved) {
        this.tools = tools;
        this.caller = caller;
        this.edits = edits;
        this.moved = moved;
    }

    /**
     * The name under the cursor and its range, which is what the editor selects and then renames; null where the cursor is not on a
     * name the model knows.
     */
    Object prepareRename(JsonNode params) {
        String uri = params.path("textDocument").path("uri").asText();
        ICompilationUnit unit = edits.unit(uri);
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
            return Map.of("range", span.toProtocol(), "placeholder", text.substring(start, end));
        } catch (Exception e) {
            throw new IllegalStateException("the name under the cursor could not be read: " + e.getMessage(), e);
        }
    }

    /** The rename as a workspace edit, from the edits the gate stages; a refused rename is answered with the gate's reason. */
    Object rename(JsonNode params) throws ToolNotFoundException {
        ObjectNode arguments = JSON.createObjectNode()
            .put("filePath", Path.of(URI.create(params.path("textDocument").path("uri").asText())).toString())
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
        edits.addStaged(response, changes, "rename");
        List<Object> renames = new ArrayList<>();
        for (JsonNode rename : data.path("fileRenames")) {
            Path from = Path.of(rename.path("from").asText());
            Path to = Path.of(rename.path("to").asText());
            moved.declaration(from, to, changes, "rename");
            renames.add(Map.of("kind", "rename", "oldUri", from.toUri().toString(), "newUri", to.toUri().toString()));
        }
        if (renames.isEmpty()) {
            return Map.of("changes", changes);
        }
        // a file renamed with its type: the text is edited where it is, then the file is renamed
        List<Object> documents = new ArrayList<>();
        changes.forEach((uri, found) -> documents.add(Map.of("textDocument", Map.of("uri", uri), "edits", found)));
        documents.addAll(renames);
        return Map.of("documentChanges", documents);
    }
}
