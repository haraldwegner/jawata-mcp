package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
     */
    private void aHireIsOut(String leadId, String planId, String answer) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        Thread child = new Thread(() -> {
            HireRegistry.shared().nextHireIsBackground();
            try (HireRegistry.Registration ignored = HireRegistry.shared().begin("developer", "m12 " + planId, leadId)) {
                HireRegistry.shared().planOpened(planId);
                started.countDown();
                Thread.sleep(1_500);
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
            inbox, leadId);
    }

    @Test
    @DisplayName("a lead with a hire out is prompted with its report and answers it; a lead with nothing out ends after its first turn")
    void aLeadIsPromptedWithTheReportOfAHireItStarted() throws Exception {
        // THE CONTROL: nothing is out, so the run is one turn, as it was.
        HiredBrain.Outcome alone = runAsLead("REMEMBER:bluebird", HiredBrain.Ceilings.of(Duration.ofSeconds(60)), "m12-lead-alone",
            HireRegistry.shared().inboxFor("m12-lead-alone"));

        String lead = "m12-lead-reported";
        HireRegistry.Inbox watching = HireRegistry.shared().inboxFor(lead);
        aHireIsOut(lead, "m12-child-report", "the code is written");
        HiredBrain.Outcome reported = runAsLead("REMEMBER:bluebird", HiredBrain.Ceilings.of(Duration.ofSeconds(60)), lead, watching);

        assertAll(
            () -> assertEquals("noted", alone.text(), "a lead with nothing out was prompted again"),
            () -> assertTrue(reported.text().contains("report read: Hire: Joe · developer (m12-child-report)"),
                "the lead was never prompted with the report of its hire: " + reported.text()),
            () -> assertTrue(reported.clean(), "the lead's run did not end cleanly: " + reported.stderrTail()));
    }

    @Test
    @DisplayName("T9: a lead's tool-step ceiling is counted across its turns, so a lead that keeps going stops; with no ceiling the same two turns end clean")
    void aLeadOverSeveralTurnsStopsAtItsToolStepCeiling() throws Exception {
        // THE CONTROL: no ceiling, the same two turns of five tool calls each end clean.
        String free = "m12-lead-free";
        HireRegistry.Inbox freeInbox = HireRegistry.shared().inboxFor(free);
        aHireIsOut(free, "m12-child-free", "SPEND");
        HiredBrain.Outcome unlimited = runAsLead("SPEND", HiredBrain.Ceilings.of(Duration.ofSeconds(60)), free, freeInbox);

        // WITH A CEILING OF SEVEN across the turns: the first turn spends five, and the second goes over.
        String bounded = "m12-lead-bounded";
        HireRegistry.Inbox boundedInbox = HireRegistry.shared().inboxFor(bounded);
        aHireIsOut(bounded, "m12-child-bounded", "SPEND");
        HiredBrain.Outcome capped = runAsLead("SPEND", new HiredBrain.Ceilings(Duration.ofSeconds(60), 7, null), bounded, boundedInbox);

        assertAll(
            () -> assertTrue(unlimited.stoppedBy().isEmpty(), "the control stopped without a ceiling: " + unlimited.stoppedBy()),
            () -> assertEquals(10, unlimited.toolSteps(), "the control did not run both turns of five tool calls"),
            () -> assertTrue(capped.stoppedBy().isPresent(), "a lead over its ceiling across turns was not stopped"),
            () -> assertTrue(capped.stoppedBy().orElse("").contains("over the ceiling of 7"), String.valueOf(capped.stoppedBy())));
    }
}
