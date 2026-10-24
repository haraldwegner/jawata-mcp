package org.jawata.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jawata.mcp.engine.ResidentLifecycle;
import org.jawata.mcp.fixtures.TransportFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28k, E16: the transport reports a listener failure to its host and the headless host turns it into the status a supervisor
 * reads. These are the two ends of the standalone engine that have to keep the numbers they always had: 70 for a listener that went
 * deaf behind a living process, 13 for a port that is taken.
 *
 * <p>The application returns {@code ResidentLifecycle#exitCode()} when the message loop returns, and the launcher exits with it, so
 * what is pinned here is that the loop DOES return and the status is the one that comes out. The control in each test is the same
 * transport with nothing wrong with it.</p>
 */
class HttpTransportReportsToTheHostTest {

    @Test
    @DisplayName("a listener that goes deaf ends the run loop and the host's status is 70")
    void aDeafListenerEndsTheRunLoopWithStatus70() throws Exception {
        AtomicReference<HttpTransport> transport = new AtomicReference<>();
        List<Integer> halted = new CopyOnWriteArrayList<>();
        // The hard stop is armed but given an hour: the orderly end below must be what ends the loop, not the fallback.
        ResidentLifecycle lifecycle = new ResidentLifecycle(() -> transport.get().close(), halted::add, Duration.ofHours(1));
        transport.set(new HttpTransport(TransportFixtures.plainEndpoint(0, "127.0.0.1", "tok"), lifecycle,
            Duration.ofSeconds(30), Duration.ofMillis(50), 2));
        Thread runner = TransportFixtures.runInBackground(transport.get());
        try {
            transport.get().getActualPort();
            assertEquals(0, lifecycle.exitCode(), "CONTROL: a listener that answers leaves the status at 0");

            transport.get().stopListenerOnly();

            runner.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(runner.isAlive(), "the listener is gone and the run loop must return so the application can exit");
            assertEquals(70, lifecycle.exitCode(), "the status the launcher exits with for a deaf listener");
            assertTrue(halted.isEmpty(), "the orderly end worked, so the hard stop was not used: " + halted);
        } finally {
            transport.get().close();
        }
    }

    @Test
    @DisplayName("a port that is taken is reported with the golden text, the run loop returns and the status is 13")
    void aTakenPortIsReportedWithStatus13() throws Exception {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = taken.getLocalPort();
            ResidentLifecycle lifecycle = new ResidentLifecycle(() -> { });
            HttpTransport transport = new HttpTransport(TransportFixtures.plainEndpoint(port, "127.0.0.1", "tok"), lifecycle);
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));

            transport.run((message, caller) -> null);

            System.setErr(originalErr);
            assertEquals(13, lifecycle.exitCode(), "the status the launcher exits with for a port that is taken");
            String said = err.toString(StandardCharsets.UTF_8).strip();
            assertTrue(said.startsWith("FATAL: cannot bind port " + port + " — "), "the golden text begins: " + said);
            assertTrue(said.endsWith(" (is another resident already listening on it?)"), "and ends: " + said);
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("CONTROL: a port that is free binds, nothing is reported and the status stays 0")
    void aFreePortReportsNothing() throws Exception {
        TransportFixtures.RecordingLifecycle lifecycle = new TransportFixtures.RecordingLifecycle();
        HttpTransport transport = new HttpTransport(TransportFixtures.plainEndpoint(0, "127.0.0.1", "tok"), lifecycle);
        TransportFixtures.runInBackground(transport);
        try {
            assertTrue(transport.getActualPort() > 0);
            assertEquals(-1, lifecycle.cannotListenPort, "nothing to report");
            assertNull(lifecycle.cannotListenCause);
            assertNotNull(transport.localMcpUrl());
        } finally {
            transport.close();
        }
    }
}
