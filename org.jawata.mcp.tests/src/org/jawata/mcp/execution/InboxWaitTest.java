package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Stage 27 (r) and (v), 2026-11-18: a lead ended "done" while its chief engineer still ran, and the chief engineer's report had no lead to go to.
 * The lead stopped waiting when no report came within the lead's own wall-clock ceiling, though a hire below it was still running under its
 * own. The wait now goes on, slice by slice, for as long as a hire of the lead is out and running.
 */
class InboxWaitTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<Thread> children = new ArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void endTheHires() throws InterruptedException {
        release.countDown();
        for (Thread child : children) {
            child.join(5_000);
        }
    }

    /** A hire of this lead started in the background that runs until {@code sleepMillis} have passed, and then keeps its answer as the hire tool does. */
    private void aHireIsOut(String leadId, String planId, long sleepMillis) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        Thread child = new Thread(() -> {
            HireRegistry.shared().nextHireIsBackground();
            try (HireRegistry.Registration ignored = HireRegistry.shared().begin("developer", "inbox-wait " + planId, leadId)) {
                HireRegistry.shared().planOpened(planId);
                started.countDown();
                release.await(sleepMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
                return;
            }
            ObjectNode kept = JSON.createObjectNode().put("plan", planId).put("name", "Joe").put("cleanExit", true).put("answer", "done");
            HireRegistry.shared().record("developer", "inbox-wait " + planId, kept);
        });
        child.setDaemon(true);
        children.add(child);
        child.start();
        assertTrue(started.await(5, TimeUnit.SECONDS), "the hire did not start");
    }

    @Test
    @DisplayName("a hire that runs longer than one wait is waited for, and its report comes; a single wait of the same length comes back empty")
    void aHireLongerThanOneWaitStillDeliversItsReport() throws Exception {
        String lead = "inbox-wait-long";
        HireRegistry.Inbox inbox = HireRegistry.shared().inboxFor(lead);
        try {
            aHireIsOut(lead, "inbox-wait-child-long", 1_200);
            // THE CONTROL: one wait of 300 ms is what ended the lead, and the hire is still out when it returns.
            assertTrue(inbox.await(300).isEmpty(), "the hire ended inside one wait, so this proves nothing");
            assertEquals(1, inbox.outstanding(), "the hire was not out");

            List<HireRegistry.Event> reports = inbox.awaitWhileOut(300, () -> !HireRegistry.shared().runningNamesFor(lead).isEmpty());
            assertAll(
                () -> assertEquals(1, reports.size(), "THE CLAIM: the report of a hire that outlived the wait was lost"),
                () -> assertEquals(0, inbox.outstanding()));
        } finally {
            inbox.close();
        }
    }

    @Test
    @DisplayName("with nothing out the wait does not wait at all")
    void withNothingOutItDoesNotWait() throws Exception {
        HireRegistry.Inbox inbox = HireRegistry.shared().inboxFor("inbox-wait-none");
        try {
            long began = System.nanoTime();
            List<HireRegistry.Event> reports = inbox.awaitWhileOut(2_000, () -> true);
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            assertAll(
                () -> assertTrue(reports.isEmpty()),
                () -> assertTrue(millis < 1_500, "a lead with nothing out waited " + millis + " ms"));
        } finally {
            inbox.close();
        }
    }

    @Test
    @DisplayName("a hire counted as out that no longer runs ends the wait after one slice, so a lead is never held for a count that cannot fall")
    void aHireThatIsNoLongerRunningEndsTheWait() throws Exception {
        String lead = "inbox-wait-gone";
        HireRegistry.Inbox inbox = HireRegistry.shared().inboxFor(lead);
        try {
            aHireIsOut(lead, "inbox-wait-child-gone", 60_000);
            long began = System.nanoTime();
            List<HireRegistry.Event> reports = inbox.awaitWhileOut(300, () -> false);
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            assertAll(
                () -> assertTrue(reports.isEmpty()),
                () -> assertFalse(inbox.outstanding() == 0, "the hire is still counted out, which is what this case is about"),
                () -> assertTrue(millis < 3_000, "the wait went on for " + millis + " ms with nothing running"));
        } finally {
            inbox.close();
        }
    }
}
