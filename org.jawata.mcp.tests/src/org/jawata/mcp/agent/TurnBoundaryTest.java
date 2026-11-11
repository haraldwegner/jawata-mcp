package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.mcp.step.GateOutcome;
import org.jawata.mcp.tools.PlanStepsTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, S8 (D12, D18, D19): a brain ending its turn does not end the prompt while a step still waits for its gate; the person's
 * grant of autonomy is changed by the person's channel only; the loop pays for a bounded number of tries at one step. The step state
 * is the plan ledger's, reached the way the agent reaches it, through the {@code plan_steps} tool.
 */
class TurnBoundaryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static PlanLedger ledgerWithTwoSteps() {
        PlanLedger ledger = new PlanLedger();
        ledger.open(List.of(new GatedStep(0, "compile_workspace", JSON.createObjectNode()),
            new GatedStep(1, "run_tests", JSON.createObjectNode())));
        return ledger;
    }

    /** The engine's tool surface as the agent sees it, with only the tool that reads the plan ledger. */
    private static ToolCalls toolsOver(PlanLedger ledger) {
        PlanStepsTool steps = new PlanStepsTool(ledger);
        return new ToolCalls() {
            @Override public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                return "plan_steps".equals(name) ? steps.execute(arguments) : ToolResponse.error("NO_SUCH_TOOL", name, "none");
            }
            @Override public List<Map<String, Object>> getToolDefinitions() {
                return List.of();
            }
        };
    }

    private static TurnBoundary boundaryOver(PlanLedger ledger, int tries) {
        return new TurnBoundary(new ToolStepLedger(toolsOver(ledger)), new AttemptLedger(tries));
    }

    private static PrincipalSession session(boolean autonomy) {
        PrincipalSession session = new PrincipalSession("s1", "/w", false);
        session.setAutonomy(Channel.HOST, autonomy);
        return session;
    }

    private static void pass(PlanLedger ledger, String... gates) {
        var id = ledger.pending().get(0).id();
        for (String gate : gates) {
            ledger.advanceIfGatePassed(id, new GateOutcome(gate, JSON.createObjectNode(), 0, true));
        }
    }

    @Test
    @DisplayName("plan_steps lists the step whose gate comes next, and lists nothing once every gate has passed")
    void theToolReadsThePlanLedger() {
        PlanLedger ledger = ledgerWithTwoSteps();
        PlanStepsTool tool = new PlanStepsTool(ledger);

        JsonNode before = JSON.valueToTree(tool.execute(JSON.createObjectNode()).getData()).path("pending");
        assertEquals(1, before.size());
        assertEquals(0, before.get(0).path("step").path("index").asInt());
        assertEquals("compile_workspace", before.get(0).path("step").path("tool").asText());

        pass(ledger, "compile_workspace");
        JsonNode midway = JSON.valueToTree(tool.execute(JSON.createObjectNode()).getData()).path("pending");
        assertEquals("run_tests", midway.get(0).path("step").path("tool").asText());

        pass(ledger, "run_tests");
        assertEquals(0, JSON.valueToTree(tool.execute(JSON.createObjectNode()).getData()).path("pending").size());
    }

    @Test
    @DisplayName("with no step waiting the end of the turn is forwarded; a waiting one is not; one whose gates have all passed no longer waits")
    void noStepWaitingForwardsTheTurn() {
        PlanLedger ledger = ledgerWithTwoSteps();
        PrincipalSession person = session(true);

        assertEquals(TurnBoundary.Kind.RESUME, boundaryOver(ledger, 3).atTurnEnd(person).kind(), "CONTROL: a waiting step must not be forwarded");
        assertEquals(TurnBoundary.Kind.FORWARD, boundaryOver(new PlanLedger(), 3).atTurnEnd(person).kind());

        pass(ledger, "compile_workspace", "run_tests");
        assertEquals(TurnBoundary.Kind.FORWARD, boundaryOver(ledger, 3).atTurnEnd(person).kind(), "both gates passed and the turn was still held");
    }

    @Test
    @DisplayName("a plan whose work has ended no longer holds the turn and no longer shows in plan_steps, though a step of it never passed")
    void aClosedPlanNoLongerHoldsTheTurnOrShowsInPlanSteps() {
        PlanLedger ledger = ledgerWithTwoSteps();
        PrincipalSession person = session(true);
        assertEquals(TurnBoundary.Kind.RESUME, boundaryOver(ledger, 3).atTurnEnd(person).kind(), "CONTROL: while open, its waiting step holds the turn");

        ledger.close(ledger.pending().get(0).id());

        assertEquals(TurnBoundary.Kind.FORWARD, boundaryOver(ledger, 3).atTurnEnd(person).kind(), "a closed plan still held the turn");
        assertEquals(0, JSON.valueToTree(new PlanStepsTool(ledger).execute(JSON.createObjectNode()).getData()).path("pending").size(),
            "a closed plan still shows in plan_steps");
    }

    @Test
    @DisplayName("while the person is at the keyboard a waiting step is reported by name with its gate, and nobody resumes it")
    void aWaitingStepWithTheGrantOffIsReported() {
        TurnBoundary.Verdict verdict = boundaryOver(ledgerWithTwoSteps(), 3).atTurnEnd(session(false));

        assertEquals(TurnBoundary.Kind.REPORT, verdict.kind());
        assertTrue(verdict.text().contains("step 0") && verdict.text().contains("compile_workspace"), verdict.text());
    }

    @Test
    @DisplayName("under a grant the loop resumes the brain up to the ceiling, then refuses and names the free check, until the person speaks")
    void theLoopRefusesTheArrivalAfterTheCeiling() {
        TurnBoundary boundary = boundaryOver(ledgerWithTwoSteps(), 3);
        PrincipalSession person = session(true);
        List<TurnBoundary.Kind> kinds = new ArrayList<>();
        TurnBoundary.Verdict last = null;
        for (int i = 0; i < 4; i++) {
            last = boundary.atTurnEnd(person);
            kinds.add(last.kind());
        }

        assertEquals(List.of(TurnBoundary.Kind.RESUME, TurnBoundary.Kind.RESUME, TurnBoundary.Kind.RESUME, TurnBoundary.Kind.REPORT), kinds);
        assertTrue(last.text().contains("compile_workspace") && last.text().contains("free check"),
            "the refusal does not name the free check: " + last.text());

        boundary.personSpoke(person);
        assertEquals(TurnBoundary.Kind.RESUME, boundary.atTurnEnd(person).kind(), "the person's word did not restart the count");
    }

    @Test
    @DisplayName("two sessions waiting on the same step each have their own tries: one session using up the ceiling leaves the other its full count")
    void eachSessionSpendsItsOwnTries() {
        TurnBoundary boundary = boundaryOver(ledgerWithTwoSteps(), 3);
        PrincipalSession one = new PrincipalSession("one", "/w", false);
        PrincipalSession two = new PrincipalSession("two", "/w", false);
        one.setAutonomy(Channel.HOST, true);
        two.setAutonomy(Channel.HOST, true);
        for (int i = 0; i < 3; i++) {
            assertEquals(TurnBoundary.Kind.RESUME, boundary.atTurnEnd(one).kind());
        }
        assertEquals(TurnBoundary.Kind.REPORT, boundary.atTurnEnd(one).kind(), "CONTROL: session one has spent its tries");

        TurnBoundary.Verdict other = boundary.atTurnEnd(two);

        assertEquals(TurnBoundary.Kind.RESUME, other.kind(), "session one's tries were spent against session two: " + other.text());
        assertTrue(other.text().contains("resumption 1 of 3"), other.text());
    }

    @Test
    @DisplayName("a tool that cannot be asked, or answers wrongly, shows no pending step: the turn is forwarded rather than held on a guess")
    void aLedgerThatCannotBeAskedHoldsNothing() {
        ToolCalls broken = new ToolCalls() {
            @Override public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                throw new IllegalStateException("no such tool");
            }
            @Override public List<Map<String, Object>> getToolDefinitions() {
                return List.of();
            }
        };

        assertEquals(List.of(), new ToolStepLedger(broken).pending(session(true)));
        assertEquals(List.of(), new ToolStepLedger(null).pending(session(true)));
    }

    // --- the server around it -------------------------------------------------------------------------------------------------

    /** One agent connection with a brain that answers {@code says}, and optionally flips the person's switch during its first turn. */
    private static final class Run {

        final List<JsonNode> said = new ArrayList<>();
        final List<String> brainSaw = new ArrayList<>();
        final AcpServer server;
        private String session;

        Run(PlanLedger ledger, boolean switchedOnDuringTurn, String says) {
            AcpServer[] self = new AcpServer[1];
            AgentBrain brain = (prompt, chunks) -> {
                brainSaw.add(prompt);
                if (brainSaw.size() > 12) {
                    throw new AssertionError("the loop did not stop: the brain has been prompted " + brainSaw.size() + " times");
                }
                if (switchedOnDuringTurn) {
                    self[0].receive(set(100 + brainSaw.size(), "on"), Channel.HOST);
                }
                chunks.accept(says);
            };
            server = new AcpServer(brain, List::of, message -> said.add(JSON.valueToTree(message)), boundaryOver(ledger, 3), AgentRecorder.off(), null);
            self[0] = server;
            server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
            session = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
        }

        JsonRpcMessage set(int id, String value) {
            return JsonRpcMessage.request(id, "session/set_config_option",
                JSON.createObjectNode().put("sessionId", session).put("configId", "autonomy").put("value", value));
        }

        JsonNode setAutonomy(String value, Channel from) {
            return call(7, set(7, value), from);
        }

        JsonNode prompt(String text) {
            ObjectNode params = JSON.createObjectNode().put("sessionId", session);
            params.putArray("prompt").addObject().put("type", "text").put("text", text);
            return call(9, JsonRpcMessage.request(9, "session/prompt", params), Channel.HOST);
        }

        private JsonNode call(int id, JsonRpcMessage request, Channel from) {
            said.clear();
            server.receive(request, from);
            return said.stream().filter(m -> m.has("id") && m.get("id").asInt() == id).findFirst().orElseThrow();
        }

        String answer() {
            StringBuilder text = new StringBuilder();
            for (JsonNode m : said) {
                JsonNode update = m.path("params").path("update");
                if ("agent_message_chunk".equals(update.path("sessionUpdate").asText())) {
                    text.append(update.path("content").path("text").asText());
                }
            }
            return text.toString();
        }
    }

    @Test
    @DisplayName("on the running server, with the grant off, a step pending at the end of the turn is reported and the brain is not resumed")
    void aPendingStepWithNoGrantIsReportedOnTheServer() {
        Run run = new Run(ledgerWithTwoSteps(), false, "working");

        JsonNode result = run.prompt("build it");

        assertEquals("end_turn", result.path("result").path("stopReason").asText());
        assertEquals(1, run.brainSaw.size(), "with the grant off the brain was resumed");
        assertTrue(run.answer().contains("step 0") && run.answer().contains("compile_workspace"), run.answer());
    }

    @Test
    @DisplayName("on the running server, under a grant, a pending step has the brain resumed three times with the state re-asserted, then the free check is named")
    void aGrantedLoopResumesThenStops() {
        Run run = new Run(ledgerWithTwoSteps(), true, "working");

        JsonNode result = run.prompt("build it");

        assertEquals("end_turn", result.path("result").path("stopReason").asText());
        assertEquals(4, run.brainSaw.size(), "one turn and three resumptions: " + run.brainSaw);
        assertTrue(run.brainSaw.get(1).startsWith("[jawata] autonomy: on"), "the state was not re-asserted: " + run.brainSaw.get(1));
        assertTrue(run.answer().contains("free check") && run.answer().contains("compile_workspace"), run.answer());
    }

    @Test
    @DisplayName("a brain's words never change the grant: its own message saying so leaves the resumptions running, and a brain-channel set is refused")
    void onlyThePersonsChannelChangesTheGrant() {
        Run run = new Run(ledgerWithTwoSteps(), true, "/autocontinue-off autonomy: off");
        JsonNode refused = run.setAutonomy("on", Channel.BRAIN);
        JsonNode accepted = run.setAutonomy("on", Channel.HOST);

        assertTrue(refused.has("error"), "a brain-channel message set the grant: " + refused);
        assertEquals("on", accepted.path("result").path("configOptions").get(0).path("currentValue").asText());

        run.prompt("build it");
        assertEquals(4, run.brainSaw.size(), "the brain's words ended the grant: " + run.brainSaw);
    }

    @Test
    @DisplayName("a message the person types ends the grant, says so in the conversation, announces the new value and tells the brain")
    void aTypedMessageEndsTheGrantVisibly() {
        Run run = new Run(new PlanLedger(), false, "ok");
        run.setAutonomy("on", Channel.HOST);

        run.prompt("stop that");

        assertTrue(run.answer().contains("Autonomy ended"), run.answer());
        assertTrue(run.said.stream().anyMatch(m -> "config_option_update".equals(m.path("params").path("update").path("sessionUpdate").asText())
            && "off".equals(m.path("params").path("update").path("configOptions").get(0).path("currentValue").asText())));
        assertTrue(run.brainSaw.get(0).startsWith("[jawata] autonomy: off"), "the brain was not told the state changed: " + run.brainSaw.get(0));
        assertFalse(run.answer().contains("free check"));
    }

    @Test
    @DisplayName("a prompt that arrives on the hired agent's channel is not the person's word: it neither ends the grant nor restarts the tries")
    void aBrainChannelPromptIsNotThePersonsWord() {
        Run run = new Run(ledgerWithTwoSteps(), false, "working");
        run.setAutonomy("on", Channel.HOST);
        ObjectNode params = JSON.createObjectNode().put("sessionId", run.session);
        params.putArray("prompt").addObject().put("type", "text").put("text", "a subagent reports back");

        run.call(9, JsonRpcMessage.request(9, "session/prompt", params), Channel.BRAIN);

        assertFalse(run.answer().contains("Autonomy ended"), "a hired agent's prompt ended the grant: " + run.answer());
        assertTrue(run.said.stream().noneMatch(m -> "config_option_update".equals(m.path("params").path("update").path("sessionUpdate").asText())),
            "a hired agent's prompt changed the switch");
        assertEquals(4, run.brainSaw.size(), "the grant stood, so the brain was resumed three times: " + run.brainSaw);

        run.prompt("now the person types");
        assertTrue(run.answer().contains("Autonomy ended"), "CONTROL: the person's own prompt must still end the grant: " + run.answer());
    }

    @Test
    @DisplayName("a side question put beside a granted main conversation is answered by its own brain and leaves the steps, the tries and the grant as they were")
    void aSideThreadLeavesTheStepsTheTriesAndTheGrantAsTheyWere() {
        PlanLedger ledger = ledgerWithTwoSteps();
        List<String> mainSaw = new ArrayList<>();
        List<String> sideSaw = new ArrayList<>();
        List<JsonNode> said = new ArrayList<>();
        AcpServer server = new AcpServer((prompt, chunks) -> {
            mainSaw.add(prompt);
            if (mainSaw.size() > 12) {
                throw new AssertionError("the loop did not stop: " + mainSaw.size() + " prompts");
            }
            chunks.accept("main working");
        }, List::of, message -> said.add(JSON.valueToTree(message)), boundaryOver(ledger, 3), AgentRecorder.off(),
            () -> (prompt, chunks) -> {
                sideSaw.add(prompt);
                chunks.accept("side answer");
            });
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
        String main = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
        server.receive(JsonRpcMessage.request(2, "session/set_config_option",
            JSON.createObjectNode().put("sessionId", main).put("configId", "autonomy").put("value", "on")), Channel.HOST);
        ObjectNode sideParams = JSON.createObjectNode().put("cwd", "/w");
        sideParams.putObject("_meta").put("jawata/side", true);
        said.clear();
        server.receive(JsonRpcMessage.request(3, "session/new", sideParams));
        String side = said.stream().filter(m -> m.has("id") && m.get("id").asInt() == 3).findFirst().orElseThrow().path("result").path("sessionId").asText();

        List<PlanLedger.Pending> before = ledger.pending();
        ObjectNode ask = JSON.createObjectNode().put("sessionId", side);
        ask.putArray("prompt").addObject().put("type", "text").put("text", "how many files did you read?");
        said.clear();
        server.receive(JsonRpcMessage.request(4, "session/prompt", ask), Channel.HOST);

        assertEquals(List.of("how many files did you read?"), sideSaw, "the side thread's own brain was not the one asked");
        assertEquals(List.of(), mainSaw, "the side question reached the main conversation's brain");
        assertFalse(before.isEmpty(), "CONTROL: the ledger held nothing pending, so an unchanged ledger proves nothing");
        assertEquals(before, ledger.pending(), "the side question moved the step ledger");
        assertTrue(said.stream().noneMatch(m -> m.path("params").path("update").path("content").path("text").asText().contains("Autonomy ended")),
            "a question on the side thread ended the main conversation's grant");

        ObjectNode build = JSON.createObjectNode().put("sessionId", main);
        build.putArray("prompt").addObject().put("type", "text").put("text", "build it");
        said.clear();
        server.receive(JsonRpcMessage.request(5, "session/prompt", build), Channel.BRAIN);
        assertEquals(4, mainSaw.size(), "the side question spent tries or ended the grant: " + mainSaw);
    }

    @Test
    @DisplayName("one session's typed message restarts that session's tries and no other session's: the tries are kept per session on one connection")
    void aPersonsMessageInOneSessionLeavesTheOtherSessionsTriesAlone() {
        PlanLedger ledger = ledgerWithTwoSteps();
        List<String> saw = new ArrayList<>();
        List<JsonNode> said = new ArrayList<>();
        AcpServer server = new AcpServer((prompt, chunks) -> {
            saw.add(prompt);
            if (saw.size() > 20) {
                throw new AssertionError("the loop did not stop: " + saw.size() + " prompts");
            }
            chunks.accept("working");
        }, List::of, message -> said.add(JSON.valueToTree(message)), boundaryOver(ledger, 3), AgentRecorder.off(), null);
        String[] ids = new String[2];
        for (int i = 0; i < 2; i++) {
            said.clear();
            server.receive(JsonRpcMessage.request(10 + i, "session/new", JSON.createObjectNode().put("cwd", "/w")));
            ids[i] = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
        }
        server.receive(JsonRpcMessage.request(20, "session/set_config_option",
            JSON.createObjectNode().put("sessionId", ids[0]).put("configId", "autonomy").put("value", "on")), Channel.HOST);

        server.receive(JsonRpcMessage.request(30, "session/prompt", promptParams(ids[0], "build it")), Channel.BRAIN);
        assertEquals(4, saw.size(), "CONTROL: session one's tries run to the ceiling (one turn, three resumptions): " + saw);

        server.receive(JsonRpcMessage.request(31, "session/prompt", promptParams(ids[1], "the person speaks in session two")), Channel.HOST);
        int afterTwo = saw.size();
        server.receive(JsonRpcMessage.request(32, "session/prompt", promptParams(ids[0], "a subagent reports back")), Channel.BRAIN);

        assertEquals(1, saw.size() - afterTwo, "the person's message in session two gave session one its tries back: " + saw);
    }

    private static ObjectNode promptParams(String session, String text) {
        ObjectNode params = JSON.createObjectNode().put("sessionId", session);
        params.putArray("prompt").addObject().put("type", "text").put("text", text);
        return params;
    }

    @Test
    @DisplayName("the person's cancel stops a running prompt with stop reason cancelled, and the brain is not resumed on the step that still waits")
    void aCancelEndsTheRunningPrompt() throws Exception {
        List<JsonNode> said = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch inBrain = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger turns = new java.util.concurrent.atomic.AtomicInteger();
        String[] session = new String[1];
        AcpServer[] self = new AcpServer[1];
        AgentBrain slow = (prompt, chunks) -> {
            turns.incrementAndGet();
            ObjectNode on = JSON.createObjectNode().put("sessionId", session[0]).put("configId", "autonomy").put("value", "on");
            self[0].receive(JsonRpcMessage.request(50, "session/set_config_option", on), Channel.HOST);
            inBrain.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        self[0] = new AcpServer(slow, List::of, message -> said.add(JSON.valueToTree(message)), boundaryOver(ledgerWithTwoSteps(), 3), AgentRecorder.off(), null);
        self[0].receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
        session[0] = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
        ObjectNode params = JSON.createObjectNode().put("sessionId", session[0]);
        params.putArray("prompt").addObject().put("type", "text").put("text", "build it");
        Thread running = new Thread(() -> self[0].receive(JsonRpcMessage.request(9, "session/prompt", params)));
        running.start();
        assertTrue(inBrain.await(10, java.util.concurrent.TimeUnit.SECONDS), "the brain never started");

        self[0].receive(JsonRpcMessage.notification("session/cancel", JSON.createObjectNode().put("sessionId", session[0])));
        running.join(10_000);

        assertFalse(running.isAlive(), "the prompt kept running after the person cancelled it");
        assertEquals("cancelled", said.stream().filter(m -> m.has("id") && m.get("id").asInt() == 9).findFirst().orElseThrow()
            .path("result").path("stopReason").asText());
        assertEquals(1, turns.get(), "a cancelled prompt was resumed on the waiting step");
    }
}
