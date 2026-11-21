package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, Stage 27, row (z28), the second ceiling: a hire that has shown no sign of life for longer than the ceiling is stopped by the engine,
 * not left running for ever, and its registration says the stop was a stall and for how long, so its answer can tell its hirer why.
 */
class HireStalledStopTest {

    /** A running hire on a thread of its own: it registers, opens its plan, and then waits until it is stopped or released. */
    private static final class Held {
        final Thread thread;
        final CountDownLatch registered = new CountDownLatch(1);
        final CountDownLatch act = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<OptionalLong> stalledFor = new AtomicReference<>(OptionalLong.empty());
        final AtomicReference<Boolean> interrupted = new AtomicReference<>(false);

        Held(String plan, boolean actsAfterTheWait) {
            thread = new Thread(() -> {
                HireRegistry hires = HireRegistry.shared();
                try (HireRegistry.Registration hire = hires.begin("developer", "z28 stop " + plan, "")) {
                    hires.planOpened(plan);
                    registered.countDown();
                    try {
                        if (actsAfterTheWait) {
                            act.await();
                            hires.noteToolCall("a call the hire made");
                        }
                        release.await();
                    } catch (InterruptedException stopped) {
                        interrupted.set(true);
                    }
                    stalledFor.set(hire.stalledFor());
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Test
    @DisplayName("28h z28: a hire quiet past the second ceiling is stopped and says it was a stall and for how long; a hire that has just acted is not stopped; nothing else is told stopped")
    void aHireQuietPastTheSecondCeilingIsStopped() throws Exception {
        Held quiet = new Held("plan-z28-quiet-stop", false);
        Held active = new Held("plan-z28-active-stop", true);
        try {
            assertTrue(quiet.registered.await(5, TimeUnit.SECONDS) && active.registered.await(5, TimeUnit.SECONDS), "the hires did not register");
            Thread.sleep(1_300);
            active.act.countDown();
            Thread.sleep(200);

            List<String> stopped = HireRegistry.shared().stopStalled(1);
            quiet.thread.join(5_000);

            assertAll(
                () -> assertEquals(1, stopped.size(), "exactly the quiet hire was to be stopped: " + stopped),
                () -> assertTrue(quiet.interrupted.get(), "the quiet hire's thread was not interrupted"),
                () -> assertTrue(quiet.stalledFor.get().isPresent() && quiet.stalledFor.get().getAsLong() >= 1, "the stop does not say it was a stall: " + quiet.stalledFor.get()),
                () -> assertFalse(active.interrupted.get(), "CONTROL: a hire that had just made a call was stopped"),
                () -> assertTrue(active.thread.isAlive(), "CONTROL: the active hire is no longer running"),
                () -> assertEquals(List.of(), HireRegistry.shared().stopStalled(1_000_000), "CONTROL: a ceiling nobody reached stopped a hire"));
        } finally {
            quiet.release.countDown();
            active.release.countDown();
            active.thread.join(5_000);
        }
    }
}
