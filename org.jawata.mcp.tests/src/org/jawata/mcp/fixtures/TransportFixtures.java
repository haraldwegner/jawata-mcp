package org.jawata.mcp.fixtures;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import org.jawata.mcp.transport.HttpTransport;
import org.jawata.mcp.transport.ResolvedToken;
import org.jawata.mcp.transport.TransportConfig;
import org.jawata.resident.ResidentEndpointPolicy;
import org.jawata.spi.host.EndpointPolicy;
import org.jawata.spi.host.EngineLifecycle;
import org.jawata.spi.host.McpEndpoint;

/**
 * What the transport tests hand a transport in place of the port, address and token it used to take (Sprint 28k, E16): an
 * endpoint policy, and a lifecycle that records what the transport told it.
 */
public final class TransportFixtures {

    private TransportFixtures() {
    }

    /** The standalone engine's own policy, so a test that reads the start line reads the production one. */
    public static EndpointPolicy residentEndpoint(int port, String bindAddress, String token) {
        TransportConfig config = new TransportConfig(TransportConfig.Kind.HTTP, port, bindAddress, token, null);
        return new ResidentEndpointPolicy(config, ResolvedToken.resolve(config));
    }

    /** A policy that answers exactly what it is given, including values the standalone engine would never produce. */
    public static EndpointPolicy plainEndpoint(int port, String bindAddress, String key) {
        return new EndpointPolicy() {
            @Override
            public String bindAddress() {
                return bindAddress;
            }

            @Override
            public int port() {
                return port;
            }

            @Override
            public Optional<String> key() {
                return Optional.ofNullable(key);
            }

            @Override
            public void listening(McpEndpoint at) {
            }
        };
    }

    /** A policy that offers no key, as the engine inside an IDE does (his ruling A3-1). */
    public static EndpointPolicy keylessEndpoint(int port, String bindAddress) {
        return plainEndpoint(port, bindAddress, null);
    }

    /** Runs {@code transport} on a daemon thread, the way the application runs it; the run loop ending is what a test looks at. */
    public static Thread runInBackground(HttpTransport transport) {
        Thread t = new Thread(() -> {
            try {
                transport.run((message, caller) -> null);
            } catch (Exception ignored) {
                // the transport is closed by the test
            }
        }, "transport-under-test");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** A lifecycle that remembers what it was told. */
    public static final class RecordingLifecycle implements EngineLifecycle {
        /** Counted down when the listener is declared deaf. */
        public final CountDownLatch deaf = new CountDownLatch(1);
        /** The port of the last {@code cannotListen}, or -1. */
        public volatile int cannotListenPort = -1;
        /** The cause of the last {@code cannotListen}, or null. */
        public volatile Exception cannotListenCause;

        @Override
        public void listenerDeaf() {
            deaf.countDown();
        }

        @Override
        public void cannotListen(int port, Exception cause) {
            cannotListenPort = port;
            cannotListenCause = cause;
        }
    }
}
