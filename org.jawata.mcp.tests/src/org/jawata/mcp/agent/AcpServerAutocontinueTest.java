package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.mcp.tools.PlanStepsTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, plan row z99: autonomy switched on starts a turn by itself, and a message the person types no longer ends it. Autonomy ends
 * only when the person switches it off (the option or a typed {@code /autocontinue-off}), presses Stop, or when no step is open.
 *
 * <p>The server is built through its six-argument constructor over a recording brain, a boundary whose open step the test controls, and a
 * sink that collects everything the server sends. Every message is the host's.</p>
 */
class AcpServerAutocontinueTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<Wire> opened = new ArrayList<>();

    @AfterEach
    void closeTheServers() {
        opened.forEach(wire -> wire.server.closed());
    }

    /** What answers: it records each prompt and each time it was told autonomy changed, and can be held inside an answer. */
    private static final class Brain implements AgentBrain {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        final List<Boolean> autonomyTold = new CopyOnWriteArrayList<>();
        final CountDownLatch firstAnswerStarted = new CountDownLatch(1);
        volatile CountDownLatch hold;
        volatile Runnable afterAnswer = () -> { };

        @Override
        public void answer(String prompt, Consumer<String> chunks) {
            prompts.add(prompt);
            firstAnswerStarted.countDown();
            CountDownLatch holding = hold;
            if (holding != null) {
                try {
                    holding.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            chunks.accept("ok");
            afterAnswer.run();
        }

        @Override
        public void autonomyChanged(boolean on) {
            autonomyTold.add(on);
        }

        long toldOff() {
            return autonomyTold.stream().filter(on -> !on).count();
        }
    }

    /** The steps waiting for their gate, as the test sets them; whether any plan is known at all is set apart from whether a step is open. */
    private static final class Plan implements StepLedger {
        volatile List<Pending> open = List.of();
        volatile boolean known = true;

        @Override
        public List<Pending> pending(PrincipalSession session) {
            return open;
        }

        @Override
        public boolean planKnown(PrincipalSession session) {
            return known;
        }

        /** One step waits, and the brain's answer passes its gate. */
        void oneStepThatAnswerClears(Brain brain) {
            open = List.of(new Pending("plan-1", 0, "compile_workspace"));
            brain.afterAnswer = () -> open = List.of();
        }
    }

    /** One connection: the server, what it sent, and the host's messages to it. */
    private final class Wire {
        final List<JsonNode> sent = new CopyOnWriteArrayList<>();
        final Brain brain = new Brain();
        final Plan plan = new Plan();
        final AcpServer server;
        final String session;
        private final AtomicInteger ids = new AtomicInteger(100);

        Wire() {
            this(null);
        }

        /** A connection whose boundary reads {@code ledger}, or the test's own {@link Plan} when that is null. */
        Wire(StepLedger ledger) {
            server = new AcpServer(brain, List::of, message -> sent.add(JSON.valueToTree(message)),
                new TurnBoundary(ledger == null ? plan : ledger, new AttemptLedger(3)), AgentRecorder.off(), null);
            opened.add(this);
            JsonNode created = send("session/new", JSON.createObjectNode().put("cwd", "/workspace"));
            session = created.path("result").path("sessionId").asText();
            assertFalse(session.isEmpty(), "CONTROL: no session was opened: " + created);
            // Opening a session tells the brain the grant it starts with; only what is told after that is a change.
            brain.autonomyTold.clear();
        }

        JsonNode send(String method, ObjectNode params) {
            int id = ids.incrementAndGet();
            server.receive(JsonRpcMessage.request(id, method, params));
            for (JsonNode message : sent) {
                if (message.path("id").asInt(-1) == id && (message.has("result") || message.has("error"))) {
                    return message;
                }
            }
            throw new AssertionError("no answer to " + method + " was sent");
        }

        JsonNode setAutonomy(String value) {
            return send("session/set_config_option", JSON.createObjectNode().put("sessionId", session).put("configId", AcpServer.AUTONOMY).put("value", value));
        }

        void type(String text) {
            ObjectNode params = JSON.createObjectNode().put("sessionId", session);
            params.putArray("prompt").addObject().put("type", "text").put("text", text);
            send("session/prompt", params);
        }

        void stop() {
            server.receive(JsonRpcMessage.notification("session/cancel", JSON.createObjectNode().put("sessionId", session)));
        }

        /** The autonomy option as the host last saw it, from an update or from the answer to a set; null when it was never shown. */
        JsonNode autonomyOption() {
            JsonNode last = null;
            for (JsonNode message : sent) {
                JsonNode options = message.path("params").path("update").path("sessionUpdate").asText().equals("config_option_update")
                    ? message.path("params").path("update").path("configOptions")
                    : message.path("result").path("configOptions");
                for (JsonNode option : options) {
                    if (AcpServer.AUTONOMY.equals(option.path("id").asText())) {
                        last = option;
                    }
                }
            }
            return last;
        }

        String autonomyNow() {
            JsonNode option = autonomyOption();
            return option == null ? "never shown" : option.path("currentValue").asText();
        }

        String agentSaid() {
            StringBuilder said = new StringBuilder();
            for (JsonNode message : sent) {
                JsonNode update = message.path("params").path("update");
                if ("agent_message_chunk".equals(update.path("sessionUpdate").asText())) {
                    said.append(update.path("content").path("text").asText());
                }
            }
            return said.toString();
        }

        boolean waitFor(BooleanSupplier condition, long millis) {
            long end = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < end) {
                if (condition.getAsBoolean()) {
                    return true;
                }
                pause(25);
            }
            return condition.getAsBoolean();
        }

        /** Autonomy on, one step open that the brain's answer clears; waits for the turn that starts, as long as one does. */
        void switchOnAndLetTheFirstTurnEnd() {
            plan.oneStepThatAnswerClears(brain);
            setAutonomy("on");
            waitFor(() -> !brain.prompts.isEmpty(), 1_500);
            pause(300);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("z99 (a): switching autonomy on with a step open makes the brain answer once with no message of the person's, and that turn asks it to take the next open step")
    void switchingOnStartsATurnThatTakesTheNextOpenStep() {
        Wire wire = new Wire();
        wire.plan.oneStepThatAnswerClears(wire.brain);

        wire.setAutonomy("on");

        assertTrue(wire.waitFor(() -> !wire.brain.prompts.isEmpty(), 3_000), "switching autonomy on started no turn: the brain was never prompted");
        pause(300);
        assertEquals(1, wire.brain.prompts.size(), "the brain was prompted more than once: " + wire.brain.prompts);
        String text = wire.brain.prompts.get(0);
        assertTrue(text.contains("[jawata] autonomy: on"), text);
        assertTrue(text.contains("next open step"), "the turn does not ask for the next open step: " + text);
        assertTrue(text.contains("compile_workspace"), "the turn does not name the step's gate: " + text);
    }

    @Test
    @DisplayName("z99 (b): with autonomy on, a message the person types leaves it on, and reaches the brain as typed")
    void aTypedMessageDoesNotEndAutonomy() {
        Wire wire = new Wire();
        wire.switchOnAndLetTheFirstTurnEnd();
        int before = wire.brain.prompts.size();

        wire.type("what is the state of the plan?");

        assertEquals("on", wire.autonomyNow(), "a typed message ended autonomy");
        assertEquals(0, wire.brain.toldOff(), "the brain was told autonomy ended: " + wire.brain.autonomyTold);
        assertEquals(before + 1, wire.brain.prompts.size());
        assertEquals("what is the state of the plan?", wire.brain.prompts.get(before), "the message did not reach the brain as typed");
    }

    @Test
    @DisplayName("z99 (c): Stop ends autonomy, and so does the option set to off, and so does a typed /autocontinue-off; each leaves the option off and tells the brain")
    void autonomyEndsOnStopOnOffAndOnTheTypedOff() {
        assertEnds("Stop", wire -> wire.stop());
        assertEnds("the option set to off", wire -> wire.setAutonomy("off"));
        assertEnds("a typed /autocontinue-off", wire -> wire.type("/autocontinue-off"));
    }

    private void assertEnds(String how, Consumer<Wire> ending) {
        Wire wire = new Wire();
        wire.switchOnAndLetTheFirstTurnEnd();
        assertEquals("on", wire.autonomyNow(), "CONTROL (" + how + "): autonomy was not on before it was ended");

        ending.accept(wire);

        assertEquals("off", wire.autonomyNow(), how + " did not end autonomy: the option still reads on");
        assertEquals(1, wire.brain.toldOff(), how + ": the brain was not told once that autonomy ended: " + wire.brain.autonomyTold);
    }

    @Test
    @DisplayName("z99 (d): switching on when no step is open says so in the chat, switches autonomy off, and starts no turn")
    void switchingOnWithNoStepOpenSaysSoAndSwitchesOff() {
        Wire wire = new Wire();
        wire.plan.open = List.of();

        wire.setAutonomy("on");
        pause(500);

        assertTrue(wire.agentSaid().toLowerCase().contains("no step is open"), "the chat was not told that no step is open: [" + wire.agentSaid() + "]");
        assertEquals("off", wire.autonomyNow(), "autonomy stayed on with no step open");
        assertEquals(1, wire.brain.toldOff(), "the brain was not told autonomy ended: " + wire.brain.autonomyTold);
        assertEquals(List.of(), wire.brain.prompts, "a turn started with no step open");
    }

    @Test
    @DisplayName("z99 (e): switching on while a turn is running only sets the flag: no second turn starts, not while it runs and not when it ends")
    void switchingOnWhileATurnRunsStartsNoSecondTurn() throws Exception {
        Wire wire = new Wire();
        wire.plan.oneStepThatAnswerClears(wire.brain);
        wire.brain.hold = new CountDownLatch(1);
        Thread running = new Thread(() -> wire.type("work on it"), "typed-turn");
        running.setDaemon(true);
        running.start();
        assertTrue(wire.brain.firstAnswerStarted.await(5, TimeUnit.SECONDS), "CONTROL: the typed turn never began");

        wire.setAutonomy("on");
        pause(400);

        assertEquals(1, wire.brain.prompts.size(), "a second turn started while one was running: " + wire.brain.prompts);
        assertEquals("on", wire.autonomyNow(), "the flag was not set");

        wire.brain.hold.countDown();
        running.join(10_000);
        pause(400);

        assertEquals(1, wire.brain.prompts.size(), "a second turn started when the running one ended: " + wire.brain.prompts);
        assertEquals("on", wire.autonomyNow());
    }

    @Test
    @DisplayName("z99 (f): switching on when no plan is known starts one turn that asks which plan, once, and does not go on asking")
    void switchingOnWithNoPlanKnownAsksWhichPlanOnce() {
        Wire wire = new Wire();
        wire.plan.known = false;
        wire.plan.open = List.of();

        wire.setAutonomy("on");

        assertTrue(wire.waitFor(() -> !wire.brain.prompts.isEmpty(), 3_000), "no turn started to ask which plan");
        pause(600);
        assertEquals(1, wire.brain.prompts.size(), "the question about the plan was put more than once: " + wire.brain.prompts);
        assertTrue(wire.brain.prompts.get(0).toLowerCase().contains("which plan"), wire.brain.prompts.get(0));
    }

    @Test
    @DisplayName("z99 (h): over the production step ledger and a real plan_steps tool on an empty plan ledger, switching on starts one turn that asks which plan, and does not switch autonomy off")
    void anEmptyPlanLedgerAsksWhichPlanAndKeepsAutonomyOn() {
        PlanStepsTool steps = new PlanStepsTool(new PlanLedger());
        ToolCalls calls = new ToolCalls() {
            @Override public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                return "plan_steps".equals(name) ? steps.execute(arguments) : ToolResponse.error("NO_SUCH_TOOL", name, "none");
            }
            @Override public List<Map<String, Object>> getToolDefinitions() {
                return List.of();
            }
        };
        Wire wire = new Wire(new ToolStepLedger(calls));

        wire.setAutonomy("on");

        assertTrue(wire.waitFor(() -> !wire.brain.prompts.isEmpty(), 3_000), "no turn started to ask which plan: " + wire.agentSaid());
        pause(600);
        assertEquals(1, wire.brain.prompts.size(), "the question about the plan was put more than once: " + wire.brain.prompts);
        assertTrue(wire.brain.prompts.get(0).toLowerCase().contains("which plan"), wire.brain.prompts.get(0));
        assertEquals("on", wire.autonomyNow(), "autonomy was switched off by an empty ledger: " + wire.agentSaid());
        assertEquals(0, wire.brain.toldOff(), "the brain was told autonomy ended: " + wire.brain.autonomyTold);
    }

    @Test
    @DisplayName("z99 (i): switching on when the only open step has a hire working on it leaves autonomy on, starts no turn, and says it is waiting for the hire")
    void aStepWithAHireWorkingOnItKeepsAutonomyOnAndWaits() {
        Wire wire = new Wire();
        wire.plan.open = List.of(new StepLedger.Pending("plan-7", 2, "run_tests", true));

        wire.setAutonomy("on");

        assertTrue(wire.waitFor(() -> wire.agentSaid().contains("waiting for"), 3_000), "the chat was not told it waits: [" + wire.agentSaid() + "]");
        pause(400);
        assertTrue(wire.agentSaid().contains("waiting for plan-7"), "the chat does not name the plan: [" + wire.agentSaid() + "]");
        assertEquals("on", wire.autonomyNow(), "autonomy was switched off while a hire works");
        assertEquals(0, wire.brain.toldOff(), "the brain was told autonomy ended: " + wire.brain.autonomyTold);
        assertEquals(List.of(), wire.brain.prompts, "a turn started while a hire works on the step");
    }

    @Test
    @DisplayName("z99 (g):the Autonomy option no longer says a message you type ends it, and says it ends only on off, on Esc or Stop, or when no step is open")
    void theOptionDescriptionSaysWhatEndsAutonomy() {
        Wire wire = new Wire();

        wire.setAutonomy("off");

        JsonNode option = wire.autonomyOption();
        assertNotNull(option, "the autonomy option was not offered");
        String description = option.path("description").asText();
        assertFalse(description.matches("(?is).*message[^.]*you type[^.]*\\bends\\b.*"), "it still says a typed message ends autonomy: " + description);
        assertTrue(description.contains("ends only when"), "it does not say when autonomy ends: " + description);
        assertTrue(description.contains("Esc") && description.contains("Stop"), "it does not name Esc and Stop: " + description);
        assertTrue(description.contains("no step is open"), "it does not name the end when no step is open: " + description);
    }
}
