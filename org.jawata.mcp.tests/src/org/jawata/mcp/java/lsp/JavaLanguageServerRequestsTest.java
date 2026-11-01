package org.jawata.mcp.java.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 28h S16b: the requests of the language server, one conversation driven in memory. The fixture is {@code Calculator.java} of
 * simple-maven; the editor's unsaved text adds a method whose body is a half-typed call, {@code s.len}, which the saved file does not have.
 */
class JavaLanguageServerRequestsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lsp-requests-test-scheduler");
        t.setDaemon(true);
        return t;
    });
    private static final String TYPED = "    void typed() { String s = \"x\"; s.len }\n";

    private final List<JsonRpcMessage> sent = new CopyOnWriteArrayList<>();
    private JavaLanguageServer server;
    private Path calculator;
    private String saved;

    private void start() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        calculator = helper.getFixturePath("simple-maven").resolve("src/main/java/com/example/Calculator.java");
        saved = Files.readString(calculator);
        server = new JavaLanguageServer(() -> service, EditorTools.over(service, call -> { }), CallerIdentity.editor("requests-test"),
            sent::add, SCHEDULER, 0);
    }

    private String uri() {
        return calculator.toUri().toString();
    }

    private String unsavedText() {
        return saved.substring(0, saved.lastIndexOf('}')) + TYPED + "}\n";
    }

    private void openUnsaved() throws Exception {
        server.receive(JsonRpcMessage.notification("textDocument/didOpen", JSON.valueToTree(Map.of("textDocument",
            Map.of("uri", uri(), "languageId", "java", "version", 1, "text", unsavedText())))));
    }

    private JsonNode answerTo(int id) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < until) {
            for (JsonRpcMessage message : sent) {
                if (message.getMethod() == null && Integer.valueOf(id).equals(message.getId())) {
                    return JSON.valueToTree(message);
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no answer to request " + id + ": " + sent);
    }

    @Test
    @DisplayName("initialize tells the editor it may ask for completion, once the server answers it")
    void completionIsAdvertised() throws Exception {
        start();

        server.receive(JsonRpcMessage.request(1, "initialize", JSON.createObjectNode()));

        JsonNode capabilities = answerTo(1).path("result").path("capabilities");
        assertTrue(capabilities.has("completionProvider"), capabilities.toString());
    }

    @Test
    @DisplayName("completion proposes what the person's unsaved text allows: after 's.len' the String method 'length'")
    void completionOnUnsavedText() throws Exception {
        start();
        openUnsaved();
        String[] lines = unsavedText().split("\n", -1);
        int line = 0;
        while (!lines[line].contains("s.len")) {
            line++;
        }
        int character = lines[line].indexOf("s.len") + "s.len".length();

        server.receive(JsonRpcMessage.request(7, "textDocument/completion", JSON.valueToTree(Map.of(
            "textDocument", Map.of("uri", uri()), "position", Map.of("line", line, "character", character)))));

        JsonNode result = answerTo(7).path("result");
        List<String> labels = new ArrayList<>();
        for (JsonNode item : result.path("items")) {
            labels.add(item.path("label").asText());
        }
        assertTrue(labels.contains("length"), "the proposals after 's.len': " + labels);
        assertEquals(saved, Files.readString(calculator), "the file on disk is as it was");
    }

    @Test
    @DisplayName("completion in a file the engine does not hold answers an empty list, not an error")
    void completionOutsideTheModelIsEmpty() throws Exception {
        start();

        server.receive(JsonRpcMessage.request(8, "textDocument/completion", JSON.valueToTree(Map.of(
            "textDocument", Map.of("uri", "file:///nowhere/Elsewhere.java"), "position", Map.of("line", 0, "character", 0)))));

        JsonNode answer = answerTo(8);
        assertFalse(answer.has("error") && !answer.path("error").isNull(), answer.toString());
        assertEquals(0, answer.path("result").path("items").size());
    }
}
