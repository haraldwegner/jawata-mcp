package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jawata.core.host.HostProcesses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Stage 24, M12 (D55): a hired lead's brain stays alive while the hires it started in the background are out, and is prompted with each
 * report; its tool-step ceiling is counted across all its turns. Run against the stand-in agent of {@link AcpBrainSessionTest}.
 */
class LeadLoopTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HostProcesses host = HostProcesses.system();
    private Path dir;
    private AcpBrainSession.Program agent;
    private final CountDownLatch finishTheChildren = new CountDownLatch(1);
    private final java.util.List<Thread> children = new java.util.ArrayList<>();

    @BeforeEach
    void startAgentSource() throws IOException {
        dir = Files.createTempDirectory("jawata-lead-loop-");
        try (InputStream in = LeadLoopTest.class.getResourceAsStream("/test-resources/acp/FakeAcpAgent.java.txt")) {
            assertNotNull(in, "the stand-in agent is not among the test resources");
            Files.write(dir.resolve("FakeAcpAgent.java"), in.readAllBytes());
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        agent = new AcpBrainSession.Program(List.of(java.toString(), dir.resolve("FakeAcpAgent.java").toString()),
            Map.of("FAKE_ACP_STATE", dir.toString()));
    }

    @AfterEach
    void removeWhatWasCreated() throws IOException, InterruptedException {
        finishTheChildren.countDown();
        for (Thread child : children) {
            child.join(5_000);
        }
        if (dir != null) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /**
     * A hire this lead's brain started in the background, running on a thread of its own as the engine runs one. It ends a moment after the lead's
     * run has begun, and what it answers is kept after its registration has closed, as the hire tool keeps it.
     * @param workMillis how long the hire works before it reports
     * @param startsWorkWhen released when the hire may begin its work, so a test can fix the order of the hire's report and the lead's brain's first
     *                       answer instead of racing them on a loaded machine; null when the hire works from the moment it starts
     */
    private void aHireIsOut(String leadId, String planId, String answer, long workMillis, CountDownLatch startsWorkWhen) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        Thread child = new Thread(() -> {
            HireRegistry.shared().nextHireIsBackground();
            try (HireRegistry.Registration ignored = HireRegistry.shared().begin("developer", "m12 " + planId, leadId)) {
                HireRegistry.shared().planOpened(planId);
                started.countDown();
                if (startsWorkWhen != null && !startsWorkWhen.await(60, TimeUnit.SECONDS)) {
                    return;
                }
                Thread.sleep(workMillis);
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
                return;
            }
            ObjectNode kept = JSON.createObjectNode().put("plan", planId).put("name", "Joe").put("cleanExit", true).put("answer", answer);
            HireRegistry.shared().record("developer", "m12 " + planId, kept);
        });
        child.setDaemon(true);
        children.add(child);
        child.start();
        assertTrue(started.await(5, TimeUnit.SECONDS), "the hire did not start");
    }

    private HiredBrain.Outcome runAsLead(String prompt, HiredBrain.Ceilings ceilings, String leadId, HireRegistry.Inbox inbox) throws Exception {
        return AcpBrainSession.runAsLead(host, agent, HireSite.in(dir), prompt, null, List.of(), Optional.empty(), ceilings, null, null, update -> { },
            inbox, leadId, null);
    }

    @Test
    @DisplayName("a lead with a hire out is prompted with its report and answers it; a lead with nothing out ends after its first turn")
    void aLeadIsPromptedWithTheReportOfAHireItStarted() throws Exception {
        // THE CONTROL: nothing is out, so the run is one turn, as it was.
        HiredBrain.Outcome alone = runAsLead("REMEMBER:bluebird", HiredBrain.Ceilings.of(Duration.ofSeconds(60)), "m12-lead-alone",
            HireRegistry.shared().inboxFor("m12-lead-alone"));

        String lead = "m12-lead-reported";
        HireRegistry.Inbox watching = HireRegistry.shared().inboxFor(lead);
        aHireIsOut(lead, "m12-child-report", "the code is written", 1_500, null);
        HiredBrain.Outcome reported = runAsLead("REMEMBER:bluebird", HiredBrain.Ceilings.of(Duration.ofSeconds(60)), lead, watching);

        assertAll(
            () -> assertEquals("noted", alone.text(), "a lead with nothing out was prompted again"),
            () -> assertTrue(reported.text().contains("report read: Hire: Joe · developer (m12-child-report)"),
                "the lead was never prompted with the report of its hire: " + reported.text()),
            () -> assertTrue(reported.clean(), "the lead's run did not end cleanly: " + reported.stderrTail()));
    }

    @Test
    @DisplayName("28h-C27-15 and -18: a lead whose brain dies while a hire it started is out ends instead of waiting on, and the hire's report goes up to whoever hired the lead once the lead's run has ended")
    void aLeadWhoseBrainDiedEndsAndHandsTheReportUp() throws Exception {
        String lead = "c27-lead-died";
        String child = "c27-child-of-dead";
        List<HireRegistry.Event> told = new java.util.concurrent.CopyOnWriteArrayList<>();
        Runnable unsubscribe = HireRegistry.shared().subscribe(event -> {
            if (child.equals(event.planId()) && "finished".equals(event.kind())) {
                told.add(event);
            }
        });
        HireRegistry.Registration leadRun = HireRegistry.shared().begin("pm", "c27 a lead whose brain dies", "c27-main");
        try {
            HireRegistry.shared().planOpened(lead);
            HireRegistry.Inbox watching = HireRegistry.shared().inboxFor(lead);
            // The hire's work begins when the brain's first answer has streamed, and the stand-in is killed 300 ms after it answers, so the report
            // comes 1.2 s after the brain is gone whatever time the stand-in took to start: started at once, the hire could report before a slow
            // start had answered, and the brain then died in the report's turn (the full suite, twice, on a loaded machine).
            CountDownLatch answered = new CountDownLatch(1);
            aHireIsOut(lead, child, "the code is written", 1_500, answered);
            long began = System.nanoTime();
            HiredBrain.Outcome ended = AcpBrainSession.runAsLead(host, agent, HireSite.in(dir), "ANSWERTHENDIE", null, List.of(), Optional.empty(),
                HiredBrain.Ceilings.of(Duration.ofSeconds(60)), text -> {
                    if (text.contains("noted")) {
                        answered.countDown();
                    }
                }, null, update -> { }, watching, lead, null);
            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - began);
            // the hire ends while the lead is still registered, whether the lead saw its brain die while waiting or before it waited
            children.get(children.size() - 1).join(10_000);
            int whileTheLeadRuns = told.size();
            leadRun.close();

            assertAll(
                // The brain's answer streamed before it died: that answer is what let the hire begin its work. The outcome's own text is the LAST
                // turn's, and on a slow machine (the macOS runner) the brain's end is seen after the hire's report came, so the lead was prompted
                // once more and the dead brain's reply is "ended before it answered" (CI 4.6.10, three runs); what this test is about is that the
                // lead then ended at once and handed the report up, which the assertions below say.
                () -> assertEquals(0, answered.getCount(), "CONTROL: the lead's brain did not answer its turn before it died (clean=" + ended.clean() + ", stopped by "
                    + ended.stoppedBy() + ", its text: " + ended.text() + ", its stderr: " + ended.stderrTail() + ")"),
                () -> assertTrue(seconds < 30, "the lead waited on with a dead brain: " + seconds + " s"),
                () -> assertTrue(ended.stoppedBy().isPresent(), "a lead whose brain died with a hire out was reported as a clean run"),
                () -> assertEquals(whileTheLeadRuns + 1, told.size(), "the hire's report did not go up once the lead whose brain died had ended: " + told),
                () -> assertEquals(lead, told.get(told.size() - 1).leadId(), "the report that went up does not name the lead that ended"),
                () -> assertTrue(HireRegistry.shared().undeliveredFor(HireRegistry.shared().rootOf(lead)).stream().anyMatch(e -> child.equals(e.planId())),
                    "the report was not kept for the chat above the lead"));
        } finally {
            leadRun.close();
            unsubscribe.run();
            told.forEach(HireRegistry.shared()::markDelivered);
        }
    }

    @Test
    @DisplayName("28h-C27-18: a report that comes while its lead still runs but reads no inbox any more (its brain's turns are over) is held, and told for the lead's chat once it ends; its hire's finish is told at once, so its card ends")
    void aReportForALeadThatReadsNoInboxIsHeldUntilTheLeadEnds() throws Exception {
        String lead = "c27-lead-no-inbox";
        String child = "c27-child-no-inbox";
        List<HireRegistry.Event> told = new java.util.concurrent.CopyOnWriteArrayList<>();
        Runnable unsubscribe = HireRegistry.shared().subscribe(event -> {
            if (child.equals(event.planId()) && "finished".equals(event.kind())) {
                told.add(event);
            }
        });
        HireRegistry.Registration leadRun = HireRegistry.shared().begin("pm", "c27 a lead that reads no inbox", "c27-main-2");
        try {
            HireRegistry.shared().planOpened(lead);
            aHireIsOut(lead, child, "the code is written", 1_500, null);
            children.get(children.size() - 1).join(10_000);
            int whileTheLeadRuns = told.size();
            boolean keptWhileTheLeadRuns = HireRegistry.shared().undeliveredFor(HireRegistry.shared().rootOf(lead)).stream()
                .anyMatch(e -> child.equals(e.planId()));
            leadRun.close();

            assertAll(
                () -> assertEquals(1, whileTheLeadRuns, "the hire's finish was not told when it finished, so its card stayed running: " + told),
                () -> assertFalse(keptWhileTheLeadRuns, "the report was handed to the chat while its lead still ran"),
                () -> assertEquals(2, told.size(), "the report was not told again once its lead had ended: " + told),
                () -> assertTrue(HireRegistry.shared().undeliveredFor(HireRegistry.shared().rootOf(lead)).stream().anyMatch(e -> child.equals(e.planId())),
                    "the report was not kept for the chat above the lead"));
        } finally {
            leadRun.close();
            unsubscribe.run();
            told.forEach(HireRegistry.shared()::markDelivered);
        }
    }

    @Test
    @DisplayName("28h-C27-18, the C27 audit: a report the lead's run took but never showed its brain, and one that comes after its inbox closed, reach the lead's chat once the lead ends, and not before")
    void aReportTheLeadsBrainWasNeverShownGoesUpWhenItsInboxCloses() throws Exception {
        String lead = "c27-lead-unshown";
        String taken = "c27-child-taken";
        String late = "c27-child-late";
        HireRegistry.Registration leadRun = HireRegistry.shared().begin("pm", "c27 a lead whose run took a report it never showed", "c27-main-3");
        try {
            HireRegistry.shared().planOpened(lead);
            HireRegistry.Inbox inbox = HireRegistry.shared().inboxFor(lead);
            aHireIsOut(lead, taken, "the first is written", 100, null);
            children.get(children.size() - 1).join(10_000);
            List<HireRegistry.Event> got = inbox.await(5_000);
            inbox.close();
            aHireIsOut(lead, late, "the second is written", 100, null);
            children.get(children.size() - 1).join(10_000);
            String chat = HireRegistry.shared().rootOf(lead);
            boolean keptWhileTheLeadRuns = HireRegistry.shared().undeliveredFor(chat).stream()
                .anyMatch(e -> taken.equals(e.planId()) || late.equals(e.planId()));
            leadRun.close();
            List<String> kept = HireRegistry.shared().undeliveredFor(chat).stream().map(HireRegistry.Event::planId).toList();

            assertAll(
                () -> assertEquals(List.of(taken), got.stream().map(HireRegistry.Event::planId).toList(), "CONTROL: the lead's run did not take the first report"),
                () -> assertFalse(keptWhileTheLeadRuns, "a report went to the chat while its lead still ran"),
                () -> assertTrue(kept.contains(taken), "a report the lead's brain was never shown was lost when its inbox closed: " + kept),
                () -> assertTrue(kept.contains(late), "a report that came after the inbox closed was lost: " + kept));
        } finally {
            leadRun.close();
            HireRegistry.shared().undeliveredFor(HireRegistry.shared().rootOf(lead)).forEach(HireRegistry.shared()::markDelivered);
        }
    }

    @Test
    @DisplayName("the full suite, 2026-11-20: a brain whose output ends in the turn that showed it a report, while its process is still seen alive, has not read the report: it reaches the lead's chat and the lead is reported stopped")
    void aReportShownToABrainWhoseOutputEndedIsNotTakenAsRead() throws Exception {
        String lead = "c27-lead-output-ended";
        String child = "c27-child-output-ended";
        HireRegistry.Registration leadRun = HireRegistry.shared().begin("pm", "c27 a lead whose brain's output ends", "c27-main-5");
        try {
            HireRegistry.shared().planOpened(lead);
            HireRegistry.Inbox watching = HireRegistry.shared().inboxFor(lead);
            aHireIsOut(lead, child, "the code is written", 1_500, null);
            HiredBrain.Outcome ended = runAsLead("ANSWERTHENCLOSE", HiredBrain.Ceilings.of(Duration.ofSeconds(60)), lead, watching);
            children.get(children.size() - 1).join(10_000);
            boolean keptWhileTheLeadRuns = HireRegistry.shared().undeliveredFor(HireRegistry.shared().rootOf(lead)).stream()
                .anyMatch(e -> child.equals(e.planId()));
            leadRun.close();

            assertAll(
                () -> assertTrue(ended.text().contains("ended before it answered"), "CONTROL: the brain answered the report's turn: " + ended.text()),
                () -> assertTrue(ended.stoppedBy().isPresent(), "a lead whose brain's output ended with a report unread was reported as a clean run"),
                () -> assertFalse(keptWhileTheLeadRuns, "the report went to the chat while its lead still ran"),
                () -> assertTrue(HireRegistry.shared().undeliveredFor(HireRegistry.shared().rootOf(lead)).stream().anyMatch(e -> child.equals(e.planId())),
                    "the report shown to a brain whose output ended was taken as read and lost"));
        } finally {
            leadRun.close();
            HireRegistry.shared().undeliveredFor(HireRegistry.shared().rootOf(lead)).forEach(HireRegistry.shared()::markDelivered);
        }
    }

    @Test
    @DisplayName("the C27 audit: a lead whose brain dies while a hire of its runs long ends within seconds, not after its own wall-clock ceiling")
    void aLeadWhoseBrainDiesWithALongHireOutEndsWithinSeconds() throws Exception {
        String lead = "c27-lead-died-long";
        HireRegistry.Registration leadRun = HireRegistry.shared().begin("pm", "c27 a lead whose brain dies with a long hire out", "c27-main-4");
        try {
            HireRegistry.shared().planOpened(lead);
            HireRegistry.Inbox watching = HireRegistry.shared().inboxFor(lead);
            aHireIsOut(lead, "c27-child-long", "the code is written", 30_000, null);
            long began = System.nanoTime();
            HiredBrain.Outcome ended = runAsLead("ANSWERTHENDIE", HiredBrain.Ceilings.of(Duration.ofSeconds(120)), lead, watching);
            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - began);

            assertAll(
                () -> assertEquals("noted", ended.text(), "CONTROL: the lead's brain did not answer its turn before it died (clean=" + ended.clean() + ", stopped by "
                    + ended.stoppedBy() + ", its stderr: " + ended.stderrTail() + ")"),
                () -> assertTrue(seconds < 15, "the lead waited " + seconds + " s on a dead brain while its hire ran on"),
                () -> assertTrue(ended.stoppedBy().isPresent(), "a lead whose brain died with a hire out was reported as a clean run"));
        } finally {
            children.get(children.size() - 1).interrupt();
            leadRun.close();
            HireRegistry.shared().undeliveredFor(HireRegistry.shared().rootOf(lead)).forEach(HireRegistry.shared()::markDelivered);
        }
    }

    @Test
    @DisplayName("28h Stage 27, finding 28h-C27-1: a hire the lead's brain started but the engine saw under another caller id is the lead's once its call's answer names the plan, so the lead waits for its report and is prompted with it")
    void aHireSeenUnderAnotherCallerIdIsTheLeadsOnceItsAnswerNamesIt() throws Exception {
        String lead = "c27-lead";
        // THE CONTROL: the hire is out under the engine's own session id and the lead's brain never says it started one, so nothing is the lead's.
        HireRegistry.Inbox unaware = HireRegistry.shared().inboxFor("c27-lead-unaware");
        aHireIsOut("c27-server-session-a", "c27-child-a", "the code is written", 1_500, null);
        HiredBrain.Outcome ignored = runAsLead("REMEMBER:bluebird", HiredBrain.Ceilings.of(Duration.ofSeconds(60)), "c27-lead-unaware", unaware);

        HireRegistry.Inbox watching = HireRegistry.shared().inboxFor(lead);
        aHireIsOut("c27-server-session-b", "c27-child-b", "the code is written", 1_500, null);
        HiredBrain.Outcome reported = runAsLead("STARTHIRE:c27-child-b", HiredBrain.Ceilings.of(Duration.ofSeconds(60)), lead, watching);

        assertAll(
            () -> assertEquals("noted", ignored.text(), "the control was prompted with a report of a hire that was not its own"),
            () -> assertTrue(reported.text().contains("report read: Hire: Joe · developer (c27-child-b)"),
                "the lead never waited for the hire its brain started, or was never prompted with its report: " + reported.text()));
    }

    @Test
    @DisplayName("28h-C27-1: a hire that closed and has not yet had its report kept when the lead names it is counted until the report comes, and the count never goes wrong")
    void aHireNamedBetweenItsCloseAndItsReportIsStillWaitedFor() {
        String lead = "c27-lead-window";
        try (HireRegistry.Inbox watching = HireRegistry.shared().inboxFor(lead)) {
            HireRegistry.shared().nextHireIsBackground();
            try (HireRegistry.Registration closing = HireRegistry.shared().begin("developer", "window c27-child-w", "c27-server-session-w")) {
                HireRegistry.shared().planOpened("c27-child-w");
            }

            HireRegistry.shared().startedBy("c27-child-w", lead);
            int whileTheReportIsStillToCome = watching.outstanding();
            HireRegistry.shared().record("developer", "window c27-child-w", JSON.createObjectNode().put("plan", "c27-child-w").put("name", "Joe"));

            assertAll(
                () -> assertEquals(1, whileTheReportIsStillToCome, "the lead would have ended instead of waiting for this report"),
                () -> assertEquals(0, watching.outstanding(), "the count went wrong, so a later hire would not be waited for"),
                () -> assertFalse(watching.isEmpty(), "the report did not reach the lead's inbox"));
        }
    }

    @Test
    @DisplayName("28h-C27-1: a hire that closes between the lead naming it and the lead's inbox hearing of it is not counted as out for ever")
    void aHireClosingWhileTheLeadTakesItOverIsNotCountedForEver() {
        String lead = "c27-lead-race";
        HireRegistry.Registration[] hire = new HireRegistry.Registration[1];
        // Subscribed before the inbox, so it hears the hire named first: the hire ends and its report is kept before the inbox hears the name.
        Runnable ending = HireRegistry.shared().subscribe(event -> {
            if (lead.equals(event.leadId()) && "started".equals(event.kind()) && hire[0] != null) {
                HireRegistry.Registration closing = hire[0];
                hire[0] = null;
                closing.close();
                HireRegistry.shared().record("developer", "race c27-child-r", JSON.createObjectNode().put("plan", "c27-child-r").put("name", "Joe"));
            }
        });
        try (HireRegistry.Inbox watching = HireRegistry.shared().inboxFor(lead)) {
            HireRegistry.shared().nextHireIsBackground();
            hire[0] = HireRegistry.shared().begin("developer", "race c27-child-r", "c27-server-session-r");
            HireRegistry.shared().planOpened("c27-child-r");

            HireRegistry.shared().startedBy("c27-child-r", lead);

            assertAll(
                () -> assertTrue(hire[0] == null, "CONTROL: the hire never ended while it was being taken over"),
                () -> assertFalse(watching.isEmpty(), "the report did not reach the lead's inbox"),
                () -> assertEquals(0, watching.outstanding(), "a reported hire is counted as out for ever, so the lead waits on nothing"));
        } finally {
            ending.run();
        }
    }

    @Test
    @DisplayName("28h-C27-1: a hire that had already ended when the lead's brain named it still reaches that lead's brain")
    void aHireThatEndedBeforeTheLeadLearnedOfItIsStillReportedToIt() throws Exception {
        String lead = "c27-lead-late";
        HireRegistry.Inbox watching = HireRegistry.shared().inboxFor(lead);
        aHireIsOut("c27-server-session-c", "c27-child-c", "the code is written", 0, null);
        children.get(children.size() - 1).join(5_000);

        HiredBrain.Outcome reported = runAsLead("STARTHIRE:c27-child-c", HiredBrain.Ceilings.of(Duration.ofSeconds(60)), lead, watching);

        assertAll(
            () -> assertTrue(reported.text().contains("report read: Hire: Joe · developer (c27-child-c)"),
                "the report of a hire that ended first was lost: " + reported.text()),
            () -> assertEquals(0, watching.outstanding(), "the count of hires out was left wrong, so a later hire would not be waited for"));
    }

    @Test
    @DisplayName("28h D57 (l): a hired lead's session is opened with its brain's own editors and shell withheld, as the principal's is, since a lead plans and hires and changes no code")
    void aLeadRunsWithItsOwnEditorsAndShellWithheld() throws Exception {
        String lead = "d57l-lead";
        HiredBrain.Outcome outcome = runAsLead("WITHHELD", HiredBrain.Ceilings.of(Duration.ofSeconds(60)), lead, HireRegistry.shared().inboxFor(lead));

        for (String tool : List.of("Edit", "Write", "MultiEdit", "NotebookEdit", "Bash")) {
            assertTrue(outcome.text().contains("\"" + tool + "\""), tool + " was not withheld from a lead: " + outcome.text());
        }
    }

    @Test
    @DisplayName("z109, z113: a lead stopped by its cost ceiling while a hire of its own runs names that hire and its plan id, which now belong to whoever hired the lead")
    void aLeadStoppedByItsCostCeilingNamesItsRunningHires() throws Exception {
        String lead = "z109-lead-cost";
        HireRegistry.Inbox inbox = HireRegistry.shared().inboxFor(lead);
        aHireIsOut(lead, "z109-child", "done", 3_000, null);

        HiredBrain.Outcome capped = runAsLead("SPEND", new HiredBrain.Ceilings(Duration.ofSeconds(60), 0, 0.0001), lead, inbox);

        String stopped = capped.stoppedBy().orElse("");
        assertAll(
            () -> assertTrue(stopped.startsWith("cost: "), "CONTROL: the lead was not stopped by its cost ceiling: " + stopped),
            () -> assertTrue(stopped.contains("still running") && stopped.contains("(z109-child)"),
                "the stop does not name the hire left running: " + stopped));
    }

    @Test
    @DisplayName("T9: a lead's tool-step ceiling is counted across its turns, so a lead that keeps going stops; with no ceiling the same two turns end clean")
    void aLeadOverSeveralTurnsStopsAtItsToolStepCeiling() throws Exception {
        // THE CONTROL: no ceiling, the same two turns of five tool calls each end clean.
        String free = "m12-lead-free";
        HireRegistry.Inbox freeInbox = HireRegistry.shared().inboxFor(free);
        // 28h z52: only a child's REPORT section reaches the lead, so the word that makes the stand-in spend again is written there.
        aHireIsOut(free, "m12-child-free", "REPORT: SPEND", 1_500, null);
        HiredBrain.Outcome unlimited = runAsLead("SPEND", HiredBrain.Ceilings.of(Duration.ofSeconds(60)), free, freeInbox);

        // WITH A CEILING OF SEVEN across the turns: the first turn spends five, and the second goes over.
        String bounded = "m12-lead-bounded";
        HireRegistry.Inbox boundedInbox = HireRegistry.shared().inboxFor(bounded);
        aHireIsOut(bounded, "m12-child-bounded", "REPORT: SPEND", 1_500, null);
        HiredBrain.Outcome capped = runAsLead("SPEND", new HiredBrain.Ceilings(Duration.ofSeconds(60), 7, null), bounded, boundedInbox);

        assertAll(
            () -> assertTrue(unlimited.stoppedBy().isEmpty(), "the control stopped without a ceiling: " + unlimited.stoppedBy()),
            () -> assertEquals(10, unlimited.toolSteps(), "the control did not run both turns of five tool calls"),
            () -> assertTrue(capped.stoppedBy().isPresent(), "a lead over its ceiling across turns was not stopped"),
            () -> assertTrue(capped.stoppedBy().orElse("").contains("over the ceiling of 7"), String.valueOf(capped.stoppedBy())));
    }
}
