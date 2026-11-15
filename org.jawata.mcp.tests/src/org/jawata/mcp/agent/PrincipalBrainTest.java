package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.engine.EngineApi;
import org.jawata.mcp.engine.EngineState;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.execution.AcpBrainSession;
import org.jawata.mcp.execution.HireSite;
import org.jawata.mcp.execution.HiredBrain;
import org.jawata.mcp.execution.Hirer;
import org.jawata.mcp.execution.SeatTree;
import org.jawata.mcp.execution.SelfEndpoint;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.spi.host.EngineSettings;
import org.jawata.spi.host.HostCapabilities;
import org.jawata.spi.host.McpEndpoint;
import org.jawata.spi.host.PersonChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sprint 28h, S7a (D1): the principal seat answers a host's prompt through a hired brain. The brain here is the stand-in ACP agent (the test
 * resource {@code FakeAcpAgent.java.txt}); that the same holds against a real vendor's brain is the stage's live exit, run separately.
 */
class PrincipalBrainTest {

    private Path dir;
    private AcpBrainSession.Program fake;

    @BeforeEach
    void putTheStandInAgentOnDisk() throws IOException {
        dir = Files.createTempDirectory("jawata-principal-");
        try (InputStream in = PrincipalBrainTest.class.getResourceAsStream("/test-resources/acp/FakeAcpAgent.java.txt")) {
            assertNotNull(in, "the stand-in agent is not among the test resources");
            Files.write(dir.resolve("FakeAcpAgent.java"), in.readAllBytes());
        }
        fake = new AcpBrainSession.Program(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            dir.resolve("FakeAcpAgent.java").toString()), Map.of("FAKE_ACP_STATE", dir.toString()));
    }

    @AfterEach
    void removeWhatWasCreated() throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private PrincipalBrain brain(Optional<AcpBrainSession.Program> program) {
        return new PrincipalBrain(HostProcesses.system(), program, HireSite.in(dir), null, List.of("inspect", "rename_symbol"),
            new Hirer(SeatTree.standard(), Map.of(), java.util.Set.of("rename_symbol")), HiredBrain.Ceilings.of(Duration.ofSeconds(60)));
    }

    private static String ask(PrincipalBrain brain, String prompt, List<String> pieces) {
        brain.answer(prompt, pieces::add);
        return String.join("", pieces);
    }

    @Test
    @DisplayName("the person's stop reaches the brain, whose reply ends the turn that an interrupt cannot end (D53 (f))")
    void aStopEndsTheTurnThroughTheBrain() throws Exception {
        PrincipalBrain brain = brain(Optional.of(fake));
        java.util.concurrent.CountDownLatch waiting = new java.util.concurrent.CountDownLatch(1);
        Thread turn = new Thread(() -> brain.answer("WAITCANCEL", piece -> waiting.countDown()));
        turn.start();
        try {
            assertTrue(waiting.await(60, java.util.concurrent.TimeUnit.SECONDS), "the brain never began its turn");

            brain.cancel();

            turn.join(15_000);
            assertFalse(turn.isAlive(), "the turn went on after the person stopped it");
        } finally {
            brain.close();
        }
    }

    @Test
    @DisplayName("D53: the conversation runs in the folder the host opened, and without one in the folder the engine started in")
    void theBrainRunsInTheFolderTheHostOpened(@org.junit.jupiter.api.io.TempDir java.nio.file.Path opened) throws Exception {
        PrincipalBrain brain = brain(Optional.of(fake));
        try {
            // both sides resolved: Windows may report the same folder under its short name (RUNNER~1)
            assertEquals(dir.toRealPath(), java.nio.file.Path.of(ask(brain, "WHICHFOLDER", new ArrayList<>())).toRealPath(), "before a host names a folder the brain runs where the engine said");
            brain.close();

            brain.workIn(opened.toString());

            assertEquals(opened.toRealPath(), java.nio.file.Path.of(ask(brain, "WHICHFOLDER", new ArrayList<>())).toRealPath(), "the brain did not run in the folder the person opened");
            brain.workIn("");
            brain.close();
            assertEquals(opened.toRealPath(), java.nio.file.Path.of(ask(brain, "WHICHFOLDER", new ArrayList<>())).toRealPath(), "a host that names no folder moved the conversation");
        } finally {
            brain.close();
        }
    }

    @Test
    @DisplayName("a new conversation on the same connection knows nothing of the last one (D53 (g))")
    void aNewConversationStartsFresh() {
        PrincipalBrain brain = brain(Optional.of(fake));
        try {
            ask(brain, "REMEMBER:EARLIERWORD", new ArrayList<>());

            brain.prepare();

            assertEquals("nothing", ask(brain, "RECALL", new ArrayList<>()), "the new conversation went on in the brain session of the last one");
            assertEquals("does not know", ask(brain, "ASKTRANSCRIPT", new ArrayList<>()), "what was said in the last conversation was carried into this one");
        } finally {
            brain.close();
        }
    }

    @Test
    @DisplayName("a prompt reaches the hired brain, its answer reaches the host as text, and a hire record names the program that ran")
    void aPromptIsAnsweredByTheHiredBrainAndRecorded() {
        PrincipalBrain brain = brain(Optional.of(fake));
        List<String> pieces = new ArrayList<>();

        String answer = ask(brain, "REMEMBER:bluebird", pieces);

        assertEquals("noted", answer);
        assertFalse(answer.contains("no brain"), "the no-brain sentence was given in place of an answer");
        assertEquals(1, brain.records().size());
        PrincipalBrain.HireRecord record = brain.records().get(0);
        assertEquals("main", record.seat());
        assertTrue(record.command().get(1).endsWith("FakeAcpAgent.java"), "the record does not name the program that ran: " + record.command());
        assertTrue(record.brainSessionId().isPresent() && record.clean(), record.toString());
    }

    private PrincipalBrain brainWithStance(AcpBrainSession.Program program) {
        return new PrincipalBrain(HostProcesses.system(), Optional.of(program), HireSite.in(dir), null, List.of("inspect"),
            new Hirer(SeatTree.standard(), Map.of(), java.util.Set.of()), HiredBrain.Ceilings.of(Duration.ofSeconds(60)), () -> Optional.of("THE-MAIN-STANCE"));
    }

    private static String asked(PrincipalBrain brain, String prompt) {
        return ask(brain, prompt, new ArrayList<>());
    }

    @Test
    @DisplayName("D53 (g): a conversation is answered by one brain process, however many messages it has")
    void aConversationKeepsOneBrainProcess() {
        PrincipalBrain brain = brain(Optional.of(fake));
        try {
            assertEquals(asked(brain, "WHICHPROCESS"), asked(brain, "WHICHPROCESS"));
        } finally {
            brain.close();
        }
    }

    @Test
    @DisplayName("D53 (g): closing the conversation ends its brain process, and a message afterwards starts a new one on the same brain session")
    void closingTheConversationEndsItsProcess() {
        PrincipalBrain brain = brain(Optional.of(fake));
        try {
            String first = asked(brain, "WHICHPROCESS");
            long pid = Long.parseLong(first);
            brain.close();
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "the brain's process outlived its conversation");
            assertFalse(first.equals(asked(brain, "WHICHPROCESS")), "no new process was started");
        } finally {
            brain.close();
        }
    }

    @Test
    @DisplayName("D53 (g): prepare starts the brain before the first message, and the first message uses that process")
    void prepareStartsTheBrainAheadOfTheFirstMessage() throws Exception {
        // by process id and not by command line: Windows does not report another process's command line
        java.util.Set<Long> before = ProcessHandle.current().descendants().map(ProcessHandle::pid).collect(java.util.stream.Collectors.toSet());
        PrincipalBrain brain = brain(Optional.of(fake));
        try {
            brain.prepare();
            long deadline = System.currentTimeMillis() + 30_000;
            java.util.Set<Long> warm = java.util.Set.of();
            while (System.currentTimeMillis() < deadline && warm.isEmpty()) {
                warm = ProcessHandle.current().descendants().map(ProcessHandle::pid).filter(pid -> !before.contains(pid)).collect(java.util.stream.Collectors.toSet());
                Thread.sleep(20);
            }
            assertTrue(warm.contains(Long.valueOf(asked(brain, "WHICHPROCESS"))), "the first message did not use the process prepare started: " + warm);
        } finally {
            brain.close();
        }
    }

    @Test
    @DisplayName("D53 (h): the main seat's instructions reach the brain with the first message and not with the later ones")
    void theMainSeatsTextGoesWithTheFirstMessageOnly() {
        PrincipalBrain brain = brainWithStance(fake);
        try {
            assertEquals("with stance", asked(brain, "ASKSTANCE"));
            assertEquals("no stance", asked(brain, "ASKSTANCE"));
        } finally {
            brain.close();
        }
        PrincipalBrain greeted = brainWithStance(fake);
        try {
            assertEquals("told", asked(greeted, "ASKGREETING"), "D53 (g): the rules ran on a greeting, 15 to 28 s to the first word");
        } finally {
            greeted.close();
        }
    }

    @Test
    @DisplayName("D53 (i): the principal moved to another brain takes effect at the next message, with the seat's instructions and what was said")
    void anotherBrainTakesOverAtTheNextMessageWithTheConversation() throws Exception {
        PrincipalBrain brain = brainWithStance(fake);
        String registry = "{\"other\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("command", fake.command(), "env", fake.environment())) + "}";
        try {
            String before = asked(brain, "WHICHPROCESS EARLIERWORD");
            assertTrue(brain.useBrain(registry, "other"));
            assertTrue(ProcessHandle.of(Long.parseLong(before)).orElseThrow().isAlive(), "the choice ended the running process at once");
            assertEquals("knows", asked(brain, "ASKTRANSCRIPT"));
            assertFalse(before.equals(asked(brain, "WHICHPROCESS")), "the message after the choice still went to the old process");
            assertFalse(brain.useBrain(registry, "not-offered"), "a brain the registry does not name was accepted");
        } finally {
            brain.close();
        }
    }

    @Test
    @DisplayName("D53 (e): a picture goes to a brain that takes pictures, and a brain that does not is sent none and the person is told")
    void aPictureGoesOnlyToABrainThatTakesIt() {
        JsonNode picture = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("type", "image").put("mimeType", "image/png").put("data", "AAA");
        Map<String, String> env = new java.util.HashMap<>(fake.environment());
        env.put("FAKE_ACP_IMAGES", "yes");
        PrincipalBrain takes = brain(Optional.of(new AcpBrainSession.Program(fake.command(), env)));
        PrincipalBrain doesNot = brain(Optional.of(fake));
        try {
            takes.attachImages(List.of(picture));
            assertEquals("1", asked(takes, "COUNTIMAGES"));
            assertEquals("0", asked(takes, "COUNTIMAGES"), "a picture was sent again with the next message");
            doesNot.attachImages(List.of(picture));
            String said = asked(doesNot, "COUNTIMAGES");
            assertTrue(said.contains("does not take pictures") && said.endsWith("0"), said);
        } finally {
            takes.close();
            doesNot.close();
        }
    }

    @Test
    @DisplayName("the vendor of a brain is read from the command line that starts it: Cursor's agent, Copilot, the Claude and Codex adapters; a program that is none of them names no vendor")
    void aCommandLineNamesTheBrainsVendor() {
        assertEquals(Optional.of("cursor"), PrincipalBrain.vendorOf(List.of("agent", "acp")));
        assertEquals(Optional.of("cursor"), PrincipalBrain.vendorOf(List.of("/home/h/.local/bin/agent", "acp")));
        assertEquals(Optional.of("copilot"), PrincipalBrain.vendorOf(List.of("copilot", "--acp")));
        assertEquals(Optional.of("claude-code"), PrincipalBrain.vendorOf(List.of("npx", "-y", "@zed-industries/claude-code-acp")));
        assertEquals(Optional.of("codex"), PrincipalBrain.vendorOf(List.of("C:\\tools\\codex-acp.exe")));
        assertEquals(Optional.empty(), PrincipalBrain.vendorOf(List.of("java", "FakeAcpAgent.java")), "a vendor was invented for a program that names none");
        assertEquals(Optional.empty(), PrincipalBrain.vendorOf(List.of()));
    }

    @Test
    @DisplayName("the hire record names the vendor of the brain that ran, and none for a program that names none")
    void theHireRecordNamesTheVendor() throws IOException {
        PrincipalBrain unnamed = brain(Optional.of(fake));
        ask(unnamed, "REMEMBER:bluebird", new ArrayList<>());
        assertEquals(Optional.empty(), unnamed.records().get(0).vendor(), "CONTROL: a stand-in that names no vendor was given one");

        // A brain is named by the program's own file name, so the test needs an executable called "agent". On Windows a program is found by its
        // extension and a link to java.exe cannot run from another place (it looks for its runtime beside itself), so the cell cannot be built.
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"),
            "the fixture is a symbolic link to java named agent, which Windows cannot run");
        Path agent = dir.resolve("agent");
        try {
            Files.createSymbolicLink(agent, Path.of(System.getProperty("java.home"), "bin", "java"));
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "this machine cannot make a symbolic link: " + e);
        }
        PrincipalBrain named = brain(Optional.of(new AcpBrainSession.Program(List.of(agent.toString(), dir.resolve("FakeAcpAgent.java").toString()),
            fake.environment())));

        ask(named, "REMEMBER:bluebird", new ArrayList<>());

        assertEquals(Optional.of("cursor"), named.records().get(0).vendor(), named.records().get(0).toString());
    }

    @Test
    @DisplayName("R9, R10: the principal brain hands the host the brain's thinking and its plan as they stream, in order, and the answer stays the answer")
    void theBrainsThinkingAndPlanReachTheHost() {
        PrincipalBrain brain = brain(Optional.of(fake));
        List<String> pieces = new ArrayList<>();
        List<String> thinking = new ArrayList<>();

        brain.answer("THINK", pieces::add, null, update -> thinking.add(update.path("sessionUpdate").asText()));

        assertEquals(List.of("agent_thought_chunk", "plan", "agent_thought_chunk"), thinking);
        assertEquals("thought", String.join("", pieces), "the thinking was taken for the answer, or the answer was lost");
    }

    @Test
    @DisplayName("S3, D30: the principal brain presents a session id of its own to the resident, the same on every prompt of one brain and different between two brains")
    void thePrincipalBrainPresentsItsOwnSessionId() {
        SelfEndpoint endpoint = new SelfEndpoint("http://127.0.0.1:1/mcp", "key");
        java.util.function.Supplier<PrincipalBrain> fresh = () -> new PrincipalBrain(HostProcesses.system(), Optional.of(fake), HireSite.in(dir), endpoint,
            List.of("inspect"), new Hirer(SeatTree.standard(), Map.of(), java.util.Set.of()), HiredBrain.Ceilings.of(Duration.ofSeconds(60)));
        PrincipalBrain one = fresh.get();
        PrincipalBrain other = fresh.get();

        String first = ask(one, "SERVERS", new ArrayList<>());
        String again = ask(one, "SERVERS", new ArrayList<>());
        String second = ask(other, "SERVERS", new ArrayList<>());

        java.util.regex.Matcher id = java.util.regex.Pattern.compile("principal-[0-9a-f-]+").matcher(first);
        assertTrue(id.find(), "the brain presented no session id of its own: " + first);
        assertTrue(again.contains(id.group()), "a second prompt of the same brain presented another id: " + again);
        assertFalse(second.contains(id.group()), "two brains presented the same id: " + second);
    }

    @Test
    @DisplayName("S10 (F7): the key the principal brain reports for its tool calls is the session id it presents to the resident, and a brain that cannot call the resident reports none")
    void theBrainReportsTheKeyItsCallsCarry() {
        SelfEndpoint endpoint = new SelfEndpoint("http://127.0.0.1:1/mcp", "key");
        PrincipalBrain connected = new PrincipalBrain(HostProcesses.system(), Optional.of(fake), HireSite.in(dir), endpoint,
            List.of("inspect"), new Hirer(SeatTree.standard(), Map.of(), java.util.Set.of()), HiredBrain.Ceilings.of(Duration.ofSeconds(60)));

        String presented = ask(connected, "SERVERS", new ArrayList<>());

        java.util.regex.Matcher id = java.util.regex.Pattern.compile("principal-[0-9a-f-]+").matcher(presented);
        assertTrue(id.find(), "the brain presented no session id of its own: " + presented);
        assertEquals(Optional.of(id.group()), connected.toolSessionKey(), "the reported key is not the one the brain's calls carried");
        assertEquals(Optional.empty(), brain(Optional.of(fake)).toolSessionKey(), "control: a brain with no endpoint to call has no key to report");
    }

    @Test
    @DisplayName("the second prompt of the same brain continues the first one's session")
    void theSecondPromptKnowsWhatTheFirstSaw() {
        PrincipalBrain brain = brain(Optional.of(fake));

        ask(brain, "REMEMBER:bluebird", new ArrayList<>());
        String recalled = ask(brain, "RECALL", new ArrayList<>());
        String fresh = ask(brain(Optional.of(fake)), "RECALL", new ArrayList<>());

        assertEquals("bluebird", recalled);
        assertEquals("nothing", fresh, "CONTROL: a brain that was not continued knew it anyway");
    }

    @Test
    @DisplayName("the principal is a lead: its brain is refused a code-changing tool and allowed a reading one")
    void theLeadCannotChangeCode() {
        PrincipalBrain brain = brain(Optional.of(fake));

        String changing = ask(brain, "CALL:mcp__jawata__rename_symbol", new ArrayList<>());
        String reading = ask(brain, "CALL:mcp__jawata__inspect", new ArrayList<>());

        assertEquals("permission:reject_once", changing);
        assertEquals("permission:allow_once", reading);
    }

    @Test
    @DisplayName("the answer reaches the host while the turn is still running, not all at once at its end")
    void theAnswerArrivesBeforeTheTurnEnds() {
        PrincipalBrain brain = brain(Optional.of(fake));
        List<String> pieces = new ArrayList<>();
        long[] firstAt = {0};

        brain.answer("STREAM", piece -> {
            if (firstAt[0] == 0) {
                firstAt[0] = System.nanoTime();
            }
            pieces.add(piece);
        });
        long returnedAt = System.nanoTime();

        assertEquals("firstsecond", String.join("", pieces));
        assertTrue(firstAt[0] != 0 && Duration.ofNanos(returnedAt - firstAt[0]).toMillis() >= 1000,
            "the first piece arrived only when the turn ended: it was held back instead of streamed");
    }

    @Test
    @DisplayName("the tools a lead is refused are the ones the published tools say rewrite source, not a list kept beside them")
    void theCodeChangingSetComesFromWhatToolsSay() {
        org.jawata.mcp.change.OperationRegistry operations = org.jawata.mcp.change.OperationRegistry.theRegistry();
        org.jawata.mcp.change.OperationRegistry.Snapshot borrowed = operations.snapshot();
        List<Map<String, Object>> published;
        try {
            org.jawata.mcp.tools.ToolRegistry registry = new org.jawata.mcp.tools.ToolRegistry();
            org.jawata.mcp.fixtures.InertShippedTools.tools().forEach(registry::register);
            published = registry.getToolDefinitions();
        } finally {
            operations.restore(borrowed);
        }

        java.util.Set<String> derived = PrincipalBrain.codeChangingTools(published);

        assertTrue(derived.containsAll(List.of("rename_symbol", "extract", "move", "change_method_signature", "organize_imports")),
            derived.toString());
        assertTrue(derived.containsAll(List.of("format", "quick_fix", "refactoring", "dependency")),
            "a tool that changes code but carries no destructiveHint was left to the lead: " + derived);
        assertTrue(java.util.Collections.disjoint(derived, List.of("inspect", "find_references", "search_symbols", "compile_workspace", "experience")),
            "a reading or checking tool was counted as rewriting source: " + derived);
        assertEquals(java.util.Set.of("x"), PrincipalBrain.codeChangingTools(List.of(
            Map.of("name", "x", "_meta", Map.of("jawata/changesCode", true)),
            Map.of("name", "y", "_meta", Map.of("jawata/changesCode", false)),
            Map.of("name", "w", "annotations", Map.of("destructiveHint", true)),
            Map.of("name", "z"))), "CONTROL: the derivation reads the published changesCode flag and nothing else");
    }

    @Test
    @DisplayName("with no brain named the agent says so and leaves no hire record")
    void withNoBrainItSaysSo() {
        PrincipalBrain brain = brain(Optional.empty());

        assertEquals(AgentFace.NO_BRAIN, ask(brain, "hello", new ArrayList<>()));
        assertEquals(List.of(), brain.records());
    }

    /** The stand-in agent, offering two models and on the first (D54 (a)). */
    private AcpBrainSession.Program fakeWithModels() {
        Map<String, String> environment = new java.util.LinkedHashMap<>(fake.environment());
        environment.put("FAKE_ACP_MODELS", "a=Alpha,b=Beta");
        return new AcpBrainSession.Program(fake.command(), environment);
    }

    @Test
    @DisplayName("D54 (a): the brain reports the models it offers and the one it is on, once its session is open, and none before")
    void theBrainReportsTheModelsAndTheOneItIsOn() {
        PrincipalBrain brain = brain(Optional.of(fakeWithModels()));
        assertEquals(Optional.empty(), brain.currentModel(), "a model the brain has not confirmed is not named");
        assertEquals(List.of(), brain.models());

        ask(brain, "hello", new ArrayList<>());

        assertEquals(Optional.of("Alpha"), brain.currentModel());
        assertEquals(Optional.of("a"), brain.currentModelValue());
        assertEquals(List.of(new AcpBrainSession.Model("a", "Alpha"), new AcpBrainSession.Model("b", "Beta")), brain.models());
        brain.close();
    }

    @Test
    @DisplayName("D54 (a): a model chosen before the session opens is applied once it is open")
    void aModelChosenBeforeTheSessionOpensIsAppliedWhenItOpens() {
        PrincipalBrain brain = brain(Optional.of(fakeWithModels()));

        assertEquals(true, brain.useModel("b"), "with no session open the choice is kept for the one that opens");
        String answer = ask(brain, "WHICHMODEL", new ArrayList<>());

        assertEquals("b", answer, "the brain answered from the model it was moved to");
        assertEquals(Optional.of("Beta"), brain.currentModel());
        brain.close();
    }

    @Test
    @DisplayName("D54 (a): a model chosen while the conversation runs moves the same session, and one the brain does not offer changes nothing")
    void aModelChosenWhileTheConversationRunsMovesItAndAnUnofferedOneDoesNot() {
        PrincipalBrain brain = brain(Optional.of(fakeWithModels()));
        ask(brain, "REMEMBER:kept", new ArrayList<>());

        assertEquals(true, brain.useModel("Beta"), "a name is matched as well as a value");
        assertEquals("b", ask(brain, "WHICHMODEL", new ArrayList<>()));
        assertEquals("kept", ask(brain, "RECALL", new ArrayList<>()), "the same session, so what it was told is still known");

        assertEquals(false, brain.useModel("no-such-model"));
        assertEquals("b", ask(brain, "WHICHMODEL", new ArrayList<>()), "refused, so it stays where it was");
        brain.close();
    }

    @Test
    @DisplayName("D54 (a): a preferred model the brain does not offer leaves the session on the brain's own default and ends no conversation")
    void aPreferredModelTheBrainDoesNotOfferLeavesTheDefault() {
        PrincipalBrain brain = brain(Optional.of(fakeWithModels()));

        brain.useModel("no-such-model");
        String answer = ask(brain, "WHICHMODEL", new ArrayList<>());

        assertEquals("a", answer);
        assertEquals(Optional.of("Alpha"), brain.currentModel());
        brain.close();
    }

    @Test
    @DisplayName("D54 (a): the model the principal's setting names beside its command line is the one the session is moved to")
    void theModelTheSettingNamesIsAppliedToTheSession() {
        String setting = "{\"command\":[\"" + inJson(fake.command().get(0)) + "\",\"" + inJson(fake.command().get(1)) + "\"],\"model\":\"Beta\",\"env\":{\"FAKE_ACP_STATE\":\""
            + inJson(dir.toString()) + "\",\"FAKE_ACP_MODELS\":\"a=Alpha,b=Beta\"}}";
        PrincipalBrain brain = PrincipalBrain.over(engineWith(Map.of(PrincipalBrain.SETTING, setting)), dir);

        assertEquals("b", ask(brain, "WHICHMODEL", new ArrayList<>()));
        assertEquals(Optional.of("Beta"), brain.currentModel());
        brain.close();
    }

    @Test
    @DisplayName("D56 (f): the model the main seat's file names beside its brain is the one a new conversation starts on")
    void theModelTheMainSeatNamesIsApplied() throws Exception {
        java.nio.file.Path seats = java.nio.file.Files.createDirectories(dir.resolve("seats"));
        java.nio.file.Files.writeString(seats.resolve("main.md"), "---\nname: main\nbrain: fake\nmodel: Beta\n---\nYou are main.\n");
        String program = "{\"command\":[\"" + inJson(fake.command().get(0)) + "\",\"" + inJson(fake.command().get(1)) + "\"],\"env\":{\"FAKE_ACP_STATE\":\""
            + inJson(dir.toString()) + "\",\"FAKE_ACP_MODELS\":\"a=Alpha,b=Beta\"}}";
        PrincipalBrain brain = PrincipalBrain.over(engineWith(Map.of(Hirer.BRAINS_SETTING, "{\"fake\":" + program + "}",
            org.jawata.mcp.execution.ScheduledHires.SEATS_SETTING, seats.toString())), dir);

        assertEquals("b", ask(brain, "WHICHMODEL", new ArrayList<>()));
        assertEquals(Optional.of("Beta"), brain.currentModel());
        brain.close();
    }

    @Test
    @DisplayName("the setting names the brain: a command line is read, text that is not one names none")
    void theSettingNamesTheBrain() {
        Optional<AcpBrainSession.Program> read = AcpBrainSession.parseProgram("{\"command\":[\"agent\",\"acp\"],\"env\":{\"K\":\"v\"}}");

        assertEquals(List.of("agent", "acp"), read.orElseThrow().command());
        assertEquals(Map.of("K", "v"), read.orElseThrow().environment());
        assertEquals(Optional.empty(), AcpBrainSession.parseProgram("not json"));
        assertEquals(Optional.empty(), AcpBrainSession.parseProgram("{\"command\":[]}"));
    }

    @Test
    @DisplayName("built from the engine, the principal brain reads the host setting and the engine's tools and address")
    void builtFromTheEngine() {
        // paths go into JSON text: on Windows their backslashes must be doubled, or \Users is not JSON and the setting names no brain
        String setting = "{\"command\":[\"" + inJson(fake.command().get(0)) + "\",\"" + inJson(fake.command().get(1)) + "\"],\"env\":{\"FAKE_ACP_STATE\":\""
            + inJson(dir.toString()) + "\"}}";
        List<String> pieces = new ArrayList<>();
        List<String> none = new ArrayList<>();

        PrincipalBrain.over(engineWith(Map.of(PrincipalBrain.SETTING, setting)), dir).answer("REMEMBER:x", pieces::add);
        PrincipalBrain.over(engineWith(Map.of()), dir).answer("REMEMBER:x", none::add);

        assertEquals("noted", String.join("", pieces));
        assertEquals(List.of(AgentFace.NO_BRAIN), none);
    }

    private static String inJson(String text) {
        return text.replace("\\", "\\\\");
    }

    private static EngineApi engineWith(Map<String, String> settings) {
        return new EngineApi() {
            @Override public ToolCalls tools() {
                return new ToolCalls() {
                    @Override public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                        throw new UnsupportedOperationException();
                    }
                    @Override public List<Map<String, Object>> getToolDefinitions() {
                        return List.of(Map.of("name", "inspect"), Map.of("name", "rename_symbol"));
                    }
                };
            }
            @Override public EngineState state() {
                return null;
            }
            @Override public PersonChannel person() {
                return null;
            }
            @Override public EngineSettings settings() {
                return key -> Optional.ofNullable(settings.get(key));
            }
            @Override public HostCapabilities host() {
                return null;
            }
            @Override public Optional<McpEndpoint> mcp() {
                return Optional.empty();
            }
        };
    }
}
