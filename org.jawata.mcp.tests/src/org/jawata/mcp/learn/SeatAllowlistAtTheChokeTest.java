package org.jawata.mcp.learn;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Sprint 28h, S13 (the audit of C6, finding F2): the engine refuses, at its one choke, a call by a hired session to a tool outside the list of
 * the seat it was hired as, whatever the brain does about {@code session/request_permission}. A hired session is its plan's id, so the list
 * is read from the plan; the gate of the step the session is working is always allowed, because a brain has to be able to run the gate that
 * ends the step.
 *
 * <p>Each refusal comes with the control that reaches the same branch and is let through: a check that refused everything, or nothing,
 * would pass one half.</p>
 */
class SeatAllowlistAtTheChokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private H2ExperienceStore store;
    private PlanLedger plans;
    private ServerChecks checks;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        plans = new PlanLedger();
        checks = new ServerChecks(new LearnerEventStore(store), (summary, details) -> { },
            org.jawata.mcp.fixtures.ShippedGates.registry(), plans);
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    /** A plan whose steps gate the named tools in order, hired with a seat that lists {@code listed}. */
    private PlanId hiredAs(List<String> listed, String... gates) {
        List<GatedStep> steps = new java.util.ArrayList<>();
        for (int i = 0; i < gates.length; i++) {
            steps.add(new GatedStep(i, gates[i], JSON.createObjectNode()));
        }
        PlanId plan = plans.open(steps);
        plans.allowTools(plan, listed);
        return plan;
    }

    private ToolResponse asksFor(PlanId plan, String tool) {
        return checks.acceptance(CallerIdentity.supplied(plan.value()), tool, JSON.createObjectNode(), new SessionLedger());
    }

    @Test
    @DisplayName("a hired session's call to a tool its seat does not list is refused, and the refusal names the list; a listed tool goes through")
    void aCallOutsideTheSeatsListIsRefused() {
        PlanId plan = hiredAs(List.of("inspect", "search_symbols"), "compile_workspace");

        ToolResponse refused = asksFor(plan, "rename_symbol");
        ToolResponse listed = asksFor(plan, "inspect");

        assertAll(
            () -> assertNotNull(refused, "a tool the seat does not list was let through the choke"),
            () -> assertEquals("TOOL_NOT_ALLOWED", refused.getError().getCode()),
            () -> assertTrue(refused.getError().getMessage().contains("rename_symbol"), refused.getError().getMessage()),
            () -> assertTrue(refused.getError().getHint().contains("inspect") && refused.getError().getHint().contains("search_symbols"),
                "the refusal does not say what the seat may call: " + refused.getError().getHint()),
            () -> assertNull(listed, "control: a tool the seat lists was refused"));
    }

    @Test
    @DisplayName("the gate of the step the session is working is allowed although the seat does not list it, and an empty list allows nothing else")
    void theCurrentGateIsAlwaysAllowed() {
        PlanId plan = hiredAs(List.of(), "compile_workspace");

        ToolResponse gate = asksFor(plan, "compile_workspace");
        ToolResponse other = asksFor(plan, "inspect");

        assertAll(
            () -> assertNull(gate, "refusing the gate would forbid the only thing that ends the step"),
            () -> assertNotNull(other, "an empty list allowed a tool"),
            () -> assertTrue(other.getError().getHint().contains("none"), other.getError().getHint()));
    }

    @Test
    @DisplayName("the allowed gate follows the plan: once a step passes, the next step's gate is the one allowed and the passed one is no longer")
    void theAllowedGateFollowsThePlan() {
        PlanId plan = hiredAs(List.of("inspect"), "compile_workspace", "run_tests");

        ToolResponse firstGateBefore = asksFor(plan, "compile_workspace");
        ToolResponse secondGateBefore = asksFor(plan, "run_tests");
        plans.advanceIfGatePassed(plan, new GateOutcome("compile_workspace", JSON.createObjectNode(), 0, true));
        ToolResponse firstGateAfter = asksFor(plan, "compile_workspace");
        ToolResponse secondGateAfter = asksFor(plan, "run_tests");

        assertAll(
            () -> assertNull(firstGateBefore),
            () -> assertNotNull(secondGateBefore, "the gate of a step not reached yet was allowed"),
            () -> assertNotNull(firstGateAfter, "the gate of a step that passed is still allowed"),
            () -> assertNull(secondGateAfter, "control: the gate of the step now being worked was refused"));
    }

    @Test
    @DisplayName("a plan given no list, a session that is no plan's and a plan that has ended are not narrowed")
    void whatNobodyRecordedAListForIsNotNarrowed() {
        PlanId noList = plans.open(List.of(new GatedStep(0, "compile_workspace", JSON.createObjectNode())));
        PlanId ended = hiredAs(List.of("inspect"), "compile_workspace");
        plans.close(ended);

        assertAll(
            () -> assertNull(asksFor(noList, "rename_symbol"), "a plan with no list narrowed its session"),
            () -> assertNull(checks.acceptance(CallerIdentity.supplied("a-persons-client"), "rename_symbol", JSON.createObjectNode(), new SessionLedger()),
                "a session that is no plan's was narrowed"),
            () -> assertNull(asksFor(ended, "rename_symbol"), "a plan that has ended still narrows"));
    }
}
