package org.jawata.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REL44-32, the wiring: the real transport probes its own real listener, and when the listener goes
 * while the process lives it says so.
 *
 * <p>The failure being reproduced is "listener gone, process alive". {@code stopListenerOnly} stops
 * the HTTP server without the transport's own shutdown, which is exactly what leaves a process that
 * looks fine and answers nothing. The control is the same transport left alone.</p>
 */
class HttpTransportNoticesItsOwnDeafnessTest {

    private static Thread runInBackground(HttpTransport transport) {
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

    @Test
    @DisplayName("a listener that dies behind a living process is declared deaf")
    void aDeadListenerBehindALivingProcessIsDeclared() throws Exception {
        CountDownLatch deaf = new CountDownLatch(1);
        HttpTransport transport = new HttpTransport(0, "127.0.0.1", "tok", Duration.ofSeconds(30),
            Duration.ofMillis(50), 2, deaf::countDown);
        runInBackground(transport);
        try {
            transport.getActualPort();
            transport.stopListenerOnly();

            assertTrue(deaf.await(10, TimeUnit.SECONDS),
                "the listener is gone and the process is alive, and nothing said so");
        } finally {
            transport.close();
        }
    }

    @Test
    @DisplayName("CONTROL: the same transport left alone is never declared deaf")
    void aHealthyListenerIsNeverDeclared() throws Exception {
        CountDownLatch deaf = new CountDownLatch(1);
        HttpTransport transport = new HttpTransport(0, "127.0.0.1", "tok", Duration.ofSeconds(30),
            Duration.ofMillis(50), 2, deaf::countDown);
        runInBackground(transport);
        try {
            transport.getActualPort();

            assertFalse(deaf.await(1500, TimeUnit.MILLISECONDS),
                "a healthy listener answers every probe (a 405 is an answer)");
        } finally {
            transport.close();
        }
    }
}
