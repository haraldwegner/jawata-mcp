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
        return AcpBrainSession.run(host, agent, HireSite.in(dir), prompt, mcp, allowed, resume, ceilings, null, null, update -> { }, null);
    }

    private HiredBrain.Outcome hire(String prompt) throws Exception {
        return hire(prompt, List.of(), Optional.empty(), HiredBrain.Ceilings.of(Duration.ofSeconds(60)), null);
    }

    /** One prompt to a conversation started with the brain's own tools as given, the conversation closed after. */
    private String converse(String prompt, List<String> allowed, AcpBrainSession.OwnTools ownTools) throws Exception {
        try (AcpBrainSession.Conversation chat = AcpBrainSession.Conversation.start(host, agent, HireSite.in(dir), null, allowed, Optional.empty(),
                HiredBrain.Ceilings.of(Duration.ofSeconds(60)), ownTools)) {
            return chat.prompt(prompt, List.of(), HiredBrain.Ceilings.of(Duration.ofSeconds(60)), piece -> { }, null, null).text();
        }
    }

    @Test
    @DisplayName("28h D57 (j): a brain whose own tools are withheld is asked, when its session opens, to disallow its editors and its shell")
    void aWithheldBrainIsAskedToDisallowItsOwnWriters() throws Exception {
        String withheld = converse("WITHHELD", List.of(), AcpBrainSession.OwnTools.WITHHELD);

        for (String tool : List.of("Edit", "Write", "MultiEdit", "NotebookEdit", "Bash")) {
            assertTrue(withheld.contains("\"" + tool + "\""), tool + " was not withheld: " + withheld);
        }
        assertEquals("none", converse("WITHHELD", List.of(), AcpBrainSession.OwnTools.KEPT), "CONTROL: a brain keeping its tools was asked to drop them");
    }

    @Test
    @DisplayName("28h Stage 27 (j), finding 28h-C27-2: a brain is asked, in its session's own options, to load no MCP server but the ones the session names, so a hired brain cannot hire or change files through the user's own jawata server")
    void aBrainIsAskedToLoadOnlyTheServersItIsGiven() throws Exception {
        assertEquals("only the named servers", converse("ONLYOURSERVERS", List.of(), AcpBrainSession.OwnTools.KEPT), "a worker was left to load every server it can find");
        assertEquals("only the named servers", converse("ONLYOURSERVERS", List.of(), AcpBrainSession.OwnTools.WITHHELD), "a lead was left to load every server it can find");
    }

    @Test
    @DisplayName("28h Stage 27 (z11): a brain is asked to load only the person's own settings, never a project's or a local one, whichever tools it keeps, so a settings file in the project it works in cannot widen what it may do")
    void aBrainIsAskedToLoadOnlyThePersonsOwnSettings() throws Exception {
        assertEquals("[\"user\"]", converse("WHICHSETTINGS", List.of(), AcpBrainSession.OwnTools.KEPT), "a worker was left to load a project's settings");
        assertEquals("[\"user\"]", converse("WHICHSETTINGS", List.of(), AcpBrainSession.OwnTools.WITHHELD), "a lead was left to load a project's settings");
        assertEquals("[\"user\"]", hire("WHICHSETTINGS", List.of(), Optional.of("fake-earlier-session"), HiredBrain.Ceilings.of(Duration.ofSeconds(60)), null).text(),
            "a resumed session was left to load a project's settings");
    }

    @Test
    @DisplayName("28h Stage 27 (z11): a brain is asked never to allow the mode that runs without asking, new or resumed, because the Claude adapter reads a project's own default mode itself whatever settings sources it is told to load")
    void aBrainIsAskedNeverToRunWithoutAsking() throws Exception {
        assertEquals("bypass refused", converse("WHICHBYPASS", List.of(), AcpBrainSession.OwnTools.KEPT), "a worker could be put in the mode that runs without asking");
        assertEquals("bypass refused", converse("WHICHBYPASS", List.of(), AcpBrainSession.OwnTools.WITHHELD), "a lead could be put in the mode that runs without asking");
        assertEquals("bypass refused", hire("WHICHBYPASS", List.of(), Optional.of("fake-earlier-session"), HiredBrain.Ceilings.of(Duration.ofSeconds(60)), null).text(),
            "a resumed session could be put in the mode that runs without asking");
    }

    @Test
    @DisplayName("28h Stage 27 (z2): a brain whose own tools are withheld stays in its normal mode although it offers a read-only 'ask' mode, as Cursor's agent does, because 'ask' hides the jawata tools and with them hire")
    void aWithheldBrainStaysInItsNormalMode() throws Exception {
        agent = new AcpBrainSession.Program(agent.command(), Map.of("FAKE_ACP_STATE", dir.toString(), "FAKE_ACP_MODES", "agent,plan,ask"));

        assertEquals("agent", converse("WHICHMODE", List.of(), AcpBrainSession.OwnTools.WITHHELD), "a lead was moved into a mode that hides hire");
        assertEquals("agent", converse("WHICHMODE", List.of(), AcpBrainSession.OwnTools.KEPT), "CONTROL: a brain keeping its tools was moved");
    }

    @Test
    @DisplayName("28h D57 (j): a brain whose own tools are withheld is refused its shell even where the allowlist names it")
    void aWithheldBrainIsRefusedItsShellWhateverTheAllowlist() throws Exception {
        assertEquals("permission:reject_once", converse("SHELL:ls", List.of("Bash"), AcpBrainSession.OwnTools.WITHHELD));
        assertEquals("permission:allow_once", converse("SHELL:ls", List.of("Bash"), AcpBrainSession.OwnTools.KEPT),
            "CONTROL: the allowlist no longer allows the shell of a brain that keeps it");
    }

    @Test
    @DisplayName("28h-C26-7: a model listing opens its brain in the one folder kept between listings, so each brain keeps one record under the home, not one per listing")
    void aModelListingOpensItsBrainInTheKeptFolder() throws Exception {
        java.nio.file.Path kept = AcpBrainSession.listingFolder();
        java.nio.file.Files.delete(kept); // absent now: only a listing that uses it brings it back

        com.fasterxml.jackson.databind.node.ObjectNode registry = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode entry = registry.putObject("claude-code");
        agent.command().forEach(entry.putArray("command")::add);
        agent.environment().forEach(entry.putObject("env")::put);
        AcpBrainSession.modelsOfferedIn(registry.toString(), "claude-code");

        assertTrue(java.nio.file.Files.isDirectory(kept), "THE CLAIM: the listing did not open its brain in the kept folder " + kept);
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
        assertTrue(with.contains("http://127.0.0.1:41234/mcp?session=plan-7"),
            "the session id was handed over only as a header, which a brain's tool server was seen to drop: " + with);
        assertFalse(without.contains("?session="), "CONTROL: an unbound endpoint put a session id in the URL: " + without);
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
    @DisplayName("28h z37: main's brain, whose own tools are withheld, is allowed its web search through the allowlist the hirer builds for main")
    void aWebSearchPermissionIsAllowedForMain() throws Exception {
        Hirer hirer = new Hirer(SeatTree.standard(), Map.of(), Set.of("rename_symbol"));

        assertEquals("permission:allow_once", converse("CALL:WebSearch", hirer.allowlist("main", List.of("inspect", "rename_symbol")), AcpBrainSession.OwnTools.WITHHELD),
            "main's web search was refused");
    }

    @Test
    @DisplayName("28h z37: main's brain, whose own tools are withheld, is allowed its page fetch through the allowlist the hirer builds for main")
    void aWebFetchPermissionIsAllowedForMain() throws Exception {
        Hirer hirer = new Hirer(SeatTree.standard(), Map.of(), Set.of("rename_symbol"));

        assertEquals("permission:allow_once", converse("CALL:WebFetch", hirer.allowlist("main", List.of("inspect", "rename_symbol")), AcpBrainSession.OwnTools.WITHHELD),
            "main's page fetch was refused");
    }

    @Test
    @DisplayName("28h z37: main is still refused its shell with the list the hirer builds for it, and no other seat's list names the web tools")
    void mainIsStillRefusedItsShellAndOnlyMainHasTheWebTools() throws Exception {
        List<String> engineTools = List.of("inspect", "rename_symbol");
        Hirer hirer = new Hirer(SeatTree.standard(), Map.of(), Set.of("rename_symbol"));

        assertEquals("permission:reject_once", converse("SHELL:ls", hirer.allowlist("main", engineTools), AcpBrainSession.OwnTools.WITHHELD),
            "main's shell was allowed");
        for (String seat : List.of("developer", "chief-engineer", "pm", "test-manager")) {
            List<String> list = hirer.allowlist(seat, engineTools);
            assertFalse(list.contains("WebSearch") || list.contains("WebFetch"), seat + " was given a web tool: " + list);
        }
    }

    @Test
    @DisplayName("28h z37: a web search is rejected for the developer through the allowlist the hirer builds for the developer, so the web tools are main's alone")
    void aWebSearchPermissionIsRejectedForTheDeveloper() throws Exception {
        Hirer hirer = new Hirer(SeatTree.standard(), Map.of(), Set.of("rename_symbol"));

        assertEquals("permission:reject_once", converse("CALL:WebSearch", hirer.allowlist("developer", List.of("inspect", "rename_symbol")), AcpBrainSession.OwnTools.WITHHELD),
            "the developer's web search was allowed");
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
        try (HireRegistry.Registration running = HireRegistry.shared().begin("developer", "SPEND", "")) {
            AcpBrainSession.run(host, agent, HireSite.in(dir), "SPEND", null, List.of(), Optional.empty(),
                HiredBrain.Ceilings.of(Duration.ofSeconds(60)), null, update -> {
                    HireRegistry.Progress now = HireRegistry.shared().progress("developer", "SPEND").orElseThrow();
                    seen.add(now.toolSteps() + " calls, context " + now.contextTokens() + ", cost " + now.costUsd());
                }, update -> { }, null);

            assertEquals(List.of(
                "1 calls, context OptionalLong.empty, cost OptionalDouble.empty",
                "2 calls, context OptionalLong[100], cost OptionalDouble[0.25]",
                "3 calls, context OptionalLong[200], cost OptionalDouble[0.5]",
                "4 calls, context OptionalLong[300], cost OptionalDouble[0.75]",
                "5 calls, context OptionalLong[400], cost OptionalDouble[1.0]"), seen,
                "the figures a reader sees while the brain works did not follow its stream");
            assertEquals(new HireRegistry.Progress(5, java.util.OptionalLong.of(500), java.util.OptionalDouble.of(1.25),
                    java.util.Collections.nCopies(5, "mcp__jawata__inspect")),
                HireRegistry.shared().progress("developer", "SPEND").orElseThrow(), "the last usage report, or the calls' titles, were not kept");
            assertTrue(HireRegistry.shared().progress("developer", "another brief").isEmpty(),
                "CONTROL: a hire of the same seat for another brief was given this hire's figures");
        }
        assertTrue(HireRegistry.shared().progress("developer", "SPEND").isEmpty(), "the figures outlived the hire");
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
                + update.path("content").path("text").asText(update.path("entries").size() + " entries")), null);

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
    @DisplayName("28h z61: Antigravity's calls of the engine, titled in either of its two forms, are kept under the server and tool, so a hire's card counts them as jawata calls")
    void antigravitysCallsAreKnownByServerAndTool() throws Exception {
        HiredBrain.Outcome outcome = hire("AGYCALL");

        assertEquals(List.of("mcp__jawata-engine__lookup", "mcp__jawata-javata-dev__experience", "mcp__someone-else__experience"), outcome.toolNames());
        assertEquals("{\"kind\":\"stats\"}", outcome.toolCalls().get(1).arguments().toString(), "the call kept the wrapper instead of its arguments");
        assertEquals("{\"kind\":\"processes\"}", outcome.toolCalls().get(0).arguments().toString());
        assertEquals(2, ToolUse.of(outcome.toolCalls(), 3, java.util.Set.of("lookup", "experience")::contains).jawata(),
            "CONTROL: a server that is not ours was counted as jawata, or ours was not");
    }

    @Test
    @DisplayName("28h D57 (d): a call Claude's ACP adapter opens with an empty input and streams its input in later updates is kept with the whole input, so it can be compared with an instruction")
    void aCallsStreamedInputIsKeptWhole() throws Exception {
        HiredBrain.Outcome outcome = hire("CLAUDECALL");

        assertEquals(List.of("Edit"), outcome.toolNames());
        assertEquals("{\"file_path\":\"/p/notes.md\",\"old_string\":\"colour: red\",\"new_string\":\"colour: blue\"}",
            outcome.toolCalls().get(0).arguments().toString(), "the call kept the empty input it was opened with, or lost it to a later update");
    }

    @Test
    @DisplayName("28h Stage 27 (z5), live: the write of a new file Claude's ACP adapter opens as 'Preparing file…' is kept as a Write of that file, so the test-first rule and the own-write rule can see it")
    void aNewFileClaudeWritesIsKeptAsAWriteOfThatFile() throws Exception {
        HiredBrain.Outcome outcome = hire("CLAUDEWRITE:/p/src/main/java/demo/Extra.java");

        assertEquals(List.of("Write"), outcome.toolNames(), "the write kept the title the adapter opened it with");
        assertEquals("/p/src/main/java/demo/Extra.java", outcome.toolCalls().get(0).arguments().path("file_path").asText(),
            "the write was kept without the file it wrote");
        assertTrue(TestFirst.violations(outcome.toolCalls(), List.of(), null, path -> java.util.Optional.empty()).stream().anyMatch(v -> v.contains("production code was written before")),
            "a production file written with no test seen failing was not read as such");
    }

    @Test
    @DisplayName("28h Stage 19, the hire-time split: the time between a tool call opening and completing on the wire is kept as the time in tools, and a run with no call keeps none")
    void aToolsTimeIsReadFromWhenItsCallOpensAndCloses() throws Exception {
        HiredBrain.Outcome timed = hire("TIMEDCALL");

        assertTrue(timed.toolMillis() >= 250 && timed.toolMillis() < 1_000,
            "the 300 ms the call took was not read as time in a tool: " + timed.toolMillis());
        assertEquals(0L, hire("SERVERS").toolMillis(), "a run that called no tool spent no time in tools, and measured it");
    }

    @Test
    @DisplayName("28h D57 (d): an edit Cursor's agent reports only as the diff of its completed call is kept as an Edit of that file with the text before and after")
    void aCursorEditIsReadFromItsDiff() throws Exception {
        HiredBrain.Outcome outcome = hire("CURSOREDIT");

        assertEquals(List.of("Edit"), outcome.toolNames());
        assertEquals("{\"file_path\":\"/p/notes.md\",\"oldText\":\"colour: red\\n\",\"newText\":\"colour: blue\\n\"}",
            outcome.toolCalls().get(0).arguments().toString(), "the edit was kept with the path alone, so no instruction can be compared with it");
    }

    @Test
    @DisplayName("C27 architect watch: a file Cursor's agent creates, whose diff has no old text, is kept as an Edit of that file like one it changed, so the rules that read editor writes see it")
    void aCursorNewFileIsReadFromItsDiff() throws Exception {
        HiredBrain.Outcome outcome = hire("CURSORNEWFILE");

        assertEquals(List.of("Edit"), outcome.toolNames());
        assertEquals("/p/src/New.java", outcome.toolCalls().get(0).arguments().path("file_path").asText(),
            "the created file was kept without its path, so no rule that reads editor writes can see it");
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

    @Test
    @DisplayName("28h D57 (c): a hire runs on the model its seat names, by the name its session offers; a model it does not offer is refused")
    void aHireRunsOnTheModelItsSeatNames() throws Exception {
        AcpBrainSession.Program withModels = new AcpBrainSession.Program(agent.command(),
            Map.of("FAKE_ACP_STATE", dir.toString(), "FAKE_ACP_MODELS", "default=Default,haiku=Haiku 5.5,opus=Opus 5.5"));
        HiredBrain.Ceilings ceilings = HiredBrain.Ceilings.of(Duration.ofSeconds(60));

        HiredBrain.Outcome onHaiku = AcpBrainSession.run(host, withModels, HireSite.in(dir), "WHICHMODEL", null, List.of(), Optional.empty(), ceilings,
            null, null, update -> { }, "Haiku 5.5");
        assertEquals("haiku", onHaiku.text().strip(), "the session was moved to the value the brain offers Haiku 5.5 under");

        IOException refused = org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> AcpBrainSession.run(host, withModels,
            HireSite.in(dir), "WHICHMODEL", null, List.of(), Optional.empty(), ceilings, null, null, update -> { }, "Sonnet 9"));
        assertTrue(refused.getMessage().contains("Sonnet 9") && refused.getMessage().contains("Haiku 5.5"),
            "the refusal names the model asked for and the ones offered: " + refused.getMessage());
    }

    /** What asking a session for a model answered, and the value the session answered when asked which model it is on. */
    private record Asked(AcpBrainSession.ModelChoice choice, String which) {
    }

    /** One conversation with a brain offering {@code models} ("value=Name,..." with the first as its default), asked for {@code wanted}. */
    private Asked asked(String models, String wanted) throws Exception {
        AcpBrainSession.Program withModels = new AcpBrainSession.Program(agent.command(),
            Map.of("FAKE_ACP_STATE", dir.toString(), "FAKE_ACP_MODELS", models));
        HiredBrain.Ceilings ceilings = HiredBrain.Ceilings.of(Duration.ofSeconds(60));
        try (AcpBrainSession.Conversation chat = AcpBrainSession.Conversation.start(host, withModels, HireSite.in(dir), null, List.of(),
                Optional.empty(), ceilings, AcpBrainSession.OwnTools.KEPT)) {
            AcpBrainSession.ModelChoice choice = chat.chooseModel(wanted);
            return new Asked(choice, chat.prompt("WHICHMODEL", List.of(), ceilings, piece -> { }, null, null).text().strip());
        }
    }

    @Test
    @DisplayName("z38: a brain asked for \"latest:grok\" is moved to the offered model of that family with the highest version, whatever order the brain lists them in")
    void theLatestOfAFamilyIsTheOneWithTheHighestVersion() throws Exception {
        AcpBrainSession.Model newest = new AcpBrainSession.Model("grok-9.2", "Grok 9.2");
        for (String order : List.of("default=Default,grok-4.5=Grok 4.5,grok-9.2=Grok 9.2", "grok-9.2=Grok 9.2,default=Default,grok-4.5=Grok 4.5",
                "grok-4.5=Grok 4.5,grok-9.2=Grok 9.2,default=Default")) {
            Asked asked = asked(order, "latest:grok");
            assertTrue(asked.choice().moved(), "the session was not moved with the models listed as " + order + ": " + asked.choice());
            assertEquals(Optional.of(newest), asked.choice().now(), "the session is not on the newest Grok with the models listed as " + order);
            assertEquals("grok-9.2", asked.which(), "the brain itself is not on the newest Grok with the models listed as " + order);
        }

        Asked tens = asked("default=Default,grok-9.2=Grok 9.2,grok-10.1=Grok 10.1", "LATEST: Grok");
        assertEquals("grok-10.1", tens.which(), "versions were compared as text, not group by group as numbers: " + tens.choice());
    }

    @Test
    @DisplayName("z38: a brain that offers no model of the family asked for stays on its default, and says which family and which models it offers")
    void aFamilyTheBrainDoesNotOfferLeavesTheSessionOnItsDefault() throws Exception {
        Asked asked = asked("default=Default,haiku=Haiku 5.5,opus=Opus 5.5", "latest:grok");

        assertFalse(asked.choice().moved(), "a session was moved to a model of a family it does not offer: " + asked.choice());
        assertEquals("default", asked.which(), "the session did not stay on its default");
        assertTrue(asked.choice().said().toLowerCase().contains("grok"), "the answer does not name the family: " + asked.choice().said());
        assertTrue(asked.choice().said().contains("Haiku 5.5") && asked.choice().said().contains("Opus 5.5"),
            "the answer does not mention the models offered: " + asked.choice().said());
        assertEquals(List.of(new AcpBrainSession.Model("default", "Default"), new AcpBrainSession.Model("haiku", "Haiku 5.5"),
            new AcpBrainSession.Model("opus", "Opus 5.5")), asked.choice().offered(), "the list of models offered was not kept with the answer");
    }

    @Test
    @DisplayName("z38: a brain offering one Grok, of a version written nowhere in the engine, is moved to exactly it, and the named way of asking for a model still moves the session as before")
    void theOneModelOfAFamilyIsChosenWhateverItsVersionAndTheNamedWayIsUnchanged() throws Exception {
        String offers = "default=Default,grok-7.3=Grok 7.3,haiku=Haiku 5.5";

        Asked latest = asked(offers, "latest:grok");
        assertTrue(latest.choice().moved(), "the only Grok was not chosen: " + latest.choice());
        assertEquals(Optional.of(new AcpBrainSession.Model("grok-7.3", "Grok 7.3")), latest.choice().now());
        assertEquals("grok-7.3", latest.which());

        AcpBrainSession.Program withModels = new AcpBrainSession.Program(agent.command(), Map.of("FAKE_ACP_STATE", dir.toString(), "FAKE_ACP_MODELS", offers));
        HiredBrain.Ceilings ceilings = HiredBrain.Ceilings.of(Duration.ofSeconds(60));
        try (AcpBrainSession.Conversation chat = AcpBrainSession.Conversation.start(host, withModels, HireSite.in(dir), null, List.of(), Optional.empty(),
                ceilings, AcpBrainSession.OwnTools.KEPT)) {
            assertTrue(chat.selectModel("grok 7.3"), "the named way of asking did not move the session");
            assertEquals("grok-7.3", chat.prompt("WHICHMODEL", List.of(), ceilings, piece -> { }, null, null).text().strip());
            assertFalse(chat.selectModel("grok 8"), "a model the brain does not offer moved the session");
        }
    }

    @Test
    @DisplayName("28h z21: the PATH a brain is started with is the one its program's own environment sets (an adapter's folder first) and otherwise the engine's, so a tool the brain could not find can be read against the PATH it really had")
    void theBrainsPathIsTheOneItIsStartedWith() {
        AcpBrainSession.Program withItsOwn = new AcpBrainSession.Program(List.of("brain"), Map.of("PATH", "/adapter/bin:/usr/bin"));
        AcpBrainSession.Program withNone = new AcpBrainSession.Program(List.of("brain"), Map.of());

        assertEquals("/adapter/bin:/usr/bin", AcpBrainSession.Conversation.pathOf(HireSite.in(dir), withItsOwn),
            "the PATH of the program's own environment is the brain's, not the engine's");
        assertEquals(java.util.Optional.ofNullable(System.getenv("PATH")).orElse("NOT SET"), AcpBrainSession.Conversation.pathOf(HireSite.in(dir), withNone),
            "CONTROL: a program that sets none leaves the brain the engine's own PATH");
    }

    @Test
    @DisplayName("a brain the engine starts is marked machine-made, whatever the program's own environment says, so History leaves its session out")
    void aBrainTheEngineStartsIsMarkedMachineMade() {
        AcpBrainSession.Program program = new AcpBrainSession.Program(List.of("brain"), Map.of("KEPT", "yes", "JAWATA_MACHINE_SESSION", "0"));
        Map<String, String> environment = AcpBrainSession.Conversation.environmentOf(HireSite.in(dir), program);
        assertEquals("1", environment.get("JAWATA_MACHINE_SESSION"), "the mark is the engine's and nothing overrides it");
        assertEquals("yes", environment.get("KEPT"), "the program's own variables are kept");
    }

    @Test
    @DisplayName("28h-C27-31 and second audit 2026-11-20 (finding 1): a brain the engine starts is told where its guard reads what the engine allows, a file of the projects' folders rewritten when they change and the windows' own lists, never a picture of its start; a host with no lists folder tells the projects' folders alone, and one with no access published tells nothing")
    void aBrainTheEngineStartsIsToldWhereToReadTheFoldersItMayWorkIn() throws IOException {
        AcpBrainSession.Program program = new AcpBrainSession.Program(List.of("brain"), Map.of());
        java.nio.file.Path trading = dir.resolve("Projects");
        java.nio.file.Path notes = dir.resolve("notes");
        java.util.concurrent.atomic.AtomicReference<List<java.nio.file.Path>> projects = new java.util.concurrent.atomic.AtomicReference<>(List.of(trading));
        try {
            java.nio.file.Path lists = dir.resolve("editor-folders");
            org.jawata.mcp.access.BrainAccess.publish(projects::get, lists);
            Map<String, String> told = AcpBrainSession.Conversation.environmentOf(HireSite.in(dir), program);
            java.nio.file.Path file = java.nio.file.Path.of(told.get("JAWATA_ACCESS_FOLDERS_FILE"));
            String atStart = Files.readString(file);
            org.jawata.mcp.access.BrainAccess.refresh(List.of(trading, notes));
            String afterALoad = Files.readString(file);
            org.jawata.mcp.access.BrainAccess.publish(projects::get, null);
            Map<String, String> noLists = AcpBrainSession.Conversation.environmentOf(HireSite.in(dir), program);
            org.jawata.mcp.access.BrainAccess.publish(null, null);
            Map<String, String> untold = AcpBrainSession.Conversation.environmentOf(HireSite.in(dir), program);

            assertEquals(trading.toString(), atStart, "the file does not hold the projects' folders at the brain's start");
            assertEquals(trading + "\n" + notes, afterALoad, "a project loaded while the brain runs did not reach its file");
            assertFalse(told.containsKey("JAWATA_ACCESS_FOLDERS"), "the brain was told a list fixed at its start beside the file: " + told);
            assertEquals(lists.toString(), told.get("JAWATA_EDITOR_FOLDERS_DIR"), "the brain was not told where the windows' lists are kept");
            assertEquals(trading.toString(), noLists.get("JAWATA_ACCESS_FOLDERS"), "with no place for the file the brain was not told the projects' folders");
            assertTrue(!untold.containsKey("JAWATA_ACCESS_FOLDERS") && !untold.containsKey("JAWATA_ACCESS_FOLDERS_FILE") && !untold.containsKey("JAWATA_EDITOR_FOLDERS_DIR"),
                "CONTROL: with no access published the brain was told folders: " + untold);
            // sign-off audit note 5: an engine with no lists folder and no project still marks its brain, so the guard takes no person's fallback
            org.jawata.mcp.access.BrainAccess.publish(List::of, null);
            Map<String, String> allowedNothing = AcpBrainSession.Conversation.environmentOf(HireSite.in(dir), program);
            assertEquals("1", allowedNothing.get("JAWATA_ACCESS_FROM_ENGINE"), "a brain the engine allows nothing was not marked as the engine's: " + allowedNothing);
            assertEquals("1", told.get("JAWATA_ACCESS_FROM_ENGINE"), "a brain told its access was not marked as the engine's");
            assertFalse(untold.containsKey("JAWATA_ACCESS_FROM_ENGINE"), "CONTROL: with no access published the brain was marked as the engine's");
        } finally {
            org.jawata.mcp.access.BrainAccess.publish(null, null);
        }
    }

    @Test
    @DisplayName("28h Stage 27 (z24): a shell call Claude's ACP adapter opens under its own description is kept as a Bash call with its command, so an instruction for Bash can be compared with it")
    void aShellCallClaudeRunsIsKeptAsABashCall() throws Exception {
        HiredBrain.Outcome outcome = hire("CLAUDEBASH:xvfb-run -a mvn -B verify");

        assertEquals(List.of("Bash"), outcome.toolNames(), "the call kept the description the adapter opened it with: " + outcome.toolNames());
        assertEquals("xvfb-run -a mvn -B verify", outcome.toolCalls().get(0).arguments().path("command").asText(),
            "the call was kept without the command it ran");
    }

    @Test
    @DisplayName("a shown model the catalogue holds is the value the command line is given")
    void aShownModelBecomesTheProgramsValue() throws Exception {
        // The stand-in's command names this test's own folder, so what its session records is no other test's.
        String registry = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("claude-code",
            Map.of("command", agent.command(), "env", Map.of("FAKE_ACP_STATE", dir.toString(), "FAKE_ACP_MODELS", "haiku=Haiku 5.5"),
                "vendor", "anthropic", "model", "sonnet")));

        AcpBrainSession.modelsOfferedIn(registry, "claude-code");

        assertEquals("haiku", AcpBrainSession.commandLineModelIn(registry, "claude-code", "Haiku 5.5"));
        assertEquals("not-in-the-catalogue", AcpBrainSession.commandLineModelIn(registry, "claude-code", "not-in-the-catalogue"));
    }
}
