package org.jawata.mcp.java.lsp;

import static org.jawata.mcp.java.lsp.LspEdits.add;
import static org.jawata.mcp.java.lsp.LspEdits.addStaged;
import static org.jawata.mcp.java.lsp.LspEdits.pathOf;
import static org.jawata.mcp.java.lsp.LspEdits.rangeOf;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.jdt.core.ICompilationUnit;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.engine.ToolNotFoundException;
import org.jawata.mcp.java.JavaRanges;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The language server's code actions: quick fixes, organize imports and the engine's extractions (Sprint 28h, S16e d). */
final class LspCodeActions {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCalls tools;
    private final CallerIdentity caller;
    private final Function<String, ICompilationUnit> unitOf;
    private final Predicate<String> unsaved;

    LspCodeActions(ToolCalls tools, CallerIdentity caller, Function<String, ICompilationUnit> unitOf, Predicate<String> unsaved) {
        this.tools = tools;
        this.caller = caller;
        this.unitOf = unitOf;
        this.unsaved = unsaved;
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
        JsonNode range = params.path("range");
        if (wanted(only, "refactor.extract") && !range.path("start").equals(range.path("end"))) {
            extractions(filePath, range, actions);
        }
        return actions;
    }

    /** What the engine's extract can do with the selected text, each as the edit it stages; a selection it cannot extract offers nothing. */
    private void extractions(String filePath, JsonNode range, List<Object> actions) throws ToolNotFoundException {
        String[][] kinds = {{"variable", "Extract to local variable"}, {"method", "Extract to method"}};
        for (String[] kind : kinds) {
            ObjectNode arguments = JSON.createObjectNode().put("kind", kind[0]).put("filePath", filePath)
                .put("startLine", range.path("start").path("line").asInt()).put("startColumn", range.path("start").path("character").asInt())
                .put("endLine", range.path("end").path("line").asInt()).put("endColumn", range.path("end").path("character").asInt())
                .put("auto_apply", false).put("edits", true);
            if (kind[0].equals("method")) {
                arguments.put("methodName", "extracted");
            }
            ToolResponse staged = tools.callTool("extract", arguments, caller);
            refuseIfBroken(staged);
            if (!staged.isSuccess()) {
                continue;
            }
            Map<String, List<Object>> changes = new LinkedHashMap<>();
            try {
                addStaged(unsaved, staged, changes, "refactoring");
            } catch (IllegalStateException e) {
                continue; // another file the refactoring would change has unsaved text: no action, rather than an error on every cursor move
            }
            if (!changes.isEmpty()) {
                actions.add(Map.of("title", kind[1], "kind", "refactor.extract", "edit", Map.of("changes", changes)));
            }
        }
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
        refuseIfBroken(listed);
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
            refuseIfBroken(applied);
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

    /**
     * A failed answer that says the work itself broke (the search for an import failed) or was cancelled is an error to the editor, never "no
     * actions", which would say there is nothing to offer; any other failed answer is that nothing is offered.
     */
    private static void refuseIfBroken(ToolResponse response) {
        if (response.isSuccess() || response.getError() == null) {
            return;
        }
        String code = response.getError().getCode();
        if ("FIX_SEARCH_FAILED".equals(code) || "OPERATION_CANCELLED".equals(code)) {
            throw new IllegalStateException(response.getError().getMessage());
        }
    }

    private void organizeImports(String filePath, List<Object> actions) throws ToolNotFoundException {
        ToolResponse staged = tools.callTool("organize_imports",
            JSON.createObjectNode().put("scope", "file").put("filePath", filePath).put("auto_apply", false).put("edits", true), caller);
        refuseIfBroken(staged);
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
}
