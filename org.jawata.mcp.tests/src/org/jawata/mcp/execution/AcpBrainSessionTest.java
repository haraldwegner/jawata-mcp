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
        return AcpBrainSession.run(host, agent, HireSite.in(dir), prompt, mcp, allowed, resume, ceilings, null, null, update -> { });
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
    @DisplayName("S7a: a brain that refuses a session with 'Authentication required' and lists a login method is logged in with that method and then answers, as Cursor's agent requires")
    void aBrainThatAsksForLoginIsLoggedIn() throws Exception {
        agent = new AcpBrainSession.Program(agent.command(), Map.of("FAKE_ACP_STATE", dir.toString(), "FAKE_ACP_LOGIN", "1"));

        HiredBrain.Outcome outcome = hire("REMEMBER:bluebird");

        assertEquals("noted", outcome.text(), "the brain refused the session and the client never logged in; stderr " + outcome.stderrTail());
        assertTrue(outcome.brainSessionId().isPresent(), "no session was opened after the login");
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
    @DisplayName("S3, D30: an endpoint bound to a session hands the brain that session id as a header on the tool server, and an unbound one hands none")
    void aSessionBoundEndpointSendsItsSessionHeader() throws Exception {
        SelfEndpoint bound = new SelfEndpoint("http://127.0.0.1:41234/mcp", "secret-key").forSession("plan-7");

        String with = hire("SERVERS", List.of(), Optional.empty(), HiredBrain.Ceilings.of(Duration.ofSeconds(60)), bound).text();
        String without = hire("SERVERS", List.of(), Optional.empty(), HiredBrain.Ceilings.of(Duration.ofSeconds(60)),
            new SelfEndpoint("http://127.0.0.1:41234/mcp", "secret-key")).text();

        assertTrue(with.contains("Mcp-Session-Id") && with.contains("plan-7"), with);
        assertFalse(without.contains("Mcp-Session-Id"), "CONTROL: an unbound endpoint sent a session header: " + without);
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
    @DisplayName("S7a: a tool the brain titles in Cursor's form 'jawata-<tool>: <tool>' is judged by the allowlist as that tool, and the same tool of another server is not")
    void theAllowlistReadsCursorsTitleForTheResidentsToolsOnly() throws Exception {
        HiredBrain.Ceilings ceilings = HiredBrain.Ceilings.of(Duration.ofSeconds(60));

        HiredBrain.Outcome listed = hire("CALL:jawata-inspect: inspect", List.of("inspect"), Optional.empty(), ceilings, null);
        HiredBrain.Outcome unlisted = hire("CALL:jawata-rename_symbol: rename_symbol", List.of("inspect"), Optional.empty(), ceilings, null);
        HiredBrain.Outcome foreign = hire("CALL:other-inspect: inspect", List.of("inspect"), Optional.empty(), ceilings, null);

        assertEquals("permission:allow_once", listed.text(), "a listed tool of the resident was refused in Cursor's form");
        assertEquals("permission:reject_once", unlisted.text(), "an unlisted tool of the resident was allowed");
        assertEquals("permission:reject_once", foreign.text(), "a tool of another server was allowed because its name matched");
    }

    @Test
    @DisplayName("D22, the loop for lookups: a seat that may use the shell is refused a text search over Java source, and is allowed a build and a declared fallback")
    void aShellSearchOverJavaSourceIsRefusedAtThePermissionRequest() throws Exception {
        HiredBrain.Ceilings ceilings = HiredBrain.Ceilings.of(Duration.ofSeconds(60));
        List<String> shellSeat = List.of("Bash");

        HiredBrain.Outcome search = hire("SHELL:grep -rn 'Foo' src/Foo.java", shellSeat, Optional.empty(), ceilings, null);
        HiredBrain.Outcome build = hire("SHELL:mvn -q -Dtest=FooTest test", shellSeat, Optional.empty(), ceilings, null);
        HiredBrain.Outcome declared = hire("SHELL:grep -rn 'Foo' src/Foo.java # jawata-fallback: inside a comment", shellSeat, Optional.empty(), ceilings, null);
        HiredBrain.Outcome notListed = hire("SHELL:mvn -q test", List.of(), Optional.empty(), ceilings, null);

        assertEquals("permission:reject_once", search.text(), "a shell search over Java source was allowed");
        assertEquals("permission:allow_once", build.text(), "CONTROL: a build was refused by the lookup driver");
        assertEquals("permission:allow_once", declared.text(), "CONTROL: a search that declares its fallback was refused");
        assertEquals("permission:reject_once", notListed.text(), "CONTROL: the allowlist still refuses what it does not list");
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
    @DisplayName("S9: while a hire's brain works, the registry of running hires holds the tool calls it has made and the context size and cost its last usage report carried, and drops them when the hire ends")
    void aRunningHiresProgressFollowsItsBrainsStream() throws Exception {
        List<String> seen = new java.util.ArrayList<>();
        try (RunningHires.Registration running = RunningHires.shared().begin("developer", "SPEND")) {
            AcpBrainSession.run(host, agent, HireSite.in(dir), "SPEND", null, List.of(), Optional.empty(),
                HiredBrain.Ceilings.of(Duration.ofSeconds(60)), null, update -> {
                    RunningHires.Progress now = RunningHires.shared().progress("developer", "SPEND").orElseThrow();
                    seen.add(now.toolSteps() + " calls, context " + now.contextTokens() + ", cost " + now.costUsd());
                }, update -> { });

            assertEquals(List.of(
                "1 calls, context OptionalLong.empty, cost OptionalDouble.empty",
                "2 calls, context OptionalLong[100], cost OptionalDouble[0.25]",
                "3 calls, context OptionalLong[200], cost OptionalDouble[0.5]",
                "4 calls, context OptionalLong[300], cost OptionalDouble[0.75]",
                "5 calls, context OptionalLong[400], cost OptionalDouble[1.0]"), seen,
                "the figures a reader sees while the brain works did not follow its stream");
            assertEquals(new RunningHires.Progress(5, java.util.OptionalLong.of(500), java.util.OptionalDouble.of(1.25)),
                RunningHires.shared().progress("developer", "SPEND").orElseThrow(), "the last usage report was not kept");
            assertTrue(RunningHires.shared().progress("developer", "another brief").isEmpty(),
                "CONTROL: a hire of the same seat for another brief was given this hire's figures");
        }
        assertTrue(RunningHires.shared().progress("developer", "SPEND").isEmpty(), "the figures outlived the hire");
    }

    @Test
    @DisplayName("the wall clock reaps a brain that never answers, and says so")
    void theWallClockReapsAHungBrain() throws Exception {
        HiredBrain.Outcome outcome = hire("HANG", List.of(), Optional.empty(), HiredBrain.Ceilings.of(Duration.ofSeconds(3)), null);

        assertTrue(outcome.timedOut(), "the hung brain was not reaped by the clock: " + outcome);
        assertFalse(outcome.clean());
    }

    @Test
    @DisplayName("the brain's thinking and its plan update reach their sink in the order they were streamed, and the thinking is not taken for the answer (contract R9, R10)")
    void thinkingAndAPlanReachTheirSinkInOrder() throws Exception {
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();

        HiredBrain.Outcome outcome = AcpBrainSession.run(host, agent, HireSite.in(dir), "THINK", null, List.of(), Optional.empty(),
            HiredBrain.Ceilings.of(Duration.ofSeconds(60)), null, null,
            update -> seen.add(update.path("sessionUpdate").asText() + ":"
                + update.path("content").path("text").asText(update.path("entries").size() + " entries")));

        assertEquals(List.of("agent_thought_chunk:Checking divisibility ", "plan:2 entries", "agent_thought_chunk:by 7 and 13."), seen);
        assertEquals("thought", outcome.text(), "the thinking was taken for the answer");
    }

    @Test
    @DisplayName("a tool call that Cursor's agent opens as a placeholder and names in a later update is known by the server and tool named there")
    void aToolCallNamedByALaterUpdateIsKnownByItsName() throws Exception {
        HiredBrain.Outcome outcome = hire("CURSORCALL");

        assertEquals(List.of("mcp__jawata__health_check"), outcome.toolNames(), "the placeholder title stayed: " + outcome.toolNames());
        assertEquals(1, outcome.toolSteps());
        assertEquals(0, outcome.unnamedToolSteps());
    }

    @Test
    @DisplayName("a brain that answered its prompt and then does not exit when its input closes is ended by the client, and that kill is not counted as a crash")
    void aBrainThatLingersAfterItsAnswerIsStillClean() throws Exception {
        agent = new AcpBrainSession.Program(agent.command(), Map.of("FAKE_ACP_STATE", dir.toString(), "FAKE_ACP_LINGER", "1"));

        HiredBrain.Outcome outcome = hire("REMEMBER:bluebird");

        assertEquals("noted", outcome.text());
        assertEquals(0, outcome.exitCode().orElse(-1), "the client's own kill was reported as the brain's exit status");
        assertTrue(outcome.clean(), "a finished answer was reported as an unclean run: " + outcome);
        assertFalse(outcome.timedOut());
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
