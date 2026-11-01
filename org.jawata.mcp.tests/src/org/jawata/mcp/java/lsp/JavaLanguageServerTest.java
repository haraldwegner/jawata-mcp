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
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.core.ICompilationUnit;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 28h S16a: one language-server conversation, driven by messages in memory (no stream, no HTTP): the lifecycle, the errors, the document
 * sync that puts an editor's unsaved text into the engine's buffers, and the problems the engine finds in that text, published as the
 * person types. The fixture is {@code Calculator.java} of simple-maven; the broken text adds a method that returns a string as an int.
 */
class JavaLanguageServerTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lsp-test-scheduler");
        t.setDaemon(true);
        return t;
    });
    private static final String BAD = "\"x\"";

    private JdtServiceImpl service;
    private Path calculator;
    private String saved;
    private final List<JsonRpcMessage> sent = new CopyOnWriteArrayList<>();
    private final List<CallerIdentity> callers = new CopyOnWriteArrayList<>();
    private JavaLanguageServer server;

    private void start() throws Exception {
        start(null);
    }

    /** @param wrap puts a layer around the engine's tools, or null for none */
    private void start(java.util.function.UnaryOperator<ToolCalls> wrap) throws Exception {
        service = helper.loadProject("simple-maven");
        calculator = helper.getFixturePath("simple-maven").resolve("src/main/java/com/example/Calculator.java");
        saved = Files.readString(calculator);
        ToolCalls tools = EditorTools.over(service, callers::add);
        server = new JavaLanguageServer(() -> service, wrap == null ? tools : wrap.apply(tools), CallerIdentity.editor("test"), sent::add, SCHEDULER, 0);
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

    private String brokenText() {
        return saved.substring(0, saved.lastIndexOf('}')) + "    int bad() { return " + BAD + "; }\n}\n";
    }

    private void open(String text) throws Exception {
        server.receive(notice("textDocument/didOpen", JSON.writeValueAsString(
            Map.of("textDocument", Map.of("uri", uri(), "languageId", "java", "version", 1, "text", text)))));
    }

    private void closeFile() throws Exception {
        server.receive(notice("textDocument/didClose", JSON.writeValueAsString(Map.of("textDocument", Map.of("uri", uri())))));
    }

    /** Replaces the characters from {@code (line, from)} to {@code (line, to)} with {@code text}, as an editor's incremental change. */
    private void editAt(int line, int from, int to, String text) throws Exception {
        server.receive(notice("textDocument/didChange", JSON.writeValueAsString(Map.of(
            "textDocument", Map.of("uri", uri(), "version", 2),
            "contentChanges", List.of(Map.of("range", Map.of("start", Map.of("line", line, "character", from),
                "end", Map.of("line", line, "character", to)), "text", text))))));
    }

    /** The parameters of every publishDiagnostics the server has sent, in order. */
    private List<JsonNode> published() {
        List<JsonNode> params = new ArrayList<>();
        for (JsonRpcMessage message : sent) {
            if ("textDocument/publishDiagnostics".equals(message.getMethod())) {
                params.add(message.getParams());
            }
        }
        return params;
    }

    private void awaitPublished(int count) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (published().size() < count && System.nanoTime() < until) {
            Thread.sleep(25);
        }
        assertTrue(published().size() >= count, "expected " + count + " publishDiagnostics, got " + published().size() + ": " + sent);
    }

    private static List<JsonNode> errorsOf(JsonNode published) {
        List<JsonNode> errors = new ArrayList<>();
        for (JsonNode diagnostic : published.path("diagnostics")) {
            if (diagnostic.path("severity").asInt() == 1) {
                errors.add(diagnostic);
            }
        }
        return errors;
    }

    /** The zero-based line and column of the first {@code token} in {@code text}. */
    private static int[] positionOf(String text, String token) {
        int offset = text.indexOf(token);
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[] {line, offset - lineStart};
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
        open(saved);
        assertEquals(saved, textOfBuffer());

        editAt(0, 0, 0, "// typed\n");
        assertTrue(textOfBuffer().startsWith("// typed\npackage com.example;"), textOfBuffer().substring(0, 40));
        assertTrue(service.buffers().orElseThrow().isDirty(unit()));

        closeFile();
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

    @Test
    @DisplayName("the problems of the unsaved text are published at the characters they are on, follow each change, and are cleared when the file closes")
    void problemsFollowTheText() throws Exception {
        start();
        String broken = brokenText();
        int[] bad = positionOf(broken, BAD);

        open(broken);
        awaitPublished(1);
        JsonNode first = published().get(0);
        assertEquals(uri(), first.path("uri").asText());
        assertEquals(1, errorsOf(first).size(), first.toString());
        JsonNode error = errorsOf(first).get(0);
        assertEquals("jawata", error.path("source").asText());
        assertEquals(bad[0], error.path("range").path("start").path("line").asInt());
        assertEquals(bad[1], error.path("range").path("start").path("character").asInt());
        assertEquals(bad[0], error.path("range").path("end").path("line").asInt());
        assertEquals(bad[1] + BAD.length(), error.path("range").path("end").path("character").asInt(), "the range covers the whole literal");
        assertTrue(error.path("message").asText().contains("String"), error.path("message").asText());

        editAt(bad[0], bad[1], bad[1] + BAD.length(), "1");
        awaitPublished(2);
        assertEquals(0, errorsOf(published().get(1)).size(), "fixed in the editor, though the file on disk never had the error");

        closeFile();
        awaitPublished(3);
        assertEquals(0, published().get(2).path("diagnostics").size(), "a closed file shows nothing of the editor's");
        assertFalse(callers.isEmpty(), "the problems were asked of the engine's gate: a server that answered another way would leave no call here");
        assertTrue(callers.stream().allMatch(caller -> caller.origin() == CallerIdentity.Origin.EDITOR), "every call was the editor's: " + callers);
    }

    @Test
    @DisplayName("an answer for text that has moved on is not published: only the answer for the text the editor now has reaches it")
    void aStaleAnswerIsNotPublished() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(real -> new ToolCalls() {
            @Override
            public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) throws org.jawata.mcp.engine.ToolNotFoundException {
                ToolResponse answer = real.callTool(name, arguments, caller);
                if (calls.incrementAndGet() == 1) {
                    try {
                        editAt(0, 0, 0, "// moved\n"); // the person types while the first answer is on its way
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                return answer;
            }

            @Override
            public List<Map<String, Object>> getToolDefinitions() {
                return real.getToolDefinitions();
            }
        });
        String broken = brokenText();
        int[] bad = positionOf(broken, BAD);

        open(broken);
        awaitPublished(1);
        Thread.sleep(500); // a stale answer, if it were published, would be here by now
        assertEquals(1, published().size(), "only the answer for the new text: " + published());
        assertEquals(2, calls.get());
        assertEquals(bad[0] + 1, errorsOf(published().get(0)).get(0).path("range").path("start").path("line").asInt(),
            "the error sits one line lower, where the typed line pushed it");
    }

    @Test
    @DisplayName("an editor that can be asked to watch files is asked to watch Java and build files; an editor that cannot is not asked")
    void watchedFilesAreRequestedOnlyOfAnEditorThatCanBeAsked() throws Exception {
        start();
        server.receive(request(1, "initialize", "{\"capabilities\":{\"workspace\":{\"didChangeWatchedFiles\":{\"dynamicRegistration\":true}}}}"));
        server.receive(notice("initialized", "{}"));

        JsonRpcMessage asked = sent.stream().filter(message -> "client/registerCapability".equals(message.getMethod())).findFirst().orElseThrow();
        JsonNode registration = asked.getParams().path("registrations").get(0);
        assertEquals("workspace/didChangeWatchedFiles", registration.path("method").asText());
        assertTrue(registration.path("registerOptions").path("watchers").toString().contains("**/*.java"), registration.toString());
        assertTrue(registration.path("registerOptions").path("watchers").toString().contains("**/pom.xml"), registration.toString());

        sent.clear();
        server.receive(request(2, "initialize", "{}"));
        server.receive(notice("initialized", "{}"));
        assertTrue(sent.stream().noneMatch(message -> "client/registerCapability".equals(message.getMethod())),
            "an editor that did not say it can be asked is not asked: " + sent);
    }

    @Test
    @DisplayName("when a watched file changes on disk, the problems of the files the editor has open are asked for again")
    void aChangeOnDiskChecksTheOpenFilesAgain() throws Exception {
        start();
        open(brokenText());
        awaitPublished(1);

        server.receive(notice("workspace/didChangeWatchedFiles", "{\"changes\":[{\"uri\":\"file:///elsewhere/Other.java\",\"type\":2}]}"));

        awaitPublished(2);
        assertEquals(uri(), published().get(1).path("uri").asText());
        assertEquals(1, errorsOf(published().get(1)).size(), "the open file still has its error");
    }
}
