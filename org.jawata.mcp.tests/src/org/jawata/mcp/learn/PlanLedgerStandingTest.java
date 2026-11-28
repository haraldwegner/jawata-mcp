package org.jawata.mcp.learn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import org.jawata.mcp.step.GateOutcome;
import org.jawata.mcp.step.PlanId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Sprint 28h item 9 (i) slice 2, hire 1 (M3, M4): the standing of a sprint plan in the ledger, found by the owner that entered it. */
class PlanLedgerStandingTest {

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

    private void pass(PlanId id, String tool) {
        ledger.advanceIfGatePassed(id, new GateOutcome(tool, empty(), 0, true));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: M3/M4 ledger: standingOf counts passed, total and the next step")
    void standingOfCountsPassedTotalAndTheNextStep() {
        PlanId id = ledger.openSprint("sprint", List.of(
            checked("A", "compile_workspace"), checked("B", "get_diagnostics"), checked("C", "run_tests")));
        ledger.ownedBy(id, "pm-1");
        pass(id, "compile_workspace");

        Standing standing = ledger.standingOf("pm-1").orElseThrow();

        assertEquals(1, standing.passed());
        assertEquals(3, standing.total());
        assertEquals(Optional.of(new Standing.Next(1, "B", "get_diagnostics")), standing.next());
        assertEquals(true, standing.open());
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: M3/M4 ledger: with every chain step passed standingOf has no next step, no next checkpoint, is not open and no person step is next")
    void standingOfAFinishedPlanHasNothingNext() {
        PlanId id = ledger.openSprint("sprint", List.of(
            checked("A", "compile_workspace"), checked("B", "get_diagnostics"), checked("C", "run_tests")));
        ledger.ownedBy(id, "pm-1");
        pass(id, "compile_workspace");
        pass(id, "get_diagnostics");
        pass(id, "run_tests");

        Standing standing = ledger.standingOf("pm-1").orElseThrow();

        assertEquals(3, standing.passed());
        assertEquals(Optional.empty(), standing.next());
        assertEquals(Optional.empty(), standing.nextCheckpoint());
        assertEquals(false, standing.open());
        assertEquals(false, standing.personNext());
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: M3/M4 ledger: nextCheckpoint is the first checkpoint step not yet passed, and empty once the only checkpoint has passed")
    void theNextCheckpointIsTheFirstOneNotYetPassed() {
        PlanId id = ledger.openSprint("sprint", List.of(
            checked("A", "compile_workspace"), checkpointed("B", "get_diagnostics"), checked("C", "run_tests")));
        ledger.ownedBy(id, "pm-1");

        assertEquals(Optional.of(new Standing.NextCheckpoint(1, "B")), ledger.standingOf("pm-1").orElseThrow().nextCheckpoint());

        pass(id, "compile_workspace");
        assertEquals(Optional.of(new Standing.NextCheckpoint(1, "B")), ledger.standingOf("pm-1").orElseThrow().nextCheckpoint());

        pass(id, "get_diagnostics");
        assertEquals(Optional.empty(), ledger.standingOf("pm-1").orElseThrow().nextCheckpoint());
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: M3/M4 ledger: checkpointReached is true right after the checkpoint step passes, false after the next step passes and false when nothing has passed")
    void aCheckpointIsReachedOnlyRightAfterItsStepPasses() {
        PlanId id = ledger.openSprint("sprint", List.of(
            checked("A", "compile_workspace"), checkpointed("B", "get_diagnostics"), checked("C", "run_tests")));
        ledger.ownedBy(id, "pm-1");

        assertEquals(false, ledger.standingOf("pm-1").orElseThrow().checkpointReached());

        pass(id, "compile_workspace");
        assertEquals(false, ledger.standingOf("pm-1").orElseThrow().checkpointReached());

        pass(id, "get_diagnostics");
        assertEquals(true, ledger.standingOf("pm-1").orElseThrow().checkpointReached());

        pass(id, "run_tests");
        assertEquals(false, ledger.standingOf("pm-1").orElseThrow().checkpointReached());
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: M3/M4 ledger: forPerson lists the person's step titles in order, and personNext is true exactly when a person's step stands right before the next chain step")
    void theStepsForThePersonAndWhetherOneIsNext() {
        PlanId id = ledger.openSprint("sprint", List.of(
            person("P"), checked("A", "compile_workspace"), person("Q"), checked("B", "get_diagnostics"), checked("C", "run_tests")));
        ledger.ownedBy(id, "pm-1");

        assertEquals(List.of("P", "Q"), ledger.standingOf("pm-1").orElseThrow().forPerson());
        assertEquals(true, ledger.standingOf("pm-1").orElseThrow().personNext());

        pass(id, "compile_workspace");
        assertEquals(List.of("P", "Q"), ledger.standingOf("pm-1").orElseThrow().forPerson());
        assertEquals(true, ledger.standingOf("pm-1").orElseThrow().personNext());

        pass(id, "get_diagnostics");
        assertEquals(false, ledger.standingOf("pm-1").orElseThrow().personNext());
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: M3/M4 ledger: a plan owned by pm-1 is found by pm-1 and not by pm-2")
    void aPlanIsFoundOnlyByItsOwner() {
        PlanId id = ledger.openSprint("sprint", List.of(checked("A", "compile_workspace")));
        ledger.ownedBy(id, "pm-1");

        assertEquals(true, ledger.standingOf("pm-1").isPresent());
        assertEquals(Optional.empty(), ledger.standingOf("pm-2"));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: M3/M4 ledger: a null, empty or blank id finds nothing even when a plan has an empty owner")
    void aNullOrBlankIdFindsNothing() {
        ledger.openSprint("sprint", List.of(checked("A", "compile_workspace")));

        assertEquals(Optional.empty(), ledger.standingOf(null));
        assertEquals(Optional.empty(), ledger.standingOf(""));
        assertEquals(Optional.empty(), ledger.standingOf("  "));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: M3/M4 ledger: a plan that was entered and never given an owner is found by no id")
    void aPlanWithNoOwnerIsFoundByNoId() {
        ledger.openSprint("sprint", List.of(checked("A", "compile_workspace")));

        assertEquals(Optional.empty(), ledger.standingOf("pm-1"));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: M3/M4 ledger: a ledger rebuilt from its plan file finds nothing for the id that owned the plan before, because a restored plan has no owner")
    void aRestoredPlanIsFoundByNoId(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        java.nio.file.Path file = directory.resolve("plans.json");
        PlanLedger first = new PlanLedger(file);
        PlanId id = first.openSprint("sprint", List.of(checked("A", "compile_workspace")));
        first.ownedBy(id, "pm-1");
        assertEquals(true, first.standingOf("pm-1").isPresent());

        PlanLedger second = new PlanLedger(file);

        assertEquals(Optional.empty(), second.standingOf("pm-1"));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: M3/M4 ledger: of two plans with the same owner and different names, standingOf answers for the second one entered")
    void ofTwoPlansOfOneOwnerTheSecondEnteredIsAnswered() {
        PlanId first = ledger.openSprint("first sprint", List.of(checked("A", "compile_workspace")));
        ledger.ownedBy(first, "pm-1");
        PlanId second = ledger.openSprint("second sprint", List.of(checked("B", "get_diagnostics"), checked("C", "run_tests")));
        ledger.ownedBy(second, "pm-1");

        Standing standing = ledger.standingOf("pm-1").orElseThrow();

        assertEquals(2, standing.total());
        assertEquals(Optional.of(new Standing.Next(0, "B", "get_diagnostics")), standing.next());
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: M3/M4 ledger: touching the first plan after the second was entered does not make it the answer, so entry order is not read off the access-ordered map")
    void touchingTheFirstPlanDoesNotMakeItTheNewest() {
        PlanId first = ledger.openSprint("first sprint", List.of(checked("A", "compile_workspace")));
        ledger.ownedBy(first, "pm-1");
        PlanId second = ledger.openSprint("second sprint", List.of(checked("B", "get_diagnostics"), checked("C", "run_tests")));
        ledger.ownedBy(second, "pm-1");

        ledger.currentStep(first);
        pass(first, "compile_workspace");

        assertEquals(2, ledger.standingOf("pm-1").orElseThrow().total());
        assertEquals(0, ledger.standingOf("pm-1").orElseThrow().passed());
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: M3/M4 ledger: asMap of a standing with a next step and a next checkpoint has its keys in the published order, with next as a map of index, title and tool")
    void asMapHasTheKeysInOrderAndTheNextAsAMap() {
        PlanId id = ledger.openSprint("sprint", List.of(
            checked("A", "compile_workspace"), checkpointed("B", "get_diagnostics"), person("P"), checked("C", "run_tests")));
        ledger.ownedBy(id, "pm-1");

        java.util.Map<String, Object> map = ledger.standingOf("pm-1").orElseThrow().asMap();

        assertEquals(List.of("passed", "total", "next", "nextCheckpoint", "forPerson", "open", "checkpointReached", "personNext"),
            List.copyOf(map.keySet()));
        assertEquals(0, map.get("passed"));
        assertEquals(3, map.get("total"));
        assertEquals(java.util.Map.of("index", 0, "title", "A", "tool", "compile_workspace"), map.get("next"));
        assertEquals(List.of("index", "title", "tool"), List.copyOf(((java.util.Map<?, ?>) map.get("next")).keySet()));
        assertEquals(java.util.Map.of("index", 1, "title", "B"), map.get("nextCheckpoint"));
        assertEquals(List.of("P"), map.get("forPerson"));
        assertEquals(true, map.get("open"));
        assertEquals(false, map.get("checkpointReached"));
        assertEquals(false, map.get("personNext"));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: M3/M4 ledger: asMap of a finished plan has next and nextCheckpoint as null values with the keys present, and open false")
    void asMapOfAFinishedPlanHasNullNextAndNextCheckpoint() {
        PlanId id = ledger.openSprint("sprint", List.of(checked("A", "compile_workspace")));
        ledger.ownedBy(id, "pm-1");
        pass(id, "compile_workspace");

        java.util.Map<String, Object> map = ledger.standingOf("pm-1").orElseThrow().asMap();

        assertEquals(true, map.containsKey("next"));
        assertEquals(null, map.get("next"));
        assertEquals(true, map.containsKey("nextCheckpoint"));
        assertEquals(null, map.get("nextCheckpoint"));
        assertEquals(false, map.get("open"));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: M3/M4 ledger: an owner whose only plan was opened with open (no sprint plan) gets an empty standing and no exception")
    void anOwnerWithOnlyAPlainPlanHasNoStanding() {
        PlanId id = ledger.open(List.of(new GatedStep(0, "compile_workspace", empty())));
        ledger.ownedBy(id, "pm-1");

        assertEquals(Optional.empty(), ledger.standingOf("pm-1"));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: M3/M4 ledger: an owner that entered a sprint plan and then opened a plain plan under the same owner still gets the sprint plan's standing")
    void aPlainPlanOpenedAfterTheSprintPlanDoesNotHideItsStanding() {
        PlanId sprint = ledger.openSprint("sprint", List.of(checked("A", "compile_workspace"), checked("B", "get_diagnostics")));
        ledger.ownedBy(sprint, "pm-1");
        PlanId plain = ledger.open(List.of(new GatedStep(0, "compile_workspace", empty())));
        ledger.ownedBy(plain, "pm-1");

        Standing standing = ledger.standingOf("pm-1").orElseThrow();

        assertEquals(2, standing.total());
        assertEquals(Optional.of(new Standing.Next(0, "A", "compile_workspace")), standing.next());
    }
}
