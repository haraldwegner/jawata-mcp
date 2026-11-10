package org.jawata.mcp.java.dap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jawata.mcp.engine.EngineApi;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.fixtures.TransportFixtures;
import org.jawata.mcp.transport.HttpTransport;
import org.jawata.spi.host.Instruments;
import org.jawata.spi.host.PersonDebugTurn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28i, Stage 25, step A2: the person's debugger on the engine's listener. An editor's debug adapter client is a client of {@code /dap}
 * the way the language client is a client of {@code /lsp}: a POST per message, a stream of the answers, the listener's key on every request. A
 * host whose debug turn is not the engine's own, one that has a debugger of its own, is refused.
 */
class DapRouteTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INITIALIZE =
        "{\"jsonrpc\":\"2.0\",\"method\":\"dap\",\"params\":{\"seq\":1,\"type\":\"request\",\"command\":\"initialize\",\"arguments\":{}}}";

    private static EngineApi engineOn(ToolCalls tools) {
        return (EngineApi) Proxy.newProxyInstance(EngineApi.class.getClassLoader(), new Class<?>[] {EngineApi.class},
            (proxy, method, args) -> "tools".equals(method.getName()) ? tools : null);
    }

    private static HttpTransport listenOn(DapFace face, String key) throws Exception {
        HttpTransport transport = new HttpTransport(TransportFixtures.plainEndpoint(0, "127.0.0.1", key), new TransportFixtures.RecordingLifecycle());
        transport.mount(face.path(), face.handler());
        TransportFixtures.runInBackground(transport);
        transport.getActualPort();
        return transport;
    }

    private static int post(int port, String conn, String key, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/dap?conn=" + conn))
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
                HttpRequest events = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/dap/events?conn=" + conn))
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
        }, "dap-test-stream-" + conn);
        reader.setDaemon(true);
        reader.start();
        return messages;
    }

    @Test
    @DisplayName("a request without the listener's key is refused, and an initialize with it is answered on the connection's stream as a debug message")
    void theKeyIsAskedForAndInitializeIsAnswered() throws Exception {
        PersonDebugTurn turn = new PersonDebugTurn();
        DapFace face = new DapFace(() -> () -> turn);
        face.attach(engineOn(null));
        HttpTransport transport = listenOn(face, "tok");
        try {
            int port = transport.getActualPort();
            assertEquals(401, post(port, "a", null, INITIALIZE));
            assertEquals(401, post(port, "a", "wrong", INITIALIZE));

            BlockingQueue<JsonNode> answers = stream(port, "a", "tok");
            assertEquals(202, post(port, "a", "tok", INITIALIZE));

            JsonNode envelope = answers.poll(30, TimeUnit.SECONDS);
            assertTrue(envelope != null, "no answer reached the stream");
            assertEquals("dap", envelope.path("method").asText());
            JsonNode answer = envelope.path("params");
            assertEquals("response", answer.path("type").asText());
            assertEquals(1, answer.path("request_seq").asInt());
            assertTrue(answer.path("body").path("supportsConfigurationDoneRequest").asBoolean());
        } finally {
            transport.close();
        }
    }

    @Test
    @DisplayName("CONTROL — a host whose debug turn is not the engine's own is refused with 503, and so is a face no engine has attached")
    void aHostWithItsOwnDebuggerIsRefused() throws Exception {
        DapFace eclipse = new DapFace(() -> Instruments.NONE);
        eclipse.attach(engineOn(null));
        HttpTransport onEclipse = listenOn(eclipse, "tok");
        DapFace detached = new DapFace(() -> () -> new PersonDebugTurn());
        HttpTransport onNothing = listenOn(detached, "tok");
        try {
            assertEquals(503, post(onEclipse.getActualPort(), "a", "tok", INITIALIZE));
            assertEquals(503, post(onNothing.getActualPort(), "a", "tok", INITIALIZE));
        } finally {
            onEclipse.close();
            onNothing.close();
        }
    }
}
