package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.learn.PlannedStep;
import org.jawata.mcp.step.GateOutcome;
import org.jawata.mcp.step.PlanId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h item 9 (i) slice 3: the list answer of {@code plan_steps} shows what an entered sprint plan marks: its name, the title of the step
 * that is waiting and whether it is a checkpoint, and the person's steps. A plan opened without marks is listed exactly as before.
 */
class PlanStepsListMarksTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlanLedger ledger = new PlanLedger();
    private final PlanStepsTool tool = new PlanStepsTool(ledger);

    private static JsonNode noArguments() {
        return JSON.createObjectNode();
    }

    private static PlannedStep checked(String title, String gate, boolean checkpoint) {
        return new PlannedStep(title, gate, noArguments(), null, checkpoint, false);
    }

    private static PlannedStep person(String title) {
        return new PlannedStep(title, null, null, null, false, true);
    }

    private PlanId sprintWithAPersonStepInTheMiddle() {
        return ledger.openSprint("sprint", List.of(
            checked("A", "compile_workspace", false), checked("B", "get_diagnostics", true), person("P"), checked("C", "run_tests", false)));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listed() {
        return (List<Map<String, Object>>) ((Map<String, Object>) tool.execute(noArguments()).getData()).get("pending");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a listed sprint plan names the plan, the title of the waiting step, that it is a checkpoint, and the person's steps with their place")
    void aListedSprintPlanShowsItsMarks() {
        PlanId id = sprintWithAPersonStepInTheMiddle();
        ledger.advanceIfGatePassed(id, new GateOutcome("compile_workspace", noArguments(), 0, true));

        Map<String, Object> row = listed().get(0);
        Map<String, Object> step = (Map<String, Object>) row.get("step");

        assertEquals("sprint", row.get("name"));
        assertEquals(1, step.get("index"));
        assertEquals("get_diagnostics", step.get("tool"));
        assertEquals("B", step.get("title"));
        assertEquals(true, step.get("checkpoint"));
        assertEquals(List.of(Map.of("title", "P", "after", 2)), row.get("waitingForPerson"));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("before any step has passed the same plan lists its first step, which is not a checkpoint: the mark is read for the step that waits")
    void theCheckpointMarkIsReadForTheStepThatWaits() {
        sprintWithAPersonStepInTheMiddle();

        Map<String, Object> step = (Map<String, Object>) listed().get(0).get("step");

        assertEquals(0, step.get("index"));
        assertEquals("A", step.get("title"));
        assertEquals(false, step.get("checkpoint"));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a plan opened from gated steps is listed exactly as before: no new key in its row and none in its step")
    void aPlanWithoutMarksIsListedAsBefore() {
        ledger.open(List.of(new GatedStep(0, "compile_workspace", noArguments())));

        Map<String, Object> row = listed().get(0);

        assertEquals(List.of("planId", "passedStep", "owner", "step", "inFlight"), List.copyOf(row.keySet()));
        assertEquals(List.of("index", "tool", "arguments"), List.copyOf(((Map<String, Object>) row.get("step")).keySet()));
    }

    @Test
    @DisplayName("a plan with only the person's steps left is not listed, though the ledger still holds it")
    void aPlanWithOnlyThePersonsStepsLeftIsNotListed() {
        PlanId id = ledger.openSprint("sprint", List.of(checked("A", "compile_workspace", false), person("P")));
        ledger.advanceIfGatePassed(id, new GateOutcome("compile_workspace", noArguments(), 0, true));

        assertEquals(List.of(), listed());
        assertEquals(1, ledger.planCount());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a person step in front of the first checked step holds nothing: that step is listed at index 0 and the person's step is shown as waiting")
    void aPersonStepInFrontHoldsNothing() {
        ledger.openSprint("sprint", List.of(person("P"), checked("X", "compile_workspace", false)));

        Map<String, Object> row = listed().get(0);
        Map<String, Object> step = (Map<String, Object>) row.get("step");

        assertEquals(0, step.get("index"));
        assertEquals("X", step.get("title"));
        assertEquals(List.of(Map.of("title", "P", "after", 0)), row.get("waitingForPerson"));
    }
}
