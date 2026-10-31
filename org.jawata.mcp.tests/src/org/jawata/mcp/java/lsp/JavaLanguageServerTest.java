package org.jawata.mcp.java.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.ICompilationUnit;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 28h S16a: one language-server conversation, driven by messages in memory (no stream, no HTTP): the lifecycle, the errors, and the
 * document sync that puts an editor's unsaved text into the engine's buffers. The fixture is {@code Calculator.java} of simple-maven.
 */
class JavaLanguageServerTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final ObjectMapper JSON = new ObjectMapper();

    private JdtServiceImpl service;
    private Path calculator;
    private String saved;
    private final List<JsonRpcMessage> sent = new ArrayList<>();
    private JavaLanguageServer server;

    private void start() throws Exception {
        service = helper.loadProject("simple-maven");
        calculator = helper.getFixturePath("simple-maven").resolve("src/main/java/com/example/Calculator.java");
        saved = Files.readString(calculator);
        server = new JavaLanguageServer(() -> service, sent::add);
    }

    private static JsonRpcMessage request(int id, String method, String paramsJson) throws Exception {
        return JsonRpcMessage.request(id, method, JSON.readTree(paramsJson));
    }

    private static JsonRpcMessage notice(String method, String paramsJson) throws Exception {
        return JsonRpcMessage.notification(method, JSON.readTree(paramsJson));
    }

    private String uri() {
        return calculator.toUri().toString();
    }

    private ICompilationUnit unit() {
        return service.getCompilationUnit(calculator).getPrimary();
    }

    private String textOfBuffer() throws Exception {
        return service.buffers().orElseThrow().view(unit()).orElseThrow().getSource();
    }

    @Test
    @DisplayName("initialize answers the capabilities the server has: incremental document sync, nothing it cannot answer yet")
    void initializeAnswersCapabilities() throws Exception {
        start();

        assertTrue(server.receive(request(1, "initialize", "{}")));

        JsonNode result = JSON.valueToTree(sent.get(0).getResult());
        assertEquals(1, sent.get(0).getId());
        assertTrue(result.path("capabilities").path("textDocumentSync").path("openClose").asBoolean());
        assertEquals(2, result.path("capabilities").path("textDocumentSync").path("change").asInt(), "incremental");
        assertFalse(result.path("capabilities").has("hoverProvider"), "only what is answered is advertised");
    }

    @Test
    @DisplayName("a request the server does not know is answered with the method-not-found error, and a notification it does not know is ignored")
    void unknownMethods() throws Exception {
        start();

        server.receive(request(7, "textDocument/noSuchThing", "{}"));
        server.receive(notice("$/someNotification", "{}"));

        assertEquals(1, sent.size(), "one reply, for the request");
        assertEquals(JavaLanguageServer.METHOD_NOT_FOUND, sent.get(0).getError().getCode());
        assertEquals(7, sent.get(0).getId());
    }

    @Test
    @DisplayName("shutdown is answered with a null result, a request after it is refused, and exit ends the conversation")
    void shutdownThenExit() throws Exception {
        start();

        server.receive(request(2, "shutdown", "null"));
        assertTrue(sent.get(0).isResponse(), "a null result is a response");
        assertEquals(2, sent.get(0).getId());

        server.receive(request(3, "initialize", "{}"));
        assertEquals(JavaLanguageServer.INVALID_REQUEST, sent.get(1).getError().getCode());

        assertFalse(server.receive(JsonRpcMessage.notification("exit", null)), "the conversation is over");
    }

    @Test
    @DisplayName("didOpen puts the editor's text into the engine's buffers, didChange applies its edits to it, and didClose gives the saved file back")
    void documentSync() throws Exception {
        start();
        String open = JSON.writeValueAsString(Map.of("textDocument", Map.of("uri", uri(), "languageId", "java", "version", 1, "text", saved)));
        server.receive(notice("textDocument/didOpen", open));
        assertEquals(saved, textOfBuffer());

        String change = JSON.writeValueAsString(Map.of("textDocument", Map.of("uri", uri(), "version", 2),
            "contentChanges", List.of(Map.of("range", Map.of("start", Map.of("line", 0, "character", 0), "end", Map.of("line", 0, "character", 0)),
                "text", "// typed\n"))));
        server.receive(notice("textDocument/didChange", change));
        assertTrue(textOfBuffer().startsWith("// typed\npackage com.example;"), textOfBuffer().substring(0, 40));
        assertTrue(service.buffers().orElseThrow().isDirty(unit()));

        server.receive(notice("textDocument/didClose", JSON.writeValueAsString(Map.of("textDocument", Map.of("uri", uri())))));
        assertFalse(service.buffers().orElseThrow().view(unit()).isPresent());
        assertEquals(saved, unit().getSource(), "the saved file is the text again");
    }

    @Test
    @DisplayName("a file no loaded project holds is ignored without an error, so an editor that opens every file it has is not refused")
    void anUnknownFileIsIgnored() throws Exception {
        start();
        String open = JSON.writeValueAsString(Map.of("textDocument", Map.of("uri", "file:///nowhere/Other.java", "languageId", "java", "version", 1, "text", "class Other {}")));

        assertTrue(server.receive(notice("textDocument/didOpen", open)));

        assertTrue(sent.isEmpty(), "nothing was sent: " + sent);
    }
}
