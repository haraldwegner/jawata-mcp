package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jawata.mcp.agent.AttemptLedger;
import org.jawata.mcp.agent.PrincipalSession;
import org.jawata.mcp.agent.StepLedger;
import org.jawata.mcp.agent.ToolStepLedger;
import org.jawata.mcp.agent.TurnBoundary;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.execution.HireRegistry;
import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.step.PlanId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Stage 24, M11 (D55): a step whose hire is running is waiting because someone is working on it. The turn boundary lets the turn end without
 * spending a try on it, {@code plan_steps} says which steps are in flight, and the agent's ledger reads that.
 */
class StepsInFlightTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlanLedger plans = new PlanLedger();
    private final PlanStepsTool tool = new PlanStepsTool(plans);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rowOf(ToolResponse response, PlanId plan) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ((Map<String, Object>) response.getData()).get("pending");
        return rows.stream().filter(row -> plan.toString().equals(row.get("planId"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a turn ends with a step in flight and no try is spent on it; a step nobody is working on is resumed or reported as before")
    void aStepInFlightIsNotAStepLeftBehind() {
        PrincipalSession session = new PrincipalSession("m11-session", "/w", false);
        session.setAutonomy(org.jawata.mcp.agent.Channel.HOST, true);
        TurnBoundary working = new TurnBoundary(s -> List.of(new StepLedger.Pending("plan-working", 0, "compile_workspace", true)), new AttemptLedger(3));
        TurnBoundary stuck = new TurnBoundary(s -> List.of(new StepLedger.Pending("plan-stuck", 0, "compile_workspace", false)), new AttemptLedger(3));

        // Five turn ends in a row: a try is spent for each one a boundary resumes, and the ceiling is three.
        List<TurnBoundary.Kind> whileWorking = new java.util.ArrayList<>();
        List<TurnBoundary.Kind> whileStuck = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            whileWorking.add(working.atTurnEnd(session).kind());
            whileStuck.add(stuck.atTurnEnd(session).kind());
        }

        assertAll(
            () -> assertEquals(List.of(TurnBoundary.Kind.FORWARD, TurnBoundary.Kind.FORWARD, TurnBoundary.Kind.FORWARD, TurnBoundary.Kind.FORWARD, TurnBoundary.Kind.FORWARD),
                whileWorking, "a step whose hire is running held the turn or spent a try"),
            () -> assertEquals(TurnBoundary.Kind.RESUME, whileStuck.get(0), "the control: a step nobody works on is still resumed"),
            () -> assertEquals(TurnBoundary.Kind.REPORT, whileStuck.get(4), "the control: and reported once the tries are used"));
    }

    @Test
    @DisplayName("plan_steps says which steps are in flight, and the agent's ledger reads it")
    void thePlanStepsAnswerSaysWhichStepsAreInFlightAndTheLedgerReadsIt() throws Exception {
        PlanId working = plans.open(List.of(new GatedStep(0, "compile_workspace", JSON.readTree("{}"))));
        PlanId stuck = plans.open(List.of(new GatedStep(0, "compile_workspace", JSON.readTree("{}"))));
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread hire = new Thread(() -> {
            try (HireRegistry.Registration ignored = HireRegistry.shared().begin("developer", "m11 steps in flight", "m11-caller")) {
                HireRegistry.shared().planOpened(working.toString());
                registered.countDown();
                release.await();
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
            }
        });
        hire.setDaemon(true);
        hire.start();
        try {
            assertTrue(registered.await(5, TimeUnit.SECONDS), "the hire did not register as running");
            ToolResponse answer = tool.execute(JSON.createObjectNode());
            ToolCalls asLedgerAsks = new ToolCalls() {
                @Override public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                    return tool.execute(arguments);
                }
                @Override public List<Map<String, Object>> getToolDefinitions() {
                    return List.of();
                }
            };
            List<StepLedger.Pending> read = new ToolStepLedger(asLedgerAsks).pending(new PrincipalSession("m11-reader", "/w", false));

            assertAll(
                () -> assertEquals(true, rowOf(answer, working).get("inFlight"), "the step of a running hire is not said to be in flight"),
                () -> assertEquals(false, rowOf(answer, stuck).get("inFlight"), "the control: a plan nobody works on is said to be in flight"),
                () -> assertTrue(read.stream().anyMatch(p -> p.planId().equals(working.toString()) && p.inFlight()), "the ledger did not read the flag"),
                () -> assertFalse(read.stream().anyMatch(p -> p.planId().equals(stuck.toString()) && p.inFlight()), "the ledger marked a stuck step in flight"));
        } finally {
            release.countDown();
            hire.join(5_000);
        }
        // Once the hire has ended its plan is no longer in flight: whatever is left of it is left behind.
        assertFalse(HireRegistry.shared().isRunningPlan(working.toString()), "the plan of an ended hire is still in flight");
    }
}
