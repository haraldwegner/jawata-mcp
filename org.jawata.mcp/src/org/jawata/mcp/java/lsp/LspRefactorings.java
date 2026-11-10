package org.jawata.mcp.java.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.engine.ToolNotFoundException;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The refactorings of the {@link RefactoringCatalogue} as the editor meets them (Sprint 28i, Stage 25, step A5): {@link #offered} adds, at the
 * cursor, each one that applies, and {@link #stage} stages one that needed the person's answers first. Each is staged with {@code auto_apply=false}
 * and answered as a workspace edit, as rename is, so the change is the editor's to apply and to undo.
 */
final class LspRefactorings {

    /** The command an editor runs for a refactoring that needs the person's answers first; its argument says what to ask and what to send back. */
    static final String COMMAND = "jawata.refactor";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCalls tools;
    private final CallerIdentity caller;
    private final LspEdits lspEdits;

    LspRefactorings(ToolCalls tools, CallerIdentity caller, LspEdits lspEdits) {
        this.tools = tools;
        this.caller = caller;
        this.lspEdits = lspEdits;
    }

    /**
     * Adds to {@code actions} the refactorings that apply at the cursor: one that needs nothing more is staged and offered as the edit it makes,
     * one that needs the person's answers is offered as a command carrying what to ask. A refactoring the engine refuses at this place is not offered.
     *
     * @param asked whether the editor asked for a code action kind
     */
    void offered(String uri, String filePath, JsonNode position, Predicate<String> asked, List<Object> actions) throws ToolNotFoundException {
        int line = position.path("line").asInt();
        int column = position.path("character").asInt();
        String element = elementAt(filePath, line, column);
        if (element == null) {
            return; // the engine does not say what is here, and staging every refactoring to find out would cost one call each on every cursor move
        }
        for (RefactoringCatalogue.Entry entry : RefactoringCatalogue.entries()) {
            if (!asked.test(entry.kind()) || !entry.appliesTo().contains(element)) {
                continue;
            }
            if (entry.needsInput()) {
                actions.add(Map.of("title", entry.title(), "kind", entry.kind(),
                    "command", Map.of("title", entry.title(), "command", COMMAND, "arguments", List.of(askingFor(entry, uri, line, column)))));
                continue;
            }
            ToolResponse staged = tools.callTool(entry.tool(), argumentsOf(entry, filePath, line, column, JSON.createObjectNode()), caller);
            if (!staged.isSuccess()) {
                continue;
            }
            Map<String, List<Object>> changes = new LinkedHashMap<>();
            try {
                lspEdits.addStaged(staged, changes, "refactoring");
            } catch (IllegalStateException e) {
                continue; // another file it would change has unsaved text: no action, rather than an error on every cursor move
            }
            if (!changes.isEmpty()) {
                actions.add(Map.of("title", entry.title(), "kind", entry.kind(), "edit", Map.of("changes", changes)));
            }
        }
    }

    /**
     * Stages the refactoring the editor ran with the answers the person gave and answers the workspace edit that makes it.
     *
     * @param params {@code id}, the document {@code uri}, the cursor {@code line} and {@code column}, and {@code inputs}, a map from each asked name to its answer
     */
    Object stage(JsonNode params) throws ToolNotFoundException {
        String id = params.path("id").asText();
        RefactoringCatalogue.Entry entry = RefactoringCatalogue.byId(id)
            .orElseThrow(() -> new IllegalArgumentException("no refactoring is offered under the id '" + id + "'"));
        String uri = params.path("uri").asText();
        Path file = Path.of(URI.create(uri));
        lspEdits.refuseIfUnsaved(uri, file, entry.title());
        JsonNode inputs = params.path("inputs");
        for (RefactoringCatalogue.Input input : entry.inputs()) {
            if (!inputs.path(input.name()).isTextual() || inputs.path(input.name()).asText().isBlank()) {
                throw new IllegalArgumentException(entry.title() + " needs an answer to: " + input.prompt());
            }
        }
        ToolResponse staged = tools.callTool(entry.tool(),
            argumentsOf(entry, file.toString(), params.path("line").asInt(), params.path("column").asInt(), inputs), caller);
        if (!staged.isSuccess()) {
            throw new IllegalStateException(entry.title() + " was refused: " + (staged.getError() == null ? "no reason given" : staged.getError().getMessage()));
        }
        Map<String, List<Object>> changes = new LinkedHashMap<>();
        lspEdits.addStaged(staged, changes, entry.title());
        return Map.of("changes", changes);
    }

    private static Map<String, Object> askingFor(RefactoringCatalogue.Entry entry, String uri, int line, int column) {
        List<Map<String, Object>> questions = new ArrayList<>();
        for (RefactoringCatalogue.Input input : entry.inputs()) {
            questions.add(Map.of("name", input.name(), "prompt", input.prompt(), "choices", input.choices()));
        }
        return Map.of("id", entry.id(), "uri", uri, "line", line, "column", column, "inputs", questions);
    }

    private static ObjectNode argumentsOf(RefactoringCatalogue.Entry entry, String filePath, int line, int column, JsonNode inputs) {
        ObjectNode arguments = JSON.createObjectNode();
        entry.fixed().forEach((name, value) -> arguments.set(name, JSON.valueToTree(value)));
        arguments.put("filePath", filePath).put("line", line).put("column", column).put("auto_apply", false).put("edits", true);
        for (RefactoringCatalogue.Input input : entry.inputs()) {
            arguments.put(input.name(), inputs.path(input.name()).asText());
        }
        return arguments;
    }

    /**
     * What kind of code element the cursor is on, from the engine's own answer: {@code method}, {@code type}, {@code field} or {@code variable};
     * null when the engine does not say, which offers nothing.
     */
    private String elementAt(String filePath, int line, int column) throws ToolNotFoundException {
        ToolResponse answer = tools.callTool("get_at_position",
            JSON.createObjectNode().put("kind", "symbol").put("filePath", filePath).put("line", line).put("column", column), caller);
        if (!answer.isSuccess()) {
            return null;
        }
        JsonNode data = JSON.valueToTree(answer.getData());
        for (String field : List.of("kind", "symbolKind", "elementKind")) {
            String said = data.path(field).asText("").toLowerCase(Locale.ROOT);
            if (said.contains("method") || said.contains("constructor")) {
                return "method";
            }
            if (said.contains("class") || said.contains("interface") || said.contains("enum") || said.contains("record") || said.equals("type")) {
                return "type";
            }
            if (said.contains("field")) {
                return "field";
            }
            if (said.contains("variable") || said.contains("local") || said.contains("parameter")) {
                return "variable";
            }
        }
        return null;
    }
}
