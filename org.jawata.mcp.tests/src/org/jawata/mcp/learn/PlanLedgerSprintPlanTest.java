package org.jawata.mcp.learn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.jawata.mcp.step.GateOutcome;
import org.jawata.mcp.step.PlanId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Sprint 28h item 9 (i) slice 1: a sprint plan entered into the ledger keeps its checked steps as the chain and its person steps beside it. */
class PlanLedgerSprintPlanTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlanLedger ledger = new PlanLedger();

    private static JsonNode empty() {
        return JSON.createObjectNode();
    }

    private static PlannedStep checked(String title, String tool) {
        return new PlannedStep(title, tool, empty(), null, false, false);
    }

    private static PlannedStep checkpointed(String title, String tool) {
        return new PlannedStep(title, tool, empty(), null, true, false);
    }

    private static PlannedStep person(String title) {
        return new PlannedStep(title, null, null, null, false, true);
    }

    @Test
    @DisplayName("entering a plan with a person step in it gives a chain of only the checked steps, none passed and not closed")
    void thePersonStepIsNotInTheChain() {
        PlanId id = ledger.openSprint("sprint", List.of(
            checked("A", "compile_workspace"), checked("B", "get_diagnostics"), person("P"), checked("C", "run_tests")));

        List<PlanLedger.PlanView> plans = ledger.view();
        assertEquals(1, plans.size());
        PlanLedger.PlanView plan = plans.get(0);
        assertEquals(id, plan.id());
        assertEquals(-1, plan.passedStep());
        assertFalse(plan.closed());
        assertEquals(3, plan.steps().size());
        assertEquals(List.of(0, 1, 2), plan.steps().stream().map(GatedStep::index).toList());
        assertEquals(List.of("compile_workspace", "get_diagnostics", "run_tests"), plan.steps().stream().map(GatedStep::tool).toList());
    }

    @Test
    @DisplayName("the marks of an entered plan hold its name, the titles of the chain and the person step with how many chain steps come before it")
    void theMarksHoldTheTitlesTheReservedStepAndTheName() {
        PlanId id = ledger.openSprint("sprint", List.of(
            checked("A", "compile_workspace"), checked("B", "get_diagnostics"), person("P"), checked("C", "run_tests")));

        PlanMarks marks = ledger.marks(id).orElseThrow();
        assertEquals("sprint", marks.name());
        assertEquals(List.of("A", "B", "C"), marks.titles());
        assertEquals(List.of(new PlanMarks.Reserved("P", 2)), marks.reserved());
    }

    @Test
    @DisplayName("the checkpoints are the chain indices of the checked steps marked as checkpoints, and a person step is never one of them")
    void theCheckpointsAreChainIndices() {
        PlanId id = ledger.openSprint("sprint", List.of(
            checked("A", "compile_workspace"), checkpointed("B", "get_diagnostics"), person("P"), checkpointed("C", "run_tests")));

        assertEquals(List.of(1, 2), ledger.marks(id).orElseThrow().checkpoints());
    }

    @Test
    @DisplayName("a plan that starts with a person step lists its first checked step as pending, and nothing once that step has passed")
    void aLeadingPersonStepHoldsNothing() {
        PlanId id = ledger.openSprint("sprint", List.of(person("P"), checked("X", "compile_workspace")));

        List<PlanLedger.Pending> pending = ledger.pending();
        assertEquals(1, pending.size());
        assertEquals(id, pending.get(0).id());
        assertEquals(-1, pending.get(0).passedStep());
        assertEquals("compile_workspace", pending.get(0).next().tool());

        ledger.advanceIfGatePassed(id, new GateOutcome("compile_workspace", empty(), 0, true));

        assertEquals(List.of(), ledger.pending());
        assertEquals(List.of(new PlanMarks.Reserved("P", 0)), ledger.marks(id).orElseThrow().reserved());
    }

    @Test
    @DisplayName("a person step between two checked steps does not hold the step after it: once the first has passed the second is next")
    void aPersonStepDoesNotHoldTheStepAfterIt() {
        PlanId id = ledger.openSprint("sprint", List.of(checked("A", "compile_workspace"), person("P"), checked("B", "run_tests")));

        ledger.advanceIfGatePassed(id, new GateOutcome("compile_workspace", empty(), 0, true));

        List<PlanLedger.Pending> pending = ledger.pending();
        assertEquals(1, pending.size());
        assertEquals(0, pending.get(0).passedStep());
        assertEquals(1, pending.get(0).next().index());
        assertEquals("run_tests", pending.get(0).next().tool());
    }

    @Test
    @DisplayName("entering a name an open plan already holds is refused, naming the plan that holds it")
    void aNameAnOpenPlanHoldsIsRefused() {
        PlanId holder = ledger.openSprint("sprint", List.of(checked("A", "compile_workspace")));

        PlanLedger.PlanExists refused = assertThrows(PlanLedger.PlanExists.class,
            () -> ledger.openSprint("sprint", List.of(checked("B", "run_tests"))));

        assertEquals(holder, refused.planId());
    }

    @Test
    @DisplayName("a name whose plan has been closed can be entered again")
    void aNameAClosedPlanHeldCanBeEnteredAgain() {
        PlanId holder = ledger.openSprint("sprint", List.of(checked("A", "compile_workspace")));
        ledger.close(holder);

        PlanId again = ledger.openSprint("sprint", List.of(checked("B", "run_tests")));

        assertEquals(2, ledger.planCount());
        assertEquals(List.of("B"), ledger.marks(again).orElseThrow().titles());
    }

    @Test
    @DisplayName("a different name can be entered while another plan is open")
    void aDifferentNameIsNotBlockedByAnOpenPlan() {
        ledger.openSprint("sprint", List.of(checked("A", "compile_workspace")));

        PlanId other = ledger.openSprint("another sprint", List.of(checked("B", "run_tests")));

        assertEquals(2, ledger.planCount());
        assertEquals("another sprint", ledger.marks(other).orElseThrow().name());
    }

    @Test
    @DisplayName("the name is stored stripped, so the same name with spaces added is the same name")
    void theNameIsStoredStripped() {
        PlanId id = ledger.openSprint("  sprint  ", List.of(checked("A", "compile_workspace")));

        assertEquals("sprint", ledger.marks(id).orElseThrow().name());
        assertThrows(PlanLedger.PlanExists.class, () -> ledger.openSprint(" sprint ", List.of(checked("B", "run_tests"))));
    }

    @Test
    @DisplayName("a blank name is refused and no plan is opened")
    void aBlankNameIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ledger.openSprint("   ", List.of(checked("A", "compile_workspace"))));
        assertEquals(0, ledger.planCount());
    }

    @Test
    @DisplayName("a null name is refused as an illegal argument, not as a null pointer")
    void aNullNameIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ledger.openSprint(null, List.of(checked("A", "compile_workspace"))));
        assertEquals(0, ledger.planCount());
    }

    @Test
    @DisplayName("an empty list of steps is refused and no plan is opened")
    void anEmptyListOfStepsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ledger.openSprint("sprint", List.of()));
        assertEquals(0, ledger.planCount());
    }

    @Test
    @DisplayName("a list of only person steps is refused and no plan is opened")
    void aListOfOnlyPersonStepsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ledger.openSprint("sprint", List.of(person("P"), person("Q"))));
        assertEquals(0, ledger.planCount());
    }

    @Test
    @DisplayName("a step closes only through its own check: another tool and a failing check leave it, the right tool at zero errors moves it")
    void aStepClosesOnlyThroughItsCheck() {
        PlanId id = ledger.openSprint("sprint", List.of(checked("A", "compile_workspace")));

        assertFalse(ledger.advanceIfGatePassed(id, new GateOutcome("run_tests", empty(), 0, true)).advanced());
        assertEquals(-1, ledger.currentStep(id).getAsInt());
        assertFalse(ledger.advanceIfGatePassed(id, new GateOutcome("compile_workspace", empty(), 1, true)).advanced());
        assertEquals(-1, ledger.currentStep(id).getAsInt());
        assertEquals(0, ledger.advanceIfGatePassed(id, new GateOutcome("compile_workspace", empty(), 0, true)).currentStep());
        assertEquals(0, ledger.currentStep(id).getAsInt());
    }

    @Test
    @DisplayName("the view of an entered plan has nothing passed and is not closed, its steps are the chain, and its components are the ones of PlanView")
    void theViewOfAnEnteredPlanIsThePlainView() {
        PlanId id = ledger.openSprint("sprint", List.of(checked("A", "compile_workspace")));

        PlanLedger.PlanView view = ledger.view().get(0);

        assertEquals(List.of("id", "passedStep", "closed", "steps"),
            List.of(PlanLedger.PlanView.class.getRecordComponents()).stream().map(component -> component.getName()).toList());
        assertEquals(id, view.id());
        assertEquals(-1, view.passedStep());
        assertFalse(view.closed());
        assertEquals(List.of(new GatedStep(0, "compile_workspace", empty(), null)), view.steps());
    }

    @Test
    @DisplayName("a plan opened from gated steps has no marks, and neither has a plan nobody opened")
    void aPlanOpenedWithoutMarksHasNoMarks() {
        PlanId id = ledger.open(List.of(new GatedStep(0, "compile_workspace", empty())));

        assertFalse(ledger.marks(id).isPresent());
        assertFalse(ledger.marks(PlanId.mint()).isPresent());
        assertFalse(ledger.marks(null).isPresent());
    }
}
