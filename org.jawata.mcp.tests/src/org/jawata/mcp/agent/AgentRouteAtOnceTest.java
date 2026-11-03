package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jawata.mcp.engine.EngineApi;
import org.jawata.mcp.fixtures.TransportFixtures;
import org.jawata.mcp.protocol.MessageRoute;
import org.jawata.mcp.transport.HttpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h, S9: the route itself, not the rule it is given. The tests of the panel's stop, pick and brains list call the server directly and
 * cannot see whether the door queues them behind the running prompt, which is what a hire running inside the prompt would meet. Here a
 * prompt is held open and a request sent over the real listener must be answered while it is still held.
 */
class AgentRouteAtOnceTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static void post(int port, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/acp?conn=c1"))
            .header("Authorization", "Bearer tok").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(202, HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    private static String answerTo(List<String> events, int id, long seconds) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < until) {
            for (String event : events) {
                if (JSON.readTree(event).path("id").asInt(-1) == id) {
                    return event;
                }
            }
            Thread.sleep(50);
        }
        return null;
    }

    @Test
    @DisplayName("a request the panel sends while a prompt is running is answered at once through the real route, and a prompt is still held in order")
    void theRouteDoesNotQueueThePanelBehindThePrompt() throws Exception {
        CountDownLatch inPrompt = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AgentFace face = new AgentFace(List::of);
        face.attach((EngineApi) Proxy.newProxyInstance(EngineApi.class.getClassLoader(), new Class<?>[] {EngineApi.class},
            (proxy, method, args) -> null));
        MessageRoute route = face.route(() -> (prompt, chunks) -> {
            inPrompt.countDown();
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 60);
        HttpTransport transport = new HttpTransport(TransportFixtures.plainEndpoint(0, "127.0.0.1", "tok"), new TransportFixtures.RecordingLifecycle());
        transport.mount("/acp", route);
        TransportFixtures.runInBackground(transport);
        List<String> events = new CopyOnWriteArrayList<>();
        try {
            int port = transport.getActualPort();
            Thread stream = new Thread(() -> {
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/acp/events?conn=c1"))
                        .header("Authorization", "Bearer tok").GET().build();
                    HTTP.send(request, HttpResponse.BodyHandlers.ofLines()).body()
                        .filter(line -> line.startsWith("data: ")).forEach(line -> events.add(line.substring("data: ".length())));
                } catch (Exception ignored) {
                    // the stream ends when the connection does
                }
            }, "agent-route-test-stream");
            stream.setDaemon(true);
            stream.start();

            post(port, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/new\",\"params\":{\"cwd\":\"/w\"}}");
            String created = answerTo(events, 1, 20);
            assertTrue(created != null, "session/new was never answered");
            String session = JSON.readTree(created).path("result").path("sessionId").asText();
            post(port, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/prompt\",\"params\":{\"sessionId\":\"" + session
                + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"go\"}]}}");
            assertTrue(inPrompt.await(20, TimeUnit.SECONDS), "the prompt never reached the brain");

            post(port, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"_jawata/brains\",\"params\":{}}");
            post(port, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"session/list\",\"params\":{}}");

            assertTrue(answerTo(events, 3, 10) != null, "the brains list waited behind the running prompt");
            assertEquals(null, answerTo(events, 2, 1), "the held prompt was answered before it was released");
            assertEquals(null, answerTo(events, 4, 1), "a request that is not run at once overtook the prompt before it");
            release.countDown();
            assertTrue(answerTo(events, 2, 20) != null, "the prompt was never answered after it was released");
            assertTrue(answerTo(events, 4, 20) != null, "the request queued behind the prompt was never answered");
            assertFalse(events.isEmpty());
        } finally {
            release.countDown();
            transport.close();
        }
    }
}
