package org.jawata.mcp.transport;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Notices a resident that is ALIVE AND DEAF (REL44-32).
 *
 * <p>Measured 2026-10-15: a resident up for ten hours answered nothing — zero HTTP server threads
 * in a thread dump, 51 connections queued against a backlog of 50 — while its main thread waited
 * on a latch and the process looked healthy. The JDK's HTTP server runs its accept loop on one
 * dispatcher thread, and when that thread dies the process lives on without a listener. Nothing
 * restarted it and nothing in the studio could tell it from a slow resident.</p>
 *
 * <p>This asks the listener itself. {@link #tick()} is one probe and the state machine around it:
 * N CONSECUTIVE failures declare the server deaf, exactly once, and any answer in between resets
 * the count, so a slow moment is not deafness. {@link #httpProbe} is the real probe, and it counts
 * ANY HTTP status as an answer: a {@code 405} from the handler proves the dispatcher is running,
 * which is all this wants to know.</p>
 */
final class ServerWatchdog {

    /** One question: is the listener answering? */
    interface Probe {
        boolean alive();
    }

    private final Probe probe;
    private final int maxFailures;
    private final Runnable onDeaf;
    private int failures;
    private boolean declared;

    ServerWatchdog(Probe probe, int maxFailures, Runnable onDeaf) {
        if (probe == null || onDeaf == null) {
            throw new IllegalArgumentException("a watchdog needs a probe and something to do about deafness");
        }
        if (maxFailures < 1) {
            throw new IllegalArgumentException(
                "maxFailures must be at least 1: a threshold of " + maxFailures
                    + " would declare a healthy server deaf");
        }
        this.probe = probe;
        this.maxFailures = maxFailures;
        this.onDeaf = onDeaf;
    }

    /**
     * One probe. Returns true exactly when THIS tick declared the server deaf; a probe that throws
     * is a failure, never an answer.
     */
    synchronized boolean tick() {
        if (declared) {
            return false;
        }
        boolean answered;
        try {
            answered = probe.alive();
        } catch (RuntimeException e) {
            answered = false;
        }
        if (answered) {
            failures = 0;
            return false;
        }
        failures++;
        if (failures < maxFailures) {
            return false;
        }
        declared = true;
        onDeaf.run();
        return true;
    }

    /** Probes every {@code interval} on a daemon thread. The caller shuts the executor down. */
    ScheduledExecutorService start(Duration interval) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HttpTransport-watchdog");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::tick, interval.toMillis(), interval.toMillis(),
            TimeUnit.MILLISECONDS);
        return executor;
    }

    /**
     * The real probe: {@code GET <url>} with a short timeout. ANY status is an answer — the
     * handler's 405 for a GET without a body, without a token, is cheap, unlogged, and proves the
     * dispatcher thread is running. A refused connection, a timeout or a reset is not.
     */
    static Probe httpProbe(String url, Duration timeout) {
        return () -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
                connection.setConnectTimeout((int) timeout.toMillis());
                connection.setReadTimeout((int) timeout.toMillis());
                connection.setRequestMethod("GET");
                try {
                    connection.getResponseCode();
                    return true;
                } finally {
                    connection.disconnect();
                }
            } catch (IOException e) {
                return false;
            }
        };
    }
}
