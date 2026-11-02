package org.jawata.mcp.java.lsp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.mcp.tools.FindRefsTool;
import org.jawata.mcp.tools.GetAtPositionTool;
import org.jawata.mcp.tools.GetCallHierarchyTool;
import org.jawata.mcp.tools.GoToDefinitionTool;
import org.jawata.mcp.tools.InspectTool;
import org.jawata.mcp.tools.RenameSymbolTool;
import org.jawata.mcp.tools.SearchSymbolsTool;
import org.jawata.mcp.tools.Tool;
import org.jawata.resident.workspace.JdtServiceImpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 28h S16b test support: one conversation with the language server, driven in memory. The server answers through a stand-in gate made of
 * the real door tools over the given service, as the editor's own caller; a request is put and its answer waited for, so a table row
 * is one line of what is asked and one of what is expected.
 */
final class LspSession {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lsp-session-scheduler");
        t.setDaemon(true);
        return t;
    });

    private final List<JsonRpcMessage> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final JavaLanguageServer server;

    LspSession(JdtServiceImpl service, String editor) {
        server = new JavaLanguageServer(() -> service, doors(service), CallerIdentity.editor(editor), sent::add, SCHEDULER, 0);
    }

    /** The doors the navigation requests are answered by, as the gate publishes them. */
    private static ToolCalls doors(JdtServiceImpl service) {
        Map<String, Tool> byName = Map.of(
            "get_at_position", new GetAtPositionTool(() -> service),
            "go_to_definition", new GoToDefinitionTool(() -> service),
            "find_references", new FindRefsTool(() -> service),
            "inspect", new InspectTool(() -> service),
            "get_call_hierarchy", new GetCallHierarchyTool(() -> service),
            "search_symbols", new SearchSymbolsTool(() -> service),
            "rename_symbol", new RenameSymbolTool(() -> service, new RefactoringChangeCache(), () -> null),
            "quick_fix", new org.jawata.mcp.tools.QuickFixTool(() -> service),
            "organize_imports", new org.jawata.mcp.tools.OrganizeImportsTool(() -> service, new RefactoringChangeCache()),
            "move", new org.jawata.mcp.tools.MoveTool(() -> service, new RefactoringChangeCache()));
        return new ToolCalls() {
            @Override
            public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                Tool tool = byName.get(name);
                if (tool == null) {
                    throw new IllegalArgumentException("this test gate publishes no door " + name);
                }
                return tool.execute(arguments);
            }

            @Override
            public List<Map<String, Object>> getToolDefinitions() {
                List<Map<String, Object>> definitions = new ArrayList<>();
                byName.keySet().forEach(name -> definitions.add(Map.of("name", name)));
                return definitions;
            }
        };
    }

    /** The editor opens a file with this text. */
    void open(Path file, String text) throws Exception {
        server.receive(JsonRpcMessage.notification("textDocument/didOpen", JSON.valueToTree(Map.of("textDocument",
            Map.of("uri", file.toUri().toString(), "languageId", "java", "version", 1, "text", text)))));
    }

    /** Puts a request and returns the result of its answer; an answer that is an error fails the row. */
    JsonNode ask(String method, Map<String, Object> params) throws Exception {
        JsonNode answer = answerTo(method, params);
        assertTrue(answer.path("error").isMissingNode() || answer.path("error").isNull(), answer.toString());
        return answer.path("result");
    }

    /** Puts a request that is expected to be refused and returns the message of the error it was answered with. */
    String refusalOf(String method, Map<String, Object> params) throws Exception {
        JsonNode answer = answerTo(method, params);
        assertTrue(answer.path("error").isObject(), "the request was expected to be refused: " + answer);
        return answer.path("error").path("message").asText();
    }

    /** Puts a request and waits for the whole answer to it, result or error. */
    private JsonNode answerTo(String method, Map<String, Object> params) throws Exception {
        int id = ids.incrementAndGet();
        server.receive(JsonRpcMessage.request(id, method, JSON.valueToTree(params)));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < until) {
            for (JsonRpcMessage message : sent) {
                if (message.getMethod() == null && Integer.valueOf(id).equals(message.getId())) {
                    return JSON.valueToTree(message);
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no answer to " + method + ": " + sent);
    }

    /** A request about the text at a position of a file. */
    static Map<String, Object> at(Path file, int line, int character) {
        return Map.of("textDocument", Map.of("uri", file.toUri().toString()), "position", Map.of("line", line, "character", character));
    }

    /** Zero-based line of the first line of {@code text} that holds {@code marker}. */
    static int lineOf(String text, String marker) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                return i;
            }
        }
        throw new AssertionError("no line holds " + marker);
    }

    /** The column {@code inside} characters into the first occurrence of {@code token} on the line of {@code text} that holds {@code lineMarker}. */
    static int columnOf(String text, String lineMarker, String token, int inside) {
        return text.split("\n", -1)[lineOf(text, lineMarker)].indexOf(token) + inside;
    }

    /** A request at the name {@code name} on the line of {@code text} that holds {@code lineMarker}, one character into the name. */
    static Map<String, Object> onName(Path file, String text, String lineMarker, String name) {
        return at(file, lineOf(text, lineMarker), columnOf(text, lineMarker, name, 1));
    }

    /** The names of the items of an answer; {@code side} names the member that holds the item when each answer wraps one. */
    static List<String> namesOf(JsonNode answers, String side) {
        List<String> names = new ArrayList<>();
        for (JsonNode answer : answers) {
            names.add((side.isEmpty() ? answer : answer.path(side)).path("name").asText());
        }
        return names;
    }

    /** The start lines of the locations of an answer. */
    static List<Integer> startLinesOf(JsonNode locations) {
        List<Integer> lines = new ArrayList<>();
        for (JsonNode location : locations) {
            lines.add(location.path("range").path("start").path("line").asInt());
        }
        return lines;
    }

    /** An item of an answer, as the next request carries it. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> asParams(JsonNode item) {
        return Map.of("item", JSON.convertValue(item, Map.class));
    }
}
