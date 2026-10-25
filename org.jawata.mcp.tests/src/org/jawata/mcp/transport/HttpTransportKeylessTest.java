package org.jawata.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.jawata.mcp.fixtures.TransportFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28k, E18 item (c): the engine inside Eclipse carries no key (his ruling A3-1), so the endpoint policy may offer none. A
 * keyless endpoint is served without authentication, and that is only safe where nothing but this machine can reach it, so the
 * transport refuses a keyless endpoint on any address that is not loopback, when it is constructed.
 *
 * <p>The control in each test is the keyed endpoint of the standalone engine, which keeps answering 401 to a request without its key.</p>
 */
class HttpTransportKeylessTest {

    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

    @Test
    @DisplayName("a keyless endpoint on loopback answers a request that carries no Authorization header")
    void aKeylessLoopbackEndpointNeedsNoHeader() throws Exception {
        HttpTransport transport = new HttpTransport(TransportFixtures.keylessEndpoint(0, "127.0.0.1"),
            new TransportFixtures.RecordingLifecycle());
        TransportFixtures.runInBackground(transport);
        try {
            int port = transport.getActualPort();
            assertEquals(204, post(port, null), "no key is asked for, and the handler's reply to this notification is empty");
        } finally {
            transport.close();
        }
    }

    @Test
    @DisplayName("CONTROL: a keyed endpoint still answers 401 to a request without its key, and 204 with it")
    void aKeyedEndpointStillAsksForItsKey() throws Exception {
        HttpTransport transport = new HttpTransport(TransportFixtures.plainEndpoint(0, "127.0.0.1", "tok"),
            new TransportFixtures.RecordingLifecycle());
        TransportFixtures.runInBackground(transport);
        try {
            int port = transport.getActualPort();
            assertEquals(401, post(port, null));
            assertEquals(204, post(port, "Bearer tok"));
        } finally {
            transport.close();
        }
    }

    @Test
    @DisplayName("a keyless endpoint on an address other than loopback does not construct, and the refusal says why")
    void aKeylessEndpointOffLoopbackIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> new HttpTransport(TransportFixtures.keylessEndpoint(0, "0.0.0.0"), new TransportFixtures.RecordingLifecycle()));
        assertTrue(refused.getMessage().contains("loopback"), "names the reason: " + refused.getMessage());
    }

    private static int post(int port, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .POST(HttpRequest.BodyPublishers.ofString(PING));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
