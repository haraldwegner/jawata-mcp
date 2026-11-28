package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.mcp.step.GateOutcome;
import org.jawata.mcp.step.PlanId;
import org.jawata.mcp.tools.PlanStepsTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 28h Stage 28 (i), slice 3 (M1, M2), end to end through the server: over the production step ledger, a real {@code plan_steps} tool and a real
 * plan ledger, with a stand-in brain that ends its turn. No agent is started but the stand-in brain.
 */
class AcpServerBackstopTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The key the brain's own tool calls carry, which owns the plan it enters. */
    private static final String KEY = "main-brain-key";

    private final List<Wire> opened = new ArrayList<>();

    @AfterEach
    void closeTheServers() {
        opened.forEach(wire -> wire.server.closed());
    }

    private static final class Brain implements AgentBrain {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        volatile Runnable afterAnswer = () -> { };

        @Override
        public void answer(String prompt, Consumer<String> chunks) {
            prompts.add(prompt);
            chunks.accept("ok");
            afterAnswer.run();
        }

        @Override
        public Optional<String> toolSessionKey() {
            return Optional.of(KEY);
        }
    }

    private final class Wire {
        final List<JsonNode> sent = new CopyOnWriteArrayList<>();
        final Brain brain = new Brain();
        final PlanLedger plans = new PlanLedger();
        final AtomicInteger otherAgentsStarted = new AtomicInteger();
        final AcpServer server;
        final String session;
        private final AtomicInteger ids = new AtomicInteger(100);

        Wire() {
            PlanStepsTool steps = new PlanStepsTool(plans);
            ToolCalls calls = new ToolCalls() {
                @Override public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                    return "plan_steps".equals(name) ? caller.speaking(() -> steps.execute(arguments)) : ToolResponse.error("NO_SUCH_TOOL", name, "none");
                }
                @Override public List<Map<String, Object>> getToolDefinitions() {
                    return List.of();
                }
            };
            TurnBoundary boundary = new TurnBoundary(new ToolStepLedger(calls, brain.toolSessionKey()), new AttemptLedger(),
                s -> !org.jawata.mcp.execution.HireRegistry.shared().runningNamesFor(s.id()).isEmpty());
            server = new AcpServer(brain, List::of, message -> sent.add(JSON.valueToTree(message)), boundary, AgentRecorder.off(), () -> {
                otherAgentsStarted.incrementAndGet();
                return brain;
            });
            opened.add(this);
            JsonNode created = send("session/new", JSON.createObjectNode().put("cwd", "/workspace"));
            session = created.path("result").path("sessionId").asText();
            assertFalse(session.isEmpty(), "CONTROL: no session was opened: " + created);
        }

        /** The brain's own call: it enters a plan whose steps are the given gates, the first one marked a checkpoint when asked. */
        PlanId enter(boolean firstIsCheckpoint, String... gates) {
            ObjectNode arguments = JSON.createObjectNode().put("action", "enter").put("name", "sprint");
            for (int i = 0; i < gates.length; i++) {
                ObjectNode step = arguments.withArray("steps").addObject().put("title", "Step " + gates[i]).put("checkpoint", i == 0 && firstIsCheckpoint);
                step.putObject("check").put("tool", gates[i]);
            }
            ToolResponse answer = CallerIdentity.supplied(KEY).speaking(() -> new PlanStepsTool(plans).execute(arguments));
            return new PlanId(JSON.valueToTree(answer.getData()).path("planId").asText());
        }

        void pass(PlanId id, String gate) {
            plans.advanceIfGatePassed(id, new GateOutcome(gate, JSON.createObjectNode(), 0, true));
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

        void autonomyOn() {
            send("session/set_config_option", JSON.createObjectNode().put("sessionId", session).put("configId", AcpServer.AUTONOMY).put("value", "on"));
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
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("28h Stage 28 (i) M1: with autonomy on and a plan entered, the brain that ends its turn with a step open and no hire running is prompted again with no message of the person's, at each turn end until the step passes, and no other agent starts")
    void aBrainThatEndsItsTurnWithAStepOpenIsSentBackUntilTheStepPasses() {
        Wire wire = new Wire();
        PlanId id = wire.enter(false, "compile_workspace");
        wire.brain.afterAnswer = () -> {
            if (wire.brain.prompts.size() == 3) {
                wire.pass(id, "compile_workspace");
            }
        };

        wire.autonomyOn();

        assertTrue(wire.waitFor(() -> wire.brain.prompts.size() >= 3, 5_000), "the brain was not sent back again and again: " + wire.brain.prompts);
        pause(500);
        assertEquals(3, wire.brain.prompts.size(), "the brain was prompted after the step passed: " + wire.brain.prompts);
        assertTrue(wire.brain.prompts.get(1).startsWith("[jawata]"), "the second prompt is not the engine's own: " + wire.brain.prompts.get(1));
        assertTrue(wire.brain.prompts.get(2).startsWith("[jawata]"), "the third prompt is not the engine's own: " + wire.brain.prompts.get(2));
        assertEquals(0, wire.otherAgentsStarted.get(), "an agent other than the brain was started");
    }

    @Test
    @DisplayName("28h Stage 28 (i) M2: when the plan's checkpoint step has passed and the brain ends its turn, the brain is not prompted again and the chat tells where the plan stands")
    void aReachedCheckpointEndsTheTurnAndTellsWhereThePlanStands() {
        Wire wire = new Wire();
        PlanId id = wire.enter(true, "compile_workspace", "run_tests");
        wire.pass(id, "compile_workspace");

        wire.autonomyOn();

        assertTrue(wire.waitFor(() -> wire.agentSaid().contains("Plan standing:"), 5_000), "the chat was not told where the plan stands: [" + wire.agentSaid() + "]");
        pause(500);
        assertEquals(1, wire.brain.prompts.size(), "the brain was sent back after the checkpoint: " + wire.brain.prompts);
        assertTrue(wire.agentSaid().contains("Checkpoint reached:"), wire.agentSaid());
        assertEquals(0, wire.otherAgentsStarted.get(), "an agent other than the brain was started");
    }
}
