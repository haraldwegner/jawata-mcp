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

    private static TurnBoundary boundaryOver(PlanLedger ledger) {
        return new TurnBoundary(new ToolStepLedger(toolsOver(ledger)), new AttemptLedger());
    }

    private static PrincipalSession session(boolean autonomy) {
        PrincipalSession session = new PrincipalSession("s1", "/w", false);
        session.setAutonomy(Channel.HOST, autonomy);
        return session;
    }

    /** What the agent said to the chat so far, as the chat shows it. */
    private static String textSaid(List<JsonNode> said) {
        StringBuilder text = new StringBuilder();
        for (JsonNode m : said) {
            JsonNode update = m.path("params").path("update");
            if ("agent_message_chunk".equals(update.path("sessionUpdate").asText())) {
                text.append(update.path("content").path("text").asText());
            }
        }
        return text.toString();
    }

    /**
     * Waits for the turn that switching autonomy on starts by itself (28h z99) to say {@code marker}, then lets it finish. The turn runs on the
     * server's own thread, so what the brain was asked is counted only after it has ended.
     */
    private static void awaitSaid(List<JsonNode> said, String marker) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (!textSaid(said).contains(marker) && System.currentTimeMillis() < end) {
            Thread.sleep(25);
        }
        assertTrue(textSaid(said).contains(marker), "the turn that switching autonomy on starts never said '" + marker + "': " + textSaid(said));
        Thread.sleep(200);
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

        assertEquals(TurnBoundary.Kind.RESUME, boundaryOver(ledger).atTurnEnd(person).kind(), "CONTROL: a waiting step must not be forwarded");
        assertEquals(TurnBoundary.Kind.FORWARD, boundaryOver(new PlanLedger()).atTurnEnd(person).kind());

        pass(ledger, "compile_workspace", "run_tests");
        assertEquals(TurnBoundary.Kind.FORWARD, boundaryOver(ledger).atTurnEnd(person).kind(), "both gates passed and the turn was still held");
    }

    @Test
    @DisplayName("a plan whose work has ended no longer holds the turn and no longer shows in plan_steps, though a step of it never passed")
    void aClosedPlanNoLongerHoldsTheTurnOrShowsInPlanSteps() {
        PlanLedger ledger = ledgerWithTwoSteps();
        PrincipalSession person = session(true);
        assertEquals(TurnBoundary.Kind.RESUME, boundaryOver(ledger).atTurnEnd(person).kind(), "CONTROL: while open, its waiting step holds the turn");

        ledger.close(ledger.pending().get(0).id());

        assertEquals(TurnBoundary.Kind.FORWARD, boundaryOver(ledger).atTurnEnd(person).kind(), "a closed plan still held the turn");
        assertEquals(0, JSON.valueToTree(new PlanStepsTool(ledger).execute(JSON.createObjectNode()).getData()).path("pending").size(),
            "a closed plan still shows in plan_steps");
    }

    @Test
    @DisplayName("while the person is at the keyboard a waiting step is reported by name with its gate, and nobody resumes it")
    void aWaitingStepWithTheGrantOffIsReported() {
        TurnBoundary.Verdict verdict = boundaryOver(ledgerWithTwoSteps()).atTurnEnd(session(false));

        assertEquals(TurnBoundary.Kind.REPORT, verdict.kind());
        assertTrue(verdict.text().contains("step 0") && verdict.text().contains("compile_workspace"), verdict.text());
    }

    @Test
    @DisplayName("28h z132: under a grant every resumption is made and numbered, none is refused for its number, and the person's word restarts the count")
    void everyResumptionIsCountedAndNoneIsRefused() {
        TurnBoundary boundary = boundaryOver(ledgerWithTwoSteps());
        PrincipalSession person = session(true);
        List<TurnBoundary.Kind> kinds = new ArrayList<>();
        TurnBoundary.Verdict last = null;
        for (int i = 0; i < 5; i++) {
            last = boundary.atTurnEnd(person);
            kinds.add(last.kind());
        }

        assertEquals(java.util.Collections.nCopies(5, TurnBoundary.Kind.RESUME), kinds);
        assertTrue(last.text().contains("resumption 5") && last.text().contains("compile_workspace"),
            "the count or the gate is not shown: " + last.text());

        boundary.personSpoke(person);
        assertTrue(boundary.atTurnEnd(person).text().contains("resumption 1"), "the person's word did not restart the count");
    }

    @Test
    @DisplayName("two sessions waiting on the same step each count their own resumptions")
    void eachSessionSpendsItsOwnTries() {
        TurnBoundary boundary = boundaryOver(ledgerWithTwoSteps());
        PrincipalSession one = new PrincipalSession("one", "/w", false);
        PrincipalSession two = new PrincipalSession("two", "/w", false);
        one.setAutonomy(Channel.HOST, true);
        two.setAutonomy(Channel.HOST, true);
        for (int i = 0; i < 3; i++) {
            boundary.atTurnEnd(one);
        }
        String ofOne = boundary.atTurnEnd(one).text();

        TurnBoundary.Verdict other = boundary.atTurnEnd(two);

        assertTrue(ofOne.contains("resumption 4 of this step"), "CONTROL: session one is not at its fourth: " + ofOne);
        assertTrue(other.text().contains("resumption 1 of this step"), "session one's count ran into session two: " + other.text());
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

    /**
     * Ends a fake brain's loop. Nothing refuses a resumption for its number (28h z132), so a fake brain that never passes its step would be
     * resumed for ever: after the third resumption of a turn the brake reports the step as being worked on, and the turn ends there.
     */
    private static final class Brake {
        private final java.util.concurrent.atomic.AtomicBoolean on = new java.util.concurrent.atomic.AtomicBoolean();
        private int resumptions;

        /** The fake brain says what it was asked, at each prompt. */
        synchronized void prompted(String prompt) {
            resumptions = prompt.contains("This is resumption") ? resumptions + 1 : 0;
            on.set(resumptions >= 3);
        }

        TurnBoundary over(PlanLedger ledger) {
            StepLedger real = new ToolStepLedger(toolsOver(ledger));
            return new TurnBoundary(session -> real.pending(session).stream()
                .map(step -> new StepLedger.Pending(step.planId(), step.index(), step.gateTool(), step.inFlight() || on.get())).toList(),
                new AttemptLedger());
        }
    }

    /** One agent connection with a brain that answers {@code says}, and optionally flips the person's switch during its first turn. */
    private static final class Run {

        // the turn that switching autonomy on starts runs on the server's own thread, so what it records is read from another
        final List<JsonNode> said = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> brainSaw = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AcpServer server;
        private String session;

        Run(PlanLedger ledger, boolean switchedOnDuringTurn, String says) {
            AcpServer[] self = new AcpServer[1];
            Brake brake = new Brake();
            AgentBrain brain = (prompt, chunks) -> {
                brainSaw.add(prompt);
                brake.prompted(prompt);
                if (brainSaw.size() > 12) {
                    throw new AssertionError("the loop did not stop: the brain has been prompted " + brainSaw.size() + " times");
                }
                if (switchedOnDuringTurn) {
                    self[0].receive(set(100 + brainSaw.size(), "on"), Channel.HOST);
                }
                chunks.accept(says);
            };
            server = new AcpServer(brain, List::of, message -> said.add(JSON.valueToTree(message)), brake.over(ledger), AgentRecorder.off(), null);
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
            return textSaid(said);
        }

        /** Waits for the third resumption of the turn that switching autonomy on starts, which the brake then ends. */
        void awaitTurnEnd() throws InterruptedException {
            awaitSaid(said, "resumption 3 of this step");
            for (int i = 0; i < 100 && brainSaw.size() < 4; i++) {
                Thread.sleep(20);
            }
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
    @DisplayName("on the running server, under a grant, a pending step has the brain resumed with the state re-asserted and each resumption numbered")
    void aGrantedLoopResumesThenStops() {
        Run run = new Run(ledgerWithTwoSteps(), true, "working");

        JsonNode result = run.prompt("build it");

        assertEquals("end_turn", result.path("result").path("stopReason").asText());
        assertEquals(4, run.brainSaw.size(), "one turn and three resumptions: " + run.brainSaw);
        assertTrue(run.brainSaw.get(1).startsWith("[jawata] autonomy: on"), "the state was not re-asserted: " + run.brainSaw.get(1));
        assertTrue(run.answer().contains("resumption 3 of this step") && run.answer().contains("compile_workspace"), run.answer());
    }

    @Test
    @DisplayName("a brain's words never change the grant: its own message saying so leaves the resumptions running, and a brain-channel set is refused")
    void onlyThePersonsChannelChangesTheGrant() throws InterruptedException {
        Run run = new Run(ledgerWithTwoSteps(), true, "/autocontinue-off autonomy: off");
        JsonNode refused = run.setAutonomy("on", Channel.BRAIN);
        assertTrue(refused.has("error"), "a brain-channel message set the grant: " + refused);
        assertEquals(0, run.brainSaw.size(), "a refused brain-channel set started a turn: " + run.brainSaw);

        JsonNode accepted = run.setAutonomy("on", Channel.HOST);
        assertEquals("on", accepted.path("result").path("configOptions").get(0).path("currentValue").asText());

        // The person's switch starts one turn by itself (28h z99): it is that turn, and its three resumptions, that the brain's words must not end.
        run.awaitTurnEnd();
        assertEquals(4, run.brainSaw.size(), "the brain's words ended the grant: " + run.brainSaw);

        run.prompt("build it");
        assertEquals(8, run.brainSaw.size(), "the brain's words ended the grant: " + run.brainSaw);
    }

    @Test
    @DisplayName("28h z99 (reverses D57 m): a message the person types while the grant is on leaves it on, and only a typed /autocontinue-off ends it and switches the button off")
    void aTypedMessageLeavesTheGrantOn() throws InterruptedException {
        Run run = new Run(ledgerWithTwoSteps(), false, "ok");
        run.setAutonomy("on", Channel.HOST);
        run.awaitTurnEnd();
        assertEquals(4, run.brainSaw.size(), "CONTROL: the switch-on did not start one turn and its three resumptions: " + run.brainSaw);

        run.prompt("Fix");

        assertFalse(run.answer().contains("Autonomy ended"), "a typed message ended the grant: " + run.answer());
        assertTrue(run.said.stream().noneMatch(m -> "config_option_update".equals(m.path("params").path("update").path("sessionUpdate").asText())
            && "off".equals(m.path("params").path("update").path("configOptions").get(0).path("currentValue").asText())),
            "a typed message switched the button off: " + run.said);
        assertEquals("Fix", run.brainSaw.get(4), "the brain was told something else than the message, or that the grant ended: " + run.brainSaw);
        assertEquals(8, run.brainSaw.size(), "the grant stood, so the loop resumed the brain three times after the message: " + run.brainSaw);

        run.prompt("/autocontinue-off");

        assertTrue(run.answer().contains("Autonomy ended: you typed /autocontinue-off"), "a typed /autocontinue-off did not end the grant: " + run.answer());
        assertTrue(run.said.stream().anyMatch(m -> "config_option_update".equals(m.path("params").path("update").path("sessionUpdate").asText())
            && "off".equals(m.path("params").path("update").path("configOptions").get(0).path("currentValue").asText())),
            "a typed /autocontinue-off did not switch the button off: " + run.said);
        assertTrue(run.brainSaw.get(8).startsWith("[jawata] autonomy: off"), "the brain was not told the grant ended: " + run.brainSaw.get(8));
        assertEquals(9, run.brainSaw.size(), "with the grant ended the brain was resumed: " + run.brainSaw);
    }

    @Test
    @DisplayName("D57 (m): the brain is told of every change of the grant, by the button and by a typed command, so the hook inside it obeys the same switch")
    void theBrainIsToldOfEveryChangeOfTheGrant() throws InterruptedException {
        List<Boolean> told = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<JsonNode> said = new java.util.concurrent.CopyOnWriteArrayList<>();
        Brake brake = new Brake();
        AgentBrain brain = new AgentBrain() {
            @Override
            public void answer(String prompt, java.util.function.Consumer<String> chunks) {
                brake.prompted(prompt);
                chunks.accept("ok");
            }

            @Override
            public void autonomyChanged(boolean on) {
                told.add(on);
            }
        };
        // a step is open, so the grant the switch gives stands until the person ends it: with none open the engine would end it itself
        AcpServer server = new AcpServer(brain, List::of, message -> said.add(JSON.valueToTree(message)), brake.over(ledgerWithTwoSteps()),
            AgentRecorder.off(), null);
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
        String session = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
        // what the opening of the session told is not a change of the grant
        told.clear();

        server.receive(JsonRpcMessage.request(2, "session/set_config_option",
            JSON.createObjectNode().put("sessionId", session).put("configId", "autonomy").put("value", "on")), Channel.HOST);
        awaitSaid(said, "resumption 3 of this step");
        ObjectNode off = JSON.createObjectNode().put("sessionId", session);
        off.putArray("prompt").addObject().put("type", "text").put("text", "/autocontinue-off");
        server.receive(JsonRpcMessage.request(3, "session/prompt", off), Channel.HOST);

        assertEquals(List.of(true, false), told, "the brain was not told of the button's switch and the typed /autocontinue-off");
    }

    @Test
    @DisplayName("28h-C26-16: a session opened after another was granted autonomy tells the brain off, so the hook inside it does not go on with the earlier grant; a side thread tells it nothing")
    void aNewSessionTellsTheBrainItsOwnGrant() throws InterruptedException {
        List<Boolean> told = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<JsonNode> said = new java.util.concurrent.CopyOnWriteArrayList<>();
        Brake brake = new Brake();
        AgentBrain brain = new AgentBrain() {
            @Override
            public void answer(String prompt, java.util.function.Consumer<String> chunks) {
                brake.prompted(prompt);
                chunks.accept("ok");
            }

            @Override
            public void autonomyChanged(boolean on) {
                told.add(on);
            }
        };
        // a step is open, so the first session's grant stands after its switch-on: with none open the engine would end it itself
        AcpServer server = new AcpServer(brain, List::of, message -> said.add(JSON.valueToTree(message)), brake.over(ledgerWithTwoSteps()),
            AgentRecorder.off(), null);
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
        String first = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
        server.receive(JsonRpcMessage.request(2, "session/set_config_option",
            JSON.createObjectNode().put("sessionId", first).put("configId", "autonomy").put("value", "on")), Channel.HOST);
        awaitSaid(said, "resumption 3 of this step");
        told.clear();

        ObjectNode side = JSON.createObjectNode().put("cwd", "/w");
        side.putObject("_meta").put("jawata/side", true);
        server.receive(JsonRpcMessage.request(3, "session/new", side));
        assertEquals(List.of(), told, "a side question ended the grant of the conversation beside it");

        server.receive(JsonRpcMessage.request(4, "session/new", JSON.createObjectNode().put("cwd", "/w")));
        assertEquals(List.of(false), told, "the new session shows off while the brain was left on");
    }

    @Test
    @DisplayName("D57 (m): a typed /autocontinue switches the grant on and /autocontinue-off switches it off, exactly as the button does, and both are shown")
    void aTypedAutocontinueSwitchesTheGrantLikeTheButton() {
        Run run = new Run(new PlanLedger(), false, "ok");

        run.prompt("/autocontinue");
        assertTrue(run.said.stream().anyMatch(m -> "config_option_update".equals(m.path("params").path("update").path("sessionUpdate").asText())
            && "on".equals(m.path("params").path("update").path("configOptions").get(0).path("currentValue").asText())),
            "a typed /autocontinue did not switch the button on: " + run.said);
        assertTrue(run.brainSaw.get(0).startsWith("[jawata] autonomy: on"), "the brain was not told the grant began: " + run.brainSaw.get(0));

        run.said.clear();
        run.prompt("/autocontinue-off");
        assertTrue(run.said.stream().anyMatch(m -> "config_option_update".equals(m.path("params").path("update").path("sessionUpdate").asText())
            && "off".equals(m.path("params").path("update").path("configOptions").get(0).path("currentValue").asText())),
            "a typed /autocontinue-off did not switch the button off: " + run.said);
        assertTrue(run.brainSaw.get(run.brainSaw.size() - 1).startsWith("[jawata] autonomy: off"),
            "the brain was not told the grant ended: " + run.brainSaw);
    }

    @Test
    @DisplayName("a prompt that arrives on the hired agent's channel is not the person's word: it neither ends the grant nor restarts the tries")
    void aBrainChannelPromptIsNotThePersonsWord() throws InterruptedException {
        Run run = new Run(ledgerWithTwoSteps(), false, "working");
        run.setAutonomy("on", Channel.HOST);
        // the switch-on starts one turn by itself (28h z99) and spends the tries on the waiting step: one turn and three resumptions
        run.awaitTurnEnd();
        assertEquals(4, run.brainSaw.size(), "CONTROL: the switch-on did not start one turn and its three resumptions: " + run.brainSaw);
        ObjectNode params = JSON.createObjectNode().put("sessionId", run.session);
        params.putArray("prompt").addObject().put("type", "text").put("text", "a subagent reports back");

        run.call(9, JsonRpcMessage.request(9, "session/prompt", params), Channel.BRAIN);

        assertFalse(run.answer().contains("Autonomy ended"), "a hired agent's prompt ended the grant: " + run.answer());
        assertTrue(run.said.stream().noneMatch(m -> "config_option_update".equals(m.path("params").path("update").path("sessionUpdate").asText())),
            "a hired agent's prompt changed the switch");
        // the grant stood (so the turn was held on the waiting step and resumed again), and the count was not restarted
        assertEquals(8, run.brainSaw.size(), "the hired agent's prompt ended the grant: " + run.brainSaw);
        assertEquals("a subagent reports back", run.brainSaw.get(4));
        assertTrue(run.answer().contains("resumption 4 of this step"), "the grant did not stand, or the count was restarted: " + run.answer());

        run.prompt("/autocontinue-off");
        assertTrue(run.answer().contains("Autonomy ended"), "CONTROL: the person's own /autocontinue-off must still end the grant: " + run.answer());
    }

    @Test
    @DisplayName("a side question put beside a granted main conversation is answered by its own brain and leaves the steps, the tries and the grant as they were")
    void aSideThreadLeavesTheStepsTheTriesAndTheGrantAsTheyWere() throws InterruptedException {
        PlanLedger ledger = ledgerWithTwoSteps();
        List<String> mainSaw = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<String> sideSaw = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<JsonNode> said = new java.util.concurrent.CopyOnWriteArrayList<>();
        Brake brake = new Brake();
        AcpServer server = new AcpServer((prompt, chunks) -> {
            mainSaw.add(prompt);
            brake.prompted(prompt);
            if (mainSaw.size() > 12) {
                throw new AssertionError("the loop did not stop: " + mainSaw.size() + " prompts");
            }
            chunks.accept("main working");
        }, List::of, message -> said.add(JSON.valueToTree(message)), brake.over(ledger), AgentRecorder.off(),
            () -> (prompt, chunks) -> {
                sideSaw.add(prompt);
                chunks.accept("side answer");
            });
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
        String main = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
        server.receive(JsonRpcMessage.request(2, "session/set_config_option",
            JSON.createObjectNode().put("sessionId", main).put("configId", "autonomy").put("value", "on")), Channel.HOST);
        // the switch-on starts the main conversation's own turn (28h z99): one turn and three resumptions
        awaitSaid(said, "resumption 3 of this step");
        for (int i = 0; i < 100 && mainSaw.size() < 4; i++) {
            Thread.sleep(20);
        }
        assertEquals(4, mainSaw.size(), "CONTROL: the switch-on did not start one turn and its three resumptions: " + mainSaw);
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
        assertEquals(4, mainSaw.size(), "the side question reached the main conversation's brain: " + mainSaw);
        assertFalse(before.isEmpty(), "CONTROL: the ledger held nothing pending, so an unchanged ledger proves nothing");
        assertEquals(before, ledger.pending(), "the side question moved the step ledger");
        assertTrue(said.stream().noneMatch(m -> m.path("params").path("update").path("content").path("text").asText().contains("Autonomy ended")),
            "a question on the side thread ended the main conversation's grant");

        ObjectNode build = JSON.createObjectNode().put("sessionId", main);
        build.putArray("prompt").addObject().put("type", "text").put("text", "build it");
        said.clear();
        server.receive(JsonRpcMessage.request(5, "session/prompt", build), Channel.BRAIN);
        // the grant stood and the count was as the turn left it: the step is resumed again, numbered from where it was
        assertEquals(8, mainSaw.size(), "the side question ended the grant: " + mainSaw);
        assertTrue(textSaid(said).contains("resumption 4 of this step"), "the side question ended the grant or restarted the count: " + textSaid(said));
    }

    @Test
    @DisplayName("one session's typed message restarts that session's tries and no other session's: the tries are kept per session on one connection")
    void aPersonsMessageInOneSessionLeavesTheOtherSessionsTriesAlone() throws InterruptedException {
        PlanLedger ledger = ledgerWithTwoSteps();
        List<String> saw = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<JsonNode> said = new java.util.concurrent.CopyOnWriteArrayList<>();
        Brake brake = new Brake();
        AcpServer server = new AcpServer((prompt, chunks) -> {
            saw.add(prompt);
            brake.prompted(prompt);
            if (saw.size() > 20) {
                throw new AssertionError("the loop did not stop: " + saw.size() + " prompts");
            }
            chunks.accept("working");
        }, List::of, message -> said.add(JSON.valueToTree(message)), brake.over(ledger), AgentRecorder.off(), null);
        String[] ids = new String[2];
        for (int i = 0; i < 2; i++) {
            said.clear();
            server.receive(JsonRpcMessage.request(10 + i, "session/new", JSON.createObjectNode().put("cwd", "/w")));
            ids[i] = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
        }
        server.receive(JsonRpcMessage.request(20, "session/set_config_option",
            JSON.createObjectNode().put("sessionId", ids[0]).put("configId", "autonomy").put("value", "on")), Channel.HOST);

        // the switch-on starts session one's own turn (28h z99): one turn and three resumptions
        awaitSaid(said, "resumption 3 of this step");
        for (int i = 0; i < 100 && saw.size() < 4; i++) {
            Thread.sleep(20);
        }
        assertEquals(4, saw.size(), "CONTROL: the switch-on did not start one turn and three resumptions: " + saw);

        server.receive(JsonRpcMessage.request(31, "session/prompt", promptParams(ids[1], "the person speaks in session two")), Channel.HOST);
        int afterTwo = saw.size();
        said.clear();
        server.receive(JsonRpcMessage.request(32, "session/prompt", promptParams(ids[0], "a subagent reports back")), Channel.BRAIN);

        // session one's count was not restarted by the person's word in session two: it goes on at 4
        assertTrue(saw.size() - afterTwo == 4 && textSaid(said).contains("resumption 4 of this step"),
            "the person's message in session two restarted session one's count: " + saw);
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
        self[0] = new AcpServer(slow, List::of, message -> said.add(JSON.valueToTree(message)), boundaryOver(ledgerWithTwoSteps()), AgentRecorder.off(), null);
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
