package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jawata.mcp.execution.HireRegistry;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, Stage 27, row (z30): a client that runs jawata without the jawata chat is the principal of its hires, and nothing can push a
 * hire's report into its turn, so it asks for it. {@code hire_wait} answers the state of one hire's plan: running, the finished report, or
 * unknown, waiting a few seconds for a running one; and it marks a report it hands over as read, so the chat does not hand it over again.
 */
class HireWaitToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HireRegistry hires = HireRegistry.shared();
    private final HireWaitTool tool = new HireWaitTool(hires);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolResponse response) {
        assertTrue(response.isSuccess(), String.valueOf(response.getError()));
        return (Map<String, Object>) response.getData();
    }

    private ToolResponse ask(String hireId, int waitSeconds) {
        ObjectNode args = JSON.createObjectNode().put("hireId", hireId);
        args.put("waitSeconds", waitSeconds);
        return tool.execute(args);
    }

    private Thread holdRunning(String planId, CountDownLatch end) throws InterruptedException {
        CountDownLatch registered = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try (HireRegistry.Registration hire = hires.begin("developer", "z30 " + planId, "")) {
                hires.planOpened(planId);
                registered.countDown();
                end.await();
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
            }
        });
        thread.setDaemon(true);
        thread.start();
        assertTrue(registered.await(5, TimeUnit.SECONDS), "the hire did not register as running");
        return thread;
    }

    @Test
    @DisplayName("28h z136: a running hire is answered with what it has used so far and what has ended under it; a figure its brain has not sent reads 'no figure', never zero")
    void aRunningHireSaysWhatItHasUsedSoFar() throws Exception {
        CountDownLatch end = new CountDownLatch(1);
        CountDownLatch used = new CountDownLatch(1);
        Thread spending = new Thread(() -> {
            try (HireRegistry.Registration hire = hires.begin("chief-engineer", "z136 spending", "")) {
                hires.planOpened("plan-z136-spending");
                hires.noteToolCall("files");
                hires.noteToolCall("inspect");
                hires.noteUsage(java.util.OptionalLong.of(500), java.util.OptionalDouble.of(1.25));
                used.countDown();
                end.await();
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
            }
        });
        spending.setDaemon(true);
        spending.start();
        Thread silent = holdRunning("plan-z136-silent", end);
        try {
            assertTrue(used.await(5, TimeUnit.SECONDS), "the hire did not report its usage");
            try (HireRegistry.Registration worker = hires.begin("developer", "z136 worker", "plan-z136-spending")) {
                hires.planOpened("plan-z136-worker");
            }
            hires.record("developer", "z136 worker", JSON.createObjectNode().put("plan", "plan-z136-worker").put("costUsd", 0.5));

            Map<?, ?> soFar = (Map<?, ?>) dataOf(ask("plan-z136-spending", 0)).get("soFar");
            Map<?, ?> none = (Map<?, ?>) dataOf(ask("plan-z136-silent", 0)).get("soFar");

            assertAll(
                () -> assertEquals(2, soFar.get("toolSteps"), String.valueOf(soFar)),
                () -> assertEquals(1.25, soFar.get("costUsd"), String.valueOf(soFar)),
                () -> assertEquals(500L, soFar.get("contextTokens"), String.valueOf(soFar)),
                () -> assertTrue(soFar.get("seconds") instanceof Long, String.valueOf(soFar)),
                () -> assertEquals(0.5, ((Map<?, ?>) soFar.get("below")).get("costUsd"), String.valueOf(soFar)),
                () -> assertEquals("no figure", none.get("costUsd"), String.valueOf(none)),
                () -> assertEquals("no figure", none.get("contextTokens"), String.valueOf(none)),
                () -> assertTrue(!none.containsKey("below"), "CONTROL: nothing ended under it: " + none));
        } finally {
            end.countDown();
            spending.join(5_000);
            silent.join(5_000);
        }
    }

    @Test
    @DisplayName("28h z30: a running hire is answered as still running after the wait, and the wait is real; an id no hire has is answered unknown")
    void aRunningHireIsStillRunning() throws Exception {
        CountDownLatch end = new CountDownLatch(1);
        Thread running = holdRunning("plan-z30-running", end);
        try {
            long startedAt = System.nanoTime();
            Map<String, Object> waited = dataOf(ask("plan-z30-running", 1));
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            Map<String, Object> unknown = dataOf(ask("plan-z30-nobody", 0));

            assertAll(
                () -> assertEquals("running", waited.get("state"), String.valueOf(waited)),
                () -> assertTrue(tookMs >= 900, "the call did not wait for the running hire: " + tookMs + " ms"),
                () -> assertEquals("unknown", unknown.get("state"), "CONTROL: an id no hire has was given a state: " + unknown));
        } finally {
            end.countDown();
            running.join(5_000);
        }
    }

    @Test
    @DisplayName("28h z62: a hire of the chat's principal brain is answered at once even when asked to wait, and says its report comes back as a turn; the same call for a plain client's hire waits")
    void aChatPrincipalsHireIsNotWaitedFor() throws Exception {
        String brainKey = "z62-principal-brain-key";
        hires.speaksFor(brainKey, "z62-chat-session");
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try (HireRegistry.Registration hire = hires.begin("pm", "z62 chat principal", brainKey)) {
                hires.planOpened("plan-z62-chat");
                registered.countDown();
                end.await();
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
            }
        });
        thread.setDaemon(true);
        thread.start();
        assertTrue(registered.await(5, TimeUnit.SECONDS), "the hire did not register as running");
        try {
            long startedAt = System.nanoTime();
            Map<String, Object> asked = dataOf(ask("plan-z62-chat", 5));
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertAll(
                () -> assertTrue(tookMs < 1_000, "hire_wait held the chat's principal for " + tookMs + " ms of a 5000 ms wait: " + asked),
                () -> assertEquals("running", asked.get("state"), String.valueOf(asked)),
                () -> assertEquals(Boolean.TRUE, asked.get("reportComesAsTurn"), String.valueOf(asked)),
                () -> assertTrue(String.valueOf(asked.get("next")).contains("end your turn"), String.valueOf(asked)),
                () -> assertTrue(hires.reportComesAsTurn(brainKey) && !hires.reportComesAsTurn("a-plain-client-key") && !hires.reportComesAsTurn(""),
                    "CONTROL: only a caller that speaks for a chat has its report come back as a turn"));
        } finally {
            end.countDown();
            thread.join(5_000);
        }
    }

    @Test
    @DisplayName("28h z85: a running hire's answer carries the STEP lines it has written so far, a line split over chunks and one still being written included, and nothing else of its text")
    void aRunningHireSaysItsProgressLines() throws Exception {
        try (HireRegistry.Registration working = hires.begin("developer", "z85 working", "")) {
            hires.planOpened("plan-z85-lines");
            hires.noteText("let me look first\nSTEP: I will ren");
            hires.noteText("ame it\n- read the class\nSTEP: renaming");

            Map<String, Object> answer = dataOf(ask("plan-z85-lines", 0));

            assertEquals("[\"I will rename it\",\"renaming\"]", JSON.valueToTree(answer.get("soFar")).path("progressLines").toString(),
                String.valueOf(answer));
        }
    }

    @Test
    @DisplayName("28h z28: a running hire that has done nothing for longer than the stall ceiling is answered as stalled with the seconds it has been idle; one that has just acted is not")
    void aQuietHireIsAnsweredAsStalled() throws Exception {
        HireWaitTool strict = new HireWaitTool(hires, 1);
        try (HireRegistry.Registration quiet = hires.begin("developer", "z28 quiet", "")) {
            hires.planOpened("plan-z28-quiet");
            ObjectNode args = JSON.createObjectNode().put("hireId", "plan-z28-quiet");

            Map<String, Object> justStarted = dataOf(strict.execute(args));
            Thread.sleep(1_200);
            Map<String, Object> idle = dataOf(strict.execute(args));
            hires.noteToolCall("a call the hire made");
            Map<String, Object> acted = dataOf(strict.execute(args));

            assertAll(
                () -> assertEquals(Boolean.FALSE, justStarted.get("stalled"), "a hire that has just started was answered as stalled: " + justStarted),
                () -> assertEquals("running", idle.get("state"), String.valueOf(idle)),
                () -> assertEquals(Boolean.TRUE, idle.get("stalled"), "a hire idle past the ceiling was not answered as stalled: " + idle),
                () -> assertTrue(((Number) idle.get("idleSeconds")).longValue() >= 1, "the answer does not say how long it has been idle: " + idle),
                () -> assertEquals(Boolean.FALSE, acted.get("stalled"), "CONTROL: a hire that has just made a call was answered as stalled: " + acted));
        }
    }

    @Test
    @DisplayName("28h z30: a finished hire's report is handed over, new the first time and marked read, so the chat does not hand it over again; asking again answers the same report as not new")
    void aFinishedReportIsHandedOverOnce() throws Exception {
        String plan = "plan-z30-finished";
        // A chat id of this test's own: the reports every other test leaves unread under the empty id are not this test's to count.
        String chat = "z30-chat-once";
        hires.nextHireIsBackground();
        try (HireRegistry.Registration hire = hires.begin("developer", "z30 finished", chat)) {
            hires.planOpened(plan);
        }
        JsonNode answer = JSON.createObjectNode().put("plan", plan).put("name", "Zed").put("record", "/tmp/records/z/proposal.md")
            .put("answer", "I went through the file line by line\nREPORT: Renamed it. Open: nothing.");
        hires.record("developer", "z30 finished", answer);

        Map<String, Object> first = dataOf(ask(plan, 0));
        Map<String, Object> again = dataOf(ask(plan, 0));

        assertAll(
            () -> assertEquals("finished", first.get("state"), String.valueOf(first)),
            () -> assertEquals("Renamed it. Open: nothing.", JSON.valueToTree(first.get("report")).path("report").asText(), "the report is not the worker's report section: " + first),
            () -> assertTrue(JSON.valueToTree(first.get("report")).path("answer").isMissingNode(), "28h z52: the whole answer went to the caller: " + first),
            () -> assertEquals(Boolean.TRUE, first.get("newToYou"), "the first read was not marked new"),
            () -> assertEquals(Boolean.FALSE, again.get("newToYou"), "the second read of the same report was marked new again"),
            () -> assertEquals(List.of(), hires.undeliveredFor(chat), "the chat would still hand over a report the tool already gave"));
    }

    @Test
    @DisplayName("28h z30: a report the chat already took is handed over by the tool as not new, so it is never told twice from the other side either")
    void aReportTheChatAlreadyTookIsNotNewToTheTool() throws Exception {
        String plan = "plan-z30-chat-first";
        String chat = "z30-chat-first";
        hires.nextHireIsBackground();
        try (HireRegistry.Registration hire = hires.begin("developer", "z30 chat first", chat)) {
            hires.planOpened(plan);
        }
        JsonNode answer = JSON.createObjectNode().put("plan", plan).put("name", "Yan").put("result", "done");
        hires.record("developer", "z30 chat first", answer);
        List<HireRegistry.Event> waiting = hires.undeliveredFor(chat);
        assertEquals(1, waiting.size(), "CONTROL: the report was not waiting for the chat before it took it: " + waiting);
        hires.markDelivered(waiting.get(0));

        Map<String, Object> asked = dataOf(ask(plan, 0));

        assertAll(
            () -> assertEquals("finished", asked.get("state"), String.valueOf(asked)),
            () -> assertEquals(Boolean.FALSE, asked.get("newToYou"), "a report the chat had already taken was marked new to the tool: " + asked));
    }

    /**
     * A hire this lead issued, held running until released or stopped; {@code answeredOnStop} receives the message its call would answer when it
     * was stopped.
     */
    private Thread heldUnder(String leadPlan, String planId, CountDownLatch end, java.util.concurrent.atomic.AtomicReference<String> answeredOnStop)
            throws InterruptedException {
        CountDownLatch registered = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try (HireRegistry.Registration hire = hires.begin("developer", "z136 " + planId, leadPlan)) {
                hires.planOpened(planId);
                registered.countDown();
                try {
                    end.await();
                } catch (InterruptedException stopped) {
                    if (hire.wasStopped()) {
                        answeredOnStop.set(HireTool.stoppedAnswer(hire).getError().getMessage());
                    }
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        assertTrue(registered.await(5, TimeUnit.SECONDS), "the hire did not register as running");
        return thread;
    }

    private ToolResponse stopAs(String callerId, String hireId) {
        ObjectNode args = JSON.createObjectNode().put("hireId", hireId).put("action", "stop");
        return org.jawata.mcp.models.CallerIdentity.supplied(callerId).speaking(() -> tool.execute(args));
    }

    @Test
    @DisplayName("28h z136: a lead stops a hire of its own, whose answer says who stopped it; another lead is refused and the hire goes on")
    void aLeadStopsOnlyItsOwnHire() throws Exception {
        CountDownLatch end = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> answered = new java.util.concurrent.atomic.AtomicReference<>("");
        Thread child = heldUnder("plan-z136-lead", "plan-z136-child", end, answered);
        try {
            ToolResponse byAnother = stopAs("plan-z136-other-lead", "plan-z136-child");
            boolean ranOnAfterTheRefusal = hires.isRunningPlan("plan-z136-child");
            ToolResponse byItsLead = stopAs("plan-z136-lead", "plan-z136-child");
            child.join(5_000);

            assertAll(
                () -> assertTrue(!byAnother.isSuccess() && "NOT_YOUR_HIRE".equals(byAnother.getError().getCode()),
                    "another lead was let stop it: " + byAnother.getData()),
                () -> assertTrue(ranOnAfterTheRefusal, "the refused stop ended the hire"),
                () -> assertEquals("stopping", dataOf(byItsLead).get("state"), String.valueOf(byItsLead.getData())),
                () -> assertEquals(1, dataOf(byItsLead).get("count"), "how many were stopped is not said: " + byItsLead.getData()),
                () -> assertTrue(!hires.isRunningPlan("plan-z136-child"), "the hire its lead stopped is still running"),
                () -> assertTrue(answered.get().contains("plan-z136-lead"), "the stopped hire does not say who stopped it: " + answered.get()));
        } finally {
            end.countDown();
            child.join(5_000);
        }
    }

    /** A hire of this lead, held running until released or stopped; its registration is handed out so the test can claim through it. */
    private Thread heldRegistered(String role, String leadPlan, String planId, CountDownLatch end,
            java.util.concurrent.atomic.AtomicReference<HireRegistry.Registration> registration) throws InterruptedException {
        CountDownLatch registered = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try (HireRegistry.Registration hire = hires.begin(role, "z207 " + planId, leadPlan)) {
                hires.planOpened(planId);
                registration.set(hire);
                registered.countDown();
                try {
                    end.await();
                } catch (InterruptedException stopped) {
                    // stopped by its hirer: the registration closes below, as when a hire's own run ends
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        assertTrue(registered.await(5, TimeUnit.SECONDS), "the hire did not register as running: " + planId);
        return thread;
    }

    @Test
    @DisplayName("28h z207: a grandparent stops a developer whose own lead has already ended, because who hired whom is kept after a hire ends; the package it held is then free for the successor's developer")
    void aGrandparentStopsADeveloperWhoseLeadHasEnded() throws Exception {
        String pm = "plan-z207-pm";
        List<String> held = List.of("org.z207.held");
        CountDownLatch endCe = new CountDownLatch(1);
        CountDownLatch endDev = new CountDownLatch(1);
        CountDownLatch endCe2 = new CountDownLatch(1);
        CountDownLatch endDev2 = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<HireRegistry.Registration> ceHire = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<HireRegistry.Registration> devHire = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<HireRegistry.Registration> ce2Hire = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<HireRegistry.Registration> dev2Hire = new java.util.concurrent.atomic.AtomicReference<>();
        Thread ce = heldRegistered("chief-engineer", pm, "plan-z207-ce", endCe, ceHire);
        Thread dev = heldRegistered("developer", "plan-z207-ce", "plan-z207-dev", endDev, devHire);
        Thread ce2 = null;
        Thread dev2 = null;
        try {
            java.util.Optional<String> first = devHire.get().claim(held);
            // The chief engineer's hire ends; its developer keeps running.
            endCe.countDown();
            ce.join(5_000);
            boolean leadEnded = !hires.isRunningPlan("plan-z207-ce");
            boolean devRunsOn = hires.isRunningPlan("plan-z207-dev");

            ce2 = heldRegistered("chief-engineer", pm, "plan-z207-ce2", endCe2, ce2Hire);
            dev2 = heldRegistered("developer", "plan-z207-ce2", "plan-z207-dev2", endDev2, dev2Hire);
            java.util.Optional<String> refusedBefore = dev2Hire.get().claim(held);

            ToolResponse stopped = stopAs(pm, "plan-z207-dev");
            dev.join(5_000);
            boolean devStillRunning = hires.isRunningPlan("plan-z207-dev");
            java.util.Optional<String> afterStop = dev2Hire.get().claim(held);

            assertAll(
                () -> assertTrue(first.isEmpty(), "CONTROL: the first developer's claim was refused: " + first),
                () -> assertTrue(leadEnded && devRunsOn, "CONTROL: the lead did not end alone, or the developer did not go on: " + leadEnded + " " + devRunsOn),
                () -> assertTrue(refusedBefore.isPresent() && refusedBefore.get().contains("plan-z207-dev"),
                    "the successor's claim on a held package was not refused naming its holder: " + refusedBefore),
                () -> assertEquals("stopping", dataOf(stopped).get("state"), String.valueOf(stopped.getData())),
                () -> assertEquals(1, dataOf(stopped).get("count"), "how many were stopped is not said: " + stopped.getData()),
                () -> assertTrue(!devStillRunning, "the developer its grandparent stopped is still running"),
                () -> assertTrue(afterStop.isEmpty(), "the package the stopped developer held was not freed: " + afterStop));
        } finally {
            endCe.countDown();
            endDev.countDown();
            endCe2.countDown();
            endDev2.countDown();
            dev.join(5_000);
            if (dev2 != null) {
                dev2.join(5_000);
            }
            if (ce2 != null) {
                ce2.join(5_000);
            }
        }
    }

    @Test
    @DisplayName("28h z211: a call that may wait 120 seconds returns the moment the hire finishes, after about 2 seconds, with the hire's report")
    void aLongWaitReturnsTheMomentTheHireFinishes() throws Exception {
        String plan = "plan-z211-finishes";
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch registered = new CountDownLatch(1);
        Thread hire = new Thread(() -> {
            hires.nextHireIsBackground();
            try (HireRegistry.Registration running = hires.begin("developer", "z211 finishes", "")) {
                hires.planOpened(plan);
                registered.countDown();
                release.await();
                // Recorded before the registration closes, so the instant the hire stops running its report is already there.
                hires.record("developer", "z211 finishes", JSON.createObjectNode().put("plan", plan).put("name", "Zed")
                    .put("record", "/tmp/records/z/proposal.md").put("answer", "worked on it\nREPORT: Done. Open: nothing."));
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
            }
        });
        hire.setDaemon(true);
        hire.start();
        assertTrue(registered.await(5, TimeUnit.SECONDS), "the hire did not register as running");
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });
        releaser.setDaemon(true);
        releaser.start();
        try {
            long startedAt = System.nanoTime();
            Map<String, Object> answer = dataOf(ask(plan, 120));
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertAll(
                () -> assertEquals("finished", answer.get("state"), String.valueOf(answer)),
                () -> assertTrue(tookMs >= 1_000, "the call did not wait for the hire: " + tookMs + " ms"),
                () -> assertTrue(tookMs < 20_000, "the call went on waiting after the hire had finished: " + tookMs + " ms of a 120000 ms wait"),
                () -> assertEquals("Done. Open: nothing.", JSON.valueToTree(answer.get("report")).path("report").asText(), String.valueOf(answer)));
        } finally {
            release.countDown();
            hire.join(5_000);
            releaser.join(5_000);
        }
    }

    @Test
    @DisplayName("28h z211: a wait of 601 seconds is refused with invalid_parameter, not clamped to the largest wait and answered as if it had been asked")
    void aWaitAbove600IsRefusedNotClamped() throws Exception {
        ToolResponse over = ask("plan-z211-over", 601);

        assertAll(
            () -> assertTrue(!over.isSuccess(), "601 was answered as a success, as if it had been clamped: " + over.getData()),
            () -> assertEquals(org.jawata.mcp.models.ErrorInfo.INVALID_PARAMETER, over.getError().getCode(), String.valueOf(over.getError())),
            () -> assertTrue(over.getError().getMessage().contains("waitSeconds") && over.getError().getMessage().contains("600"),
                "the refusal does not name waitSeconds and the largest value: " + over.getError().getMessage()));
    }

    @Test
    @DisplayName("28h z211: a wait of exactly 600 seconds is accepted, and on a hire that has already finished it answers finished at once")
    void aWaitOf600OnAFinishedHireAnswersAtOnce() throws Exception {
        String plan = "plan-z211-boundary";
        hires.nextHireIsBackground();
        try (HireRegistry.Registration hire = hires.begin("developer", "z211 boundary", "")) {
            hires.planOpened(plan);
        }
        hires.record("developer", "z211 boundary", JSON.createObjectNode().put("plan", plan).put("name", "Yan")
            .put("record", "/tmp/records/y/proposal.md").put("answer", "went through it\nREPORT: Done at the boundary. Open: nothing."));

        long startedAt = System.nanoTime();
        Map<String, Object> answer = dataOf(ask(plan, 600));
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertAll(
            () -> assertEquals("finished", answer.get("state"), String.valueOf(answer)),
            () -> assertTrue(tookMs < 2_000, "a finished hire was waited for: " + tookMs + " ms"));
    }
}
