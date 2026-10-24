package org.jawata.mcp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.BindException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28k, E16: what the engine that runs alone does when its listener fails. The numbers are the contract with whatever
 * supervises the process, so they are pinned here as numbers.
 */
class ResidentLifecycleTest {

    @Test
    @DisplayName("a lifecycle that was told nothing leaves the status at 0")
    void aFreshLifecycleIsStatusZero() {
        assertEquals(0, new ResidentLifecycle(() -> { }).exitCode());
    }

    @Test
    @DisplayName("a deaf listener sets status 70 and ends the message loop")
    void deafnessSetsStatus70AndStopsTheLoop() {
        CountDownLatch stopped = new CountDownLatch(1);
        List<Integer> halted = new CopyOnWriteArrayList<>();
        ResidentLifecycle lifecycle = new ResidentLifecycle(stopped::countDown, halted::add, Duration.ofHours(1));

        lifecycle.listenerDeaf();

        assertEquals(70, lifecycle.exitCode());
        assertEquals(0, stopped.getCount(), "the message loop was asked to end");
        assertTrue(halted.isEmpty(), "and the hard stop waits for its grace period: " + halted);
    }

    @Test
    @DisplayName("an orderly end that hangs is followed by a hard stop with status 70")
    void aHangingEndIsFollowedByAHardStop() throws Exception {
        CountDownLatch halted = new CountDownLatch(1);
        List<Integer> statuses = new CopyOnWriteArrayList<>();
        ResidentLifecycle lifecycle = new ResidentLifecycle(() -> { }, status -> {
            statuses.add(status);
            halted.countDown();
        }, Duration.ofMillis(50));

        lifecycle.listenerDeaf();

        assertTrue(halted.await(10, TimeUnit.SECONDS), "a deaf process may have other things wrong with it; the hard stop must come");
        assertEquals(List.of(70), statuses);
    }

    @Test
    @DisplayName("a port that cannot be bound sets status 13 and prints the golden line on stderr")
    void aTakenPortSetsStatus13AndPrintsTheGoldenLine() {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            ResidentLifecycle lifecycle = new ResidentLifecycle(() -> { });

            lifecycle.cannotListen(8080, new BindException("Address already in use"));

            assertEquals(13, lifecycle.exitCode());
            assertEquals("FATAL: cannot bind port 8080 — Address already in use"
                + " (is another resident already listening on it?)" + System.lineSeparator(),
                captured.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(original);
        }
    }
}
