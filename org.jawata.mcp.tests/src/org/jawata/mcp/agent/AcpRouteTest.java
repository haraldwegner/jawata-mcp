package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jawata.mcp.engine.EngineApi;
import org.jawata.mcp.fixtures.TransportFixtures;
import org.jawata.mcp.transport.HttpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, S5: the agent's door on the listener keeps the listener's key on every method it answers, and a connection ends when the
 * host program says so, so its stream does not stay open for the life of the resident.
 */
class AcpRouteTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static HttpTransport listener(String key) throws Exception {
        AgentFace face = new AgentFace(List::of);
        face.attach((EngineApi) Proxy.newProxyInstance(EngineApi.class.getClassLoader(), new Class<?>[] {EngineApi.class},
            (proxy, method, args) -> null));
        HttpTransport transport = new HttpTransport(TransportFixtures.plainEndpoint(0, "127.0.0.1", key),
            new TransportFixtures.RecordingLifecycle());
        transport.mount(face.path(), face.handler());
        TransportFixtures.runInBackground(transport);
        transport.getActualPort();
        return transport;
    }

    private static int status(int port, String method, String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path + "?conn=c1"))
            .method(method, "POST".equals(method) ? HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}")
                : HttpRequest.BodyPublishers.noBody());
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    @DisplayName("a host program that goes away without a DELETE has its connection released once the next write to its stream fails")
    void aStreamTheHostDropsReleasesItsConnection() throws Exception {
        AgentFace face = new AgentFace(List::of);
        face.attach((EngineApi) Proxy.newProxyInstance(EngineApi.class.getClassLoader(), new Class<?>[] {EngineApi.class},
            (proxy, method, args) -> null));
        AcpRoute route = new AcpRoute(face, () -> (prompt, chunks) -> { }, 1);
        HttpTransport transport = new HttpTransport(TransportFixtures.plainEndpoint(0, "127.0.0.1", "tok"),
            new TransportFixtures.RecordingLifecycle());
        transport.mount("/acp", route);
        TransportFixtures.runInBackground(transport);
        try {
            int port = transport.getActualPort();
            HttpRequest events = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/acp/events?conn=gone"))
                .header("Authorization", "Bearer tok").GET().build();
            InputStream stream = HTTP.send(events, HttpResponse.BodyHandlers.ofInputStream()).body();
            assertTrue(stream.read() >= 0, "the stream sent nothing");
            assertEquals(1, route.openConnections(), "the stream did not open a connection");

            stream.close();

            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (route.openConnections() > 0 && System.nanoTime() < until) {
                Thread.sleep(100);
            }
            assertEquals(0, route.openConnections(), "the connection outlived its dropped stream");
        } finally {
            transport.close();
        }
    }

    @Test
    @DisplayName("every method on the route asks for the key, and a DELETE ends the connection's open stream")
    void aDeleteEndsTheStreamAndEveryMethodAsksForTheKey() throws Exception {
        HttpTransport transport = listener("tok");
        try {
            int port = transport.getActualPort();
            assertEquals(401, status(port, "POST", "/acp", null));
            assertEquals(401, status(port, "GET", "/acp/events", null));
            assertEquals(401, status(port, "DELETE", "/acp", null));
            assertEquals(401, status(port, "POST", "/acp", "wrong"));

            CountDownLatch ended = new CountDownLatch(1);
            CountDownLatch streaming = new CountDownLatch(1);
            Thread stream = new Thread(() -> {
                try {
                    HttpRequest events = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/acp/events?conn=c1"))
                        .header("Authorization", "Bearer tok").GET().build();
                    HTTP.send(events, HttpResponse.BodyHandlers.ofLines()).body().forEach(line -> streaming.countDown());
                } catch (Exception ignored) {
                    // a stream the resident closed ends here either way
                } finally {
                    ended.countDown();
                }
            }, "acp-test-stream");
            stream.setDaemon(true);
            stream.start();
            assertEquals(202, status(port, "POST", "/acp", "tok"));
            assertTrue(streaming.await(30, TimeUnit.SECONDS), "the agent's answer never reached the stream");

            assertEquals(204, status(port, "DELETE", "/acp", "tok"));
            assertTrue(ended.await(30, TimeUnit.SECONDS), "the stream stayed open after the connection was ended");
        } finally {
            transport.close();
        }
    }
}
