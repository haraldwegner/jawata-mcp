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

        Held(String plan, boolean actsAfterTheWait, String issuer) {
            thread = new Thread(() -> {
                HireRegistry hires = HireRegistry.shared();
                try (HireRegistry.Registration hire = hires.begin("developer", "z28 stop " + plan, issuer)) {
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
    @DisplayName("z102: a lead with a hire running under it is neither told quiet nor stopped; once nothing runs under it, its quiet counts again from then")
    void aLeadWaitingOnItsHireIsNotQuiet() throws Exception {
        Held lead = new Held("plan-z102-lead", false, "");
        assertTrue(lead.registered.await(5, TimeUnit.SECONDS), "the lead did not register");
        Held child = new Held("plan-z102-child", true, "plan-z102-lead");
        try {
            assertTrue(child.registered.await(5, TimeUnit.SECONDS), "the lead's hire did not register");
            Thread.sleep(1_300);
            child.act.countDown();
            Thread.sleep(200);

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> asked = (java.util.Map<String, Object>) new org.jawata.mcp.tools.HireWaitTool(HireRegistry.shared(), 1)
                .execute(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("hireId", "plan-z102-lead")).getData();
            assertAll(
                () -> assertEquals(false, asked.get("stalled"), "hire_wait called a waiting lead stalled: " + asked),
                () -> assertTrue(String.valueOf(asked.get("waitingFor")).contains("developer"), "hire_wait does not say what the lead waits for: " + asked));

            List<String> told = HireRegistry.shared().noteQuiet(1);
            List<String> stopped = HireRegistry.shared().stopStalled(1);
            child.release.countDown();
            child.thread.join(5_000);
            List<String> rightAfter = HireRegistry.shared().stopStalled(1);

            assertAll(
                () -> assertEquals(List.of(), told, "a lead waiting on its hire was told as quiet"),
                () -> assertEquals(List.of(), stopped, "a lead waiting on its hire was stopped"),
                () -> assertEquals(List.of(), rightAfter, "a lead was stopped the moment its hire ended, before it could read the report"),
                () -> assertTrue(lead.thread.isAlive(), "the lead is no longer running"));

            Thread.sleep(1_300);
            assertEquals(1, HireRegistry.shared().stopStalled(1).size(), "CONTROL: a lead with nothing under it that stays quiet is stopped");
            lead.thread.join(5_000);
        } finally {
            child.release.countDown();
            lead.release.countDown();
            child.thread.join(5_000);
            lead.thread.join(5_000);
        }
    }

    @Test
    @DisplayName("28h z28: a hire quiet past the second ceiling is stopped and says it was a stall and for how long; a hire that has just acted is not stopped; nothing else is told stopped")
    void aHireQuietPastTheSecondCeilingIsStopped() throws Exception {
        Held quiet = new Held("plan-z28-quiet-stop", false, "");
        Held active = new Held("plan-z28-active-stop", true, "");
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
