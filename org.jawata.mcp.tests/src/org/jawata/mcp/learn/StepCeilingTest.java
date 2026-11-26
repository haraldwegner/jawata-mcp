package org.jawata.mcp.learn;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.step.GateOutcome;
import org.jawata.mcp.step.PlanId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h S12 slice 4 (D18): the per-step action ceiling. A hired session that has spent a step's allowance of engine calls without that
 * step's gate passing is refused its next call, the refusal names the gate, and a step that passes starts its count again.
 *
 * <p>The clock is set by the test, so "calls since the step began" is exact; the session ledger and the plan ledger read the same one.</p>
 */
class StepCeilingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private H2ExperienceStore store;
    private final long[] now = {1000};
    private PlanLedger plans;
    private SessionLedger ledger;
    private ServerChecks checks;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        plans = new PlanLedger(() -> now[0]);
        ledger = new SessionLedger();
        checks = new ServerChecks(new LearnerEventStore(store), (summary, details) -> { },
            org.jawata.mcp.fixtures.ShippedGates.registry(), plans);
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    private static GatedStep step(int index, String tool) {
        return new GatedStep(index, tool, JSON.createObjectNode());
    }

    /** A plan whose hired brain has made its first edit: the only state in which a ceiling applies. */
    private PlanId open(int ceiling, GatedStep... steps) {
        PlanId plan = plans.open(List.of(steps), ceiling);
        plans.editSeen(plan);
        return plan;
    }

    private void call(PlanId plan, String tool) {
        ledger.record(CallerIdentity.supplied(plan.value()), new SessionLedger.CallRecord(tool, true, 0, now[0], null));
    }

    private ToolResponse next(PlanId plan, String tool) {
        return checks.acceptance(CallerIdentity.supplied(plan.value()), tool, JSON.createObjectNode(), ledger);
    }

    @Test
    @DisplayName("S12 D18: after a step's allowance of engine calls without its gate passing, the next call is refused and the refusal names the gate")
    void theNextCallIsRefusedNamingTheGate() {
        PlanId plan = open(3, step(0, "compile_workspace"));
        call(plan, "inspect");
        call(plan, "search_symbols");

        ToolResponse underTheCeiling = next(plan, "inspect");
        call(plan, "inspect");
        ToolResponse refused = next(plan, "inspect");

        assertAll(
            () -> assertNull(underTheCeiling, "two calls of three: the next is allowed"),
            () -> assertNotNull(refused, "three calls of three and the gate has not passed: the next is refused"),
            () -> assertTrue(refused.getError().getHint().contains("compile_workspace"), "the refusal must name the gate: " + refused.getError().getHint()));
    }

    @Test
    @DisplayName("S12 D18: the gate's own tool is never refused and never counted, because refusing it would forbid the only thing that ends the step")
    void theGateItselfIsAlwaysAllowed() {
        PlanId plan = open(2, step(0, "compile_workspace"));
        call(plan, "inspect");
        call(plan, "inspect");

        assertAll(
            () -> assertNotNull(next(plan, "inspect"), "the allowance is spent"),
            () -> assertNull(next(plan, "compile_workspace"), "the gate itself is let through"));
    }

    @Test
    @DisplayName("S12 D18: a step that passes its gate starts its count again")
    void aPassedStepStartsItsCountAgain() {
        PlanId plan = open(2, step(0, "compile_workspace"), step(1, "run_tests"));
        call(plan, "inspect");
        call(plan, "inspect");
        assertNotNull(next(plan, "inspect"), "the first step's allowance is spent");

        now[0] = 2000;
        plans.advanceIfGatePassed(plan, new GateOutcome("compile_workspace", JSON.createObjectNode(), 0, true));
        now[0] = 2001;
        ToolResponse afterTheStepPassed = next(plan, "inspect");
        call(plan, "inspect");
        call(plan, "inspect");
        ToolResponse spentAgain = next(plan, "inspect");

        assertAll(
            () -> assertNull(afterTheStepPassed, "the calls before the advance belong to the step that passed"),
            () -> assertNotNull(spentAgain, "the second step has its own allowance, and spending it refuses"),
            () -> assertTrue(spentAgain.getError().getHint().contains("run_tests")));
    }

    @Test
    @DisplayName("S12 D18, the controls: no ceiling, a session that is no plan's, a closed plan and a step that gates the seat's answer are never refused")
    void whereNoCeilingAppliesNothingIsRefused() {
        PlanId noCeiling = open(0, step(0, "compile_workspace"));
        PlanId closed = open(1, step(0, "compile_workspace"));
        PlanId answer = open(1, step(0, PlanLedger.ANSWER_GATE_PREFIX + "upward_message"), step(1, "compile_workspace"));
        for (int i = 0; i < 5; i++) {
            call(noCeiling, "inspect");
            call(closed, "inspect");
            call(answer, "inspect");
        }
        plans.close(closed);

        assertAll(
            () -> assertNull(next(noCeiling, "inspect"), "a plan opened without a ceiling"),
            () -> assertNull(next(closed, "inspect"), "a closed plan"),
            () -> assertNull(next(answer, "inspect"), "the current step gates the answer, which cannot exist yet"),
            () -> assertNull(checks.acceptance(CallerIdentity.supplied("a-session-that-is-no-plans"), "inspect", JSON.createObjectNode(), ledger),
                "a caller that is not a hired brain"));
    }

    @Test
    @DisplayName("z128 (a): a brain that has completed no editing call cannot pass a step, so 15 reading calls are not refused")
    void readingCallsWithoutAnEditAreNotRefused() {
        PlanId plan = plans.open(List.of(step(0, "compile_workspace")), 3);
        for (int i = 0; i < 15; i++) {
            call(plan, "inspect");
        }

        assertNull(next(plan, "inspect"), "the plan's hire has not completed an editing call: no ceiling applies");
    }

    @Test
    @DisplayName("z128 (b): 12 reading calls and then 'hire' (a lead's call) is not refused")
    void aLeadsHireCallAfterReadingIsNotRefused() {
        PlanId plan = plans.open(List.of(step(0, "compile_workspace")), 3);
        for (int i = 0; i < 12; i++) {
            call(plan, "search_symbols");
        }

        assertNull(next(plan, "hire"), "a lead never edits: no ceiling applies to it");
    }

    @Test
    @DisplayName("z128 (c): the count starts at the first edit, and the refusal names the gate call with its declared arguments")
    void theCountStartsAtTheFirstEditAndTheHintNamesTheDeclaredCall() {
        com.fasterxml.jackson.databind.node.ObjectNode declared = JSON.createObjectNode().put("projectKey", "jawata-mcp");
        PlanId plan = plans.open(List.of(new GatedStep(0, "compile_workspace", declared)), 3);
        for (int i = 0; i < 10; i++) {
            call(plan, "inspect");
        }
        now[0] = 2000;
        plans.editSeen(plan);
        now[0] = 2001;
        ToolResponse rightAfterTheEdit = next(plan, "inspect");
        call(plan, "inspect");
        call(plan, "inspect");
        call(plan, "inspect");
        ToolResponse spent = next(plan, "inspect");

        assertAll(
            () -> assertNull(rightAfterTheEdit, "the reading before the first edit counted against the step"),
            () -> assertNotNull(spent, "three calls after the edit spend the allowance of three"),
            () -> assertTrue(spent.getError().getHint().contains("compile_workspace with {\"projectKey\":\"jawata-mcp\"}"),
                spent.getError().getHint()));
    }
}
