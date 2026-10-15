package org.jawata.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REL44-32: a resident that is ALIVE AND DEAF is noticed.
 *
 * <p>Measured 2026-10-15: a resident up for ten hours answered nothing, sat at 1.6% of a core with
 * no garbage-collection activity, had zero HTTP server threads in a thread dump and 51 connections
 * queued against a backlog of 50, while its main thread waited on a latch in {@code HttpTransport}.
 * The process stayed up when the server inside it had died, and nothing noticed: the studio saw a
 * process, every tool call timed out. The watchdog asks the listener itself, and when it stops
 * answering the process exits non-zero, so whatever supervises it sees a dead resident instead of a
 * deaf one.</p>
 */
class ServerWatchdogTest {

    @Test
    @DisplayName("it declares the server deaf after N CONSECUTIVE failed probes, once")
    void itDeclaresDeafnessAfterConsecutiveFailures() {
        AtomicInteger declared = new AtomicInteger();
        ServerWatchdog dog = new ServerWatchdog(() -> false, 3, declared::incrementAndGet);

        assertAll(
            () -> assertFalse(dog.tick(), "one failure is not deafness"),
            () -> assertFalse(dog.tick(), "two are not either"),
            () -> assertTrue(dog.tick(), "the third consecutive failure declares it"),
            () -> assertFalse(dog.tick(), "and it is declared once, not on every later tick"),
            () -> assertEquals(1, declared.get()));
    }

    @Test
    @DisplayName("an answer in between resets the count, so a slow moment is not deafness")
    void anAnswerResetsTheCount() {
        AtomicBoolean answers = new AtomicBoolean(false);
        AtomicInteger declared = new AtomicInteger();
        ServerWatchdog dog = new ServerWatchdog(answers::get, 3, declared::incrementAndGet);

        dog.tick();
        dog.tick();
        answers.set(true);
        dog.tick();
        answers.set(false);
        dog.tick();
        dog.tick();

        assertEquals(0, declared.get(), "two failures, an answer, two failures: never three in a row");
    }

    @Test
    @DisplayName("a probe that throws counts as a failure, never as an answer")
    void aThrowingProbeIsAFailure() {
        AtomicInteger declared = new AtomicInteger();
        ServerWatchdog dog = new ServerWatchdog(() -> {
            throw new IllegalStateException("the probe fell over");
        }, 2, declared::incrementAndGet);

        dog.tick();
        dog.tick();

        assertEquals(1, declared.get());
    }

    @Test
    @DisplayName("a threshold below one would declare a healthy server deaf and is refused")
    void aNonsenseThresholdIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ServerWatchdog(() -> true, 0, () -> { }));
    }

    @Test
    @DisplayName("started, it probes on its own schedule and declares deafness without anyone calling tick")
    void itRunsOnItsOwnSchedule() throws Exception {
        CountDownLatch declared = new CountDownLatch(1);
        ServerWatchdog dog = new ServerWatchdog(() -> false, 2, declared::countDown);

        var executor = dog.start(Duration.ofMillis(20));
        try {
            assertTrue(declared.await(5, TimeUnit.SECONDS), "it never declared a server that never answers");
        } finally {
            executor.shutdownNow();
        }
    }
}
