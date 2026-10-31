package org.jawata.mcp.execution;

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
import java.util.Set;

import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.agent.Hirer;
import org.jawata.mcp.agent.SeatTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, S6 (D1, D4, D28): the engine as an ACP client, run against a stand-in agent that speaks the protocol over stdio (the
 * resource {@code FakeAcpAgent.java.txt}, a single-file Java program, so no other tool is needed on any operating system). What is
 * proved here is the client: the handshake, a session opened or loaded, the seat's allowlist answering the brain's permission requests,
 * the resident named as the brain's tool server, the ceilings bounding the run, and the session id coming back for the next hire. That
 * the same holds against a real vendor's brain is the stage's live exit, run separately.
 */
class AcpBrainSessionTest {

    private final HostProcesses host = HostProcesses.system();
    private Path dir;
    private AcpBrainSession.Program agent;

    @BeforeEach
    void startAgentSource() throws IOException {
        dir = Files.createTempDirectory("jawata-acp-brain-");
        try (InputStream in = AcpBrainSessionTest.class.getResourceAsStream("/test-resources/acp/FakeAcpAgent.java.txt")) {
            assertNotNull(in, "the stand-in agent is not among the test resources");
            Files.write(dir.resolve("FakeAcpAgent.java"), in.readAllBytes());
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        agent = new AcpBrainSession.Program(List.of(java.toString(), dir.resolve("FakeAcpAgent.java").toString()),
            Map.of("FAKE_ACP_STATE", dir.toString()));
    }

    @AfterEach
    void removeWhatWasCreated() throws IOException {
        if (dir != null) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private HiredBrain.Outcome hire(String prompt, List<String> allowed, Optional<String> resume, HiredBrain.Ceilings ceilings,
            SelfEndpoint mcp) throws Exception {
        return AcpBrainSession.run(host, agent, HireSite.in(dir), prompt, mcp, allowed, resume, ceilings);
    }

    private HiredBrain.Outcome hire(String prompt) throws Exception {
        return hire(prompt, List.of(), Optional.empty(), HiredBrain.Ceilings.of(Duration.ofSeconds(60)), null);
    }

    @Test
    @DisplayName("a prompt is answered, the brain exits cleanly, and the outcome carries the session id the brain opened")
    void aPromptIsAnsweredAndTheSessionIdComesBack() throws Exception {
        HiredBrain.Outcome outcome = hire("REMEMBER:bluebird");

        assertEquals("noted", outcome.text());
        assertTrue(outcome.clean(), "the brain did not end cleanly: exit " + outcome.exitCode() + " stderr " + outcome.stderrTail());
        assertTrue(outcome.brainSessionId().isPresent(), "no session id came back");
        assertEquals(0, outcome.toolSteps());
    }

    @Test
    @DisplayName("D4: a second hire given the first one's session id answers what only the first saw, and the replayed history is not its answer")
    void aSecondHireKnowsWhatTheFirstSaw() throws Exception {
        String first = hire("REMEMBER:bluebird").brainSessionId().orElseThrow();

        HiredBrain.Outcome resumed = hire("RECALL", List.of(), Optional.of(first), HiredBrain.Ceilings.of(Duration.ofSeconds(60)), null);
        HiredBrain.Outcome fresh = hire("RECALL");

        assertEquals("bluebird", resumed.text(), "the resumed hire did not know what the first one saw");
        assertEquals(Optional.of(first), resumed.brainSessionId());
        assertFalse(resumed.text().contains("HISTORY"), "the history replayed on load was taken for this run's answer");
        assertEquals("nothing", fresh.text(), "CONTROL: a hire that was not given the session knew it anyway");
    }

    @Test
    @DisplayName("D28: the resident is named to the brain as its one tool server, over HTTP with the key")
    void theResidentIsTheBrainsOneToolServer() throws Exception {
        HiredBrain.Outcome outcome = hire("SERVERS", List.of(), Optional.empty(), HiredBrain.Ceilings.of(Duration.ofSeconds(60)),
            new SelfEndpoint("http://127.0.0.1:41234/mcp", "secret-key"));

        assertTrue(outcome.text().contains("\"type\":\"http\""), outcome.text());
        assertTrue(outcome.text().contains("\"name\":\"jawata\""), outcome.text());
        assertTrue(outcome.text().contains("http://127.0.0.1:41234/mcp"), outcome.text());
        assertTrue(outcome.text().contains("Bearer secret-key"), outcome.text());
        assertEquals("[]", hire("SERVERS").text(), "CONTROL: a hire with no endpoint was still given a tool server");
    }

    @Test
    @DisplayName("D28: the seat's allowlist answers the brain's permission requests: a listed tool is allowed, an unlisted one and an empty list reject")
    void theAllowlistAnswersPermissionRequests() throws Exception {
        HiredBrain.Ceilings ceilings = HiredBrain.Ceilings.of(Duration.ofSeconds(60));

        HiredBrain.Outcome listed = hire("TOOL", List.of("inspect"), Optional.empty(), ceilings, null);
        HiredBrain.Outcome unlisted = hire("WRITE", List.of("inspect"), Optional.empty(), ceilings, null);
        HiredBrain.Outcome none = hire("TOOL", List.of(), Optional.empty(), ceilings, null);

        assertEquals("permission:allow_once", listed.text());
        assertEquals("permission:reject_once", unlisted.text());
        assertEquals("permission:reject_once", none.text(), "an empty allowlist allowed a tool");
        assertEquals(List.of("mcp__jawata__inspect"), listed.toolNames(), "the call was not named in the outcome");
        assertEquals("1", listed.toolCalls().get(0).arguments().path("a").asText(), "the call's arguments were not kept");
    }

    @Test
    @DisplayName("E4: a lead's code-changing call is refused and a developer's identical call is not, through the allowlist the hirer builds")
    void aLeadsCodeChangingCallIsRefusedAndADevelopersIsNot() throws Exception {
        List<String> engineTools = List.of("inspect", "rename_symbol");
        Hirer hirer = new Hirer(SeatTree.standard(), Map.of(), Set.of("rename_symbol"));
        HiredBrain.Ceilings ceilings = HiredBrain.Ceilings.of(Duration.ofSeconds(60));

        HiredBrain.Outcome lead = hire("CALL:mcp__jawata__rename_symbol", hirer.allowlist("chief-engineer", engineTools), Optional.empty(), ceilings, null);
        HiredBrain.Outcome developer = hire("CALL:mcp__jawata__rename_symbol", hirer.allowlist("developer", engineTools), Optional.empty(), ceilings, null);
        HiredBrain.Outcome leadReading = hire("CALL:mcp__jawata__inspect", hirer.allowlist("chief-engineer", engineTools), Optional.empty(), ceilings, null);

        assertEquals("permission:reject_once", lead.text(), "a lead's code-changing call was allowed");
        assertEquals("permission:allow_once", developer.text(), "a developer's identical call was refused");
        assertEquals("permission:allow_once", leadReading.text(), "CONTROL: a lead's reading call was refused too");
    }

    @Test
    @DisplayName("a tool-step ceiling stops the run while it is going, not after: the brain is reaped well before its wall clock")
    void aStepCeilingBoundsTheRun() throws Exception {
        long started = System.nanoTime();
        HiredBrain.Outcome outcome = hire("SPEND", List.of(), Optional.empty(),
            new HiredBrain.Ceilings(Duration.ofSeconds(60), 2, null), null);

        assertTrue(outcome.stoppedBy().isPresent(), "the ceiling did not stop the run: " + outcome);
        assertTrue(outcome.stoppedBy().get().startsWith("tool steps"), outcome.stoppedBy().get());
        assertFalse(outcome.timedOut(), "the wall clock fired, so the ceiling bounded nothing");
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 30, "the run took as long as a wall clock would");
        assertFalse(outcome.text().contains("spent"), "the brain was allowed to finish its answer");
    }

    @Test
    @DisplayName("a cost ceiling stops the run on the cost the brain reported")
    void aCostCeilingBoundsTheRun() throws Exception {
        HiredBrain.Outcome outcome = hire("SPEND", List.of(), Optional.empty(),
            new HiredBrain.Ceilings(Duration.ofSeconds(60), 0, 0.6), null);

        assertTrue(outcome.stoppedBy().orElse("").startsWith("cost"), String.valueOf(outcome.stoppedBy()));
        assertFalse(outcome.timedOut());
    }

    @Test
    @DisplayName("the wall clock reaps a brain that never answers, and says so")
    void theWallClockReapsAHungBrain() throws Exception {
        HiredBrain.Outcome outcome = hire("HANG", List.of(), Optional.empty(), HiredBrain.Ceilings.of(Duration.ofSeconds(3)), null);

        assertTrue(outcome.timedOut(), "the hung brain was not reaped by the clock: " + outcome);
        assertFalse(outcome.clean());
    }

    @Test
    @DisplayName("a brain that dies mid-prompt is a failure, not an empty answer")
    void aBrainThatDiesIsNotSilence() throws Exception {
        HiredBrain.Outcome outcome = hire("DIE");

        assertFalse(outcome.clean());
        assertFalse(outcome.saidNothing(), "a crash was reported as the model saying nothing");
        assertEquals(3, outcome.exitCode().orElse(-1));
        assertTrue(outcome.text().contains("ended before it answered"), outcome.text());
    }
}
