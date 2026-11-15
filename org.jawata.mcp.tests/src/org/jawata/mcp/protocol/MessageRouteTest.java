package org.jawata.mcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jawata.mcp.fixtures.TransportFixtures;
import org.jawata.mcp.transport.HttpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 28h S16a: what the agent's door and the language server's door have in common is one route, and the part that differs is passed in.
 * Messages are worked in the order they arrive on a connection, except the methods the caller names, which act at once.
 */
class MessageRouteTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static int post(int port, String method) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/echo?conn=c"))
            .header("Authorization", "Bearer tok")
            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\"}")).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    @DisplayName("a message the session says belongs beside the main conversation is worked while the main turn runs")
    void aMessageBesideTheTurnDoesNotWaitForIt() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch besideSeen = new CountDownLatch(1);
        MessageRoute route = new MessageRoute("/echo", out -> new MessageRoute.Session() {
            @Override
            public void receive(JsonRpcMessage message) {
                if ("beside".equals(message.getMethod())) {
                    besideSeen.countDown();
                    return;
                }
                slowStarted.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public boolean worksBeside(JsonRpcMessage message) {
                return "beside".equals(message.getMethod());
            }
        }, method -> false, 1);
        HttpTransport transport = new HttpTransport(TransportFixtures.plainEndpoint(0, "127.0.0.1", "tok"), new TransportFixtures.RecordingLifecycle());
        transport.mount("/echo", route);
        TransportFixtures.runInBackground(transport);
        try {
            int port = transport.getActualPort();
            assertEquals(202, post(port, "slow"));
            assertTrue(slowStarted.await(30, TimeUnit.SECONDS), "the slow message never started");

            assertEquals(202, post(port, "beside"));

            assertTrue(besideSeen.await(10, TimeUnit.SECONDS), "the message beside the turn waited behind it");
        } finally {
            release.countDown();
            transport.close();
        }
    }

    @Test
    @DisplayName("a method the caller names acts while an earlier message is still being worked; every other message waits its turn")
    void aNamedMethodActsAtOnce() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch cancelSeen = new CountDownLatch(1);
        CountDownLatch queuedSeen = new CountDownLatch(1);
        AtomicBoolean queuedRanBeforeRelease = new AtomicBoolean();
        MessageRoute route = new MessageRoute("/echo", out -> message -> {
            switch (message.getMethod()) {
                case "slow" -> {
                    slowStarted.countDown();
                    try {
                        release.await(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                case "cancel" -> cancelSeen.countDown();
                case "queued" -> {
                    queuedRanBeforeRelease.set(release.getCount() > 0);
                    queuedSeen.countDown();
                }
                default -> { }
            }
        }, "cancel"::equals, 1);
        HttpTransport transport = new HttpTransport(TransportFixtures.plainEndpoint(0, "127.0.0.1", "tok"), new TransportFixtures.RecordingLifecycle());
        transport.mount("/echo", route);
        TransportFixtures.runInBackground(transport);
        try {
            int port = transport.getActualPort();
            assertEquals(202, post(port, "slow"));
            assertTrue(slowStarted.await(30, TimeUnit.SECONDS), "the slow message never started");
            assertEquals(202, post(port, "queued"));

            assertEquals(202, post(port, "cancel"));

            assertTrue(cancelSeen.await(10, TimeUnit.SECONDS), "the cancel waited behind the slow message");
            assertEquals(1, queuedSeen.getCount(), "the queued message ran while the slow one was still being worked");
            release.countDown();
            assertTrue(queuedSeen.await(30, TimeUnit.SECONDS), "the queued message never ran after the slow one");
            assertEquals(false, queuedRanBeforeRelease.get(), "the queued message ran ahead of the slow one it followed");
        } finally {
            release.countDown();
            transport.close();
        }
    }

    @Test
    @DisplayName("a session is told when its host ends the connection, and one that fails in hearing it does not stop the connection from ending")
    void aSessionIsToldWhenItsHostIsGone() throws Exception {
        CountDownLatch told = new CountDownLatch(1);
        MessageRoute route = new MessageRoute("/echo", out -> new MessageRoute.Session() {
            @Override
            public void receive(JsonRpcMessage message) {
                // the host's message is not what this test is about
            }

            @Override
            public void closed() {
                told.countDown();
                throw new IllegalStateException("a session that cannot release what it holds");
            }
        }, method -> false, 1);
        HttpTransport transport = new HttpTransport(TransportFixtures.plainEndpoint(0, "127.0.0.1", "tok"), new TransportFixtures.RecordingLifecycle());
        transport.mount("/echo", route);
        TransportFixtures.runInBackground(transport);
        try {
            int port = transport.getActualPort();
            assertEquals(202, post(port, "hello"));

            HttpRequest end = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/echo?conn=c"))
                .header("Authorization", "Bearer tok").DELETE().build();
            int status = HTTP.send(end, HttpResponse.BodyHandlers.discarding()).statusCode();

            assertTrue(told.await(30, TimeUnit.SECONDS), "the session was never told its host had gone");
            assertTrue(status < 300, "the connection ended although its session threw: " + status);
        } finally {
            transport.close();
        }
    }
}
