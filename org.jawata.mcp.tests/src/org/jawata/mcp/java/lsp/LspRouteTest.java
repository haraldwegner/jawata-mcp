package org.jawata.mcp.java.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.core.ICompilationUnit;
import org.jawata.mcp.engine.EngineApi;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.fixtures.TransportFixtures;
import org.jawata.mcp.transport.HttpTransport;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.spi.host.HostCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 28h S16a: the language server on the engine's listener. Editors are clients of {@code /lsp} the way the agent's host program is a client
 * of {@code /acp}: a POST per message, a stream of the answers, the listener's key on every request. Two editors that open the same file
 * share the one buffer, and a host that keeps the editor's buffers itself is refused.
 */
class LspRouteTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static EngineApi engineOn(boolean ownsWorkspace, ToolCalls tools) {
        HostCapabilities host = new HostCapabilities("test", ownsWorkspace, true, true, List.of("java"));
        return (EngineApi) Proxy.newProxyInstance(EngineApi.class.getClassLoader(), new Class<?>[] {EngineApi.class},
            (proxy, method, args) -> "host".equals(method.getName()) ? host : "tools".equals(method.getName()) ? tools : null);
    }

    private static HttpTransport listenOn(LspFace face, String key) throws Exception {
        HttpTransport transport = new HttpTransport(TransportFixtures.plainEndpoint(0, "127.0.0.1", key), new TransportFixtures.RecordingLifecycle());
        transport.mount(face.path(), face.handler());
        TransportFixtures.runInBackground(transport);
        transport.getActualPort();
        return transport;
    }

    private static int post(int port, String conn, String key, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/lsp?conn=" + conn))
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header("Authorization", "Bearer " + key);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** Opens the stream of one connection and collects each message it sends. */
    private static BlockingQueue<JsonNode> stream(int port, String conn, String key) {
        BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try {
                HttpRequest events = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/lsp/events?conn=" + conn))
                    .header("Authorization", "Bearer " + key).GET().build();
                HTTP.send(events, HttpResponse.BodyHandlers.ofLines()).body().forEach(line -> {
                    if (line.startsWith("data: ")) {
                        try {
                            messages.add(JSON.readTree(line.substring("data: ".length())));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                });
            } catch (Exception ignored) {
                // a stream the resident closed ends here either way
            }
        }, "lsp-test-stream-" + conn);
        reader.setDaemon(true);
        reader.start();
        return messages;
    }

    @Test
    @DisplayName("a request without the listener's key is refused, and an initialize with it is answered on the connection's stream")
    void theKeyIsAskedForAndInitializeIsAnswered() throws Exception {
        LspFace face = new LspFace(() -> null, 300);
        face.attach(engineOn(true, null));
        HttpTransport transport = listenOn(face, "tok");
        try {
            int port = transport.getActualPort();
            assertEquals(401, post(port, "a", null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"));
            assertEquals(401, post(port, "a", "wrong", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"));

            BlockingQueue<JsonNode> answers = stream(port, "a", "tok");
            assertEquals(202, post(port, "a", "tok", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"));

            JsonNode answer = answers.poll(30, TimeUnit.SECONDS);
            assertTrue(answer != null, "no answer reached the stream");
            assertEquals(1, answer.path("id").asInt());
            assertEquals(2, answer.path("result").path("capabilities").path("textDocumentSync").path("change").asInt());
        } finally {
            transport.close();
        }
    }

    @Test
    @DisplayName("two editors that open the same file share one buffer, and it goes when the last of them closes")
    void twoEditorsShareTheBuffer() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        Path calculator = helper.getFixturePath("simple-maven").resolve("src/main/java/com/example/Calculator.java");
        String text = Files.readString(calculator);
        ICompilationUnit unit = service.getCompilationUnit(calculator).getPrimary();
        LspFace face = new LspFace(() -> service, 300);
        face.attach(engineOn(true, EditorTools.over(service, caller -> { })));
        HttpTransport transport = listenOn(face, "tok");
        try {
            int port = transport.getActualPort();
            String open = JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "textDocument/didOpen",
                "params", Map.of("textDocument", Map.of("uri", calculator.toUri().toString(), "languageId", "java", "version", 1, "text", text))));
            String close = JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "textDocument/didClose",
                "params", Map.of("textDocument", Map.of("uri", calculator.toUri().toString()))));

            assertEquals(202, post(port, "editor-a", "tok", open));
            waitForHolders(service, unit, 1);
            ICompilationUnit first = service.buffers().orElseThrow().view(unit).orElseThrow();
            assertEquals(202, post(port, "editor-b", "tok", open));
            waitForHolders(service, unit, 2);
            assertSame(first, service.buffers().orElseThrow().view(unit).orElseThrow(), "one working copy for both editors");

            assertEquals(202, post(port, "editor-a", "tok", close));
            waitForHolders(service, unit, 1);
            assertTrue(service.buffers().orElseThrow().view(unit).isPresent(), "the second editor still has it open");
            assertEquals(202, post(port, "editor-b", "tok", close));
            waitForHolders(service, unit, 0);
        } finally {
            transport.close();
        }
    }

    @Test
    @DisplayName("a host that keeps the editor's buffers itself is refused with 503, and so is a face no engine has attached")
    void aHostThatOwnsNoWorkspaceIsRefused() throws Exception {
        LspFace eclipse = new LspFace(() -> null, 300);
        eclipse.attach(engineOn(false, null));
        HttpTransport onEclipse = listenOn(eclipse, "tok");
        LspFace detached = new LspFace(() -> null, 300);
        HttpTransport onNothing = listenOn(detached, "tok");
        try {
            String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
            assertEquals(503, post(onEclipse.getActualPort(), "a", "tok", initialize));
            assertEquals(503, post(onNothing.getActualPort(), "a", "tok", initialize));
        } finally {
            onEclipse.close();
            onNothing.close();
        }
    }

    private static void waitForHolders(JdtServiceImpl service, ICompilationUnit unit, int expected) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (service.buffers().orElseThrow().holders(unit) != expected && System.nanoTime() < until) {
            Thread.sleep(50);
        }
        assertEquals(expected, service.buffers().orElseThrow().holders(unit));
    }
}
