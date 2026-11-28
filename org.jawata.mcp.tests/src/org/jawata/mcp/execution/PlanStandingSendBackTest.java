package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.learn.PlannedStep;
import org.jawata.mcp.step.GateOutcome;
import org.jawata.mcp.step.PlanId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Sprint 28h item 9 (i) slice 2, hire 3a (M3): when the engine sends a pm back to the plan it entered, with the plan's standing, and when not. */
class PlanStandingSendBackTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlanLedger plans = new PlanLedger();

    private final HireRegistry hires = HireRegistry.shared();

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

    /** Enters a sprint plan owned by this lead and passes the steps whose tools are named, each through its own gate. */
    private void entered(String leadId, List<PlannedStep> steps, String... passedTools) {
        PlanId id = plans.openSprint("the sprint of " + leadId, steps);
        plans.ownedBy(id, leadId);
        for (String tool : passedTools) {
            plans.advanceIfGatePassed(id, new GateOutcome(tool, empty(), 0, true));
        }
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: a pm whose plan has an open step and nothing blocking is sent back with the plan's standing and told to do the step or say what blocks it")
    void aPmWithAnOpenStepAndNothingBlockingIsSentBackWithItsPlansStanding() {
        entered("m3-open", List.of(checked("Compile", "compile_workspace"), checked("Diagnose", "get_diagnostics")), "compile_workspace");

        Optional<AcpBrainSession.OpenCheck> sentBack = PlanStandingSendBack.of(plans, hires, "m3-open");

        assertTrue(sentBack.isPresent(), "a pm with an open step and nothing blocking was not sent back");
        AcpBrainSession.OpenCheck check = sentBack.get();
        assertAll(
            () -> assertTrue(check.ofPlan(), "the open check does not say it is the plan's"),
            () -> assertEquals(1, check.passed()),
            () -> assertTrue(check.told().startsWith("Plan standing: "), check.told()),
            () -> assertTrue(check.told().contains("Next: "), check.told()),
            () -> assertEquals("Plan standing: 1 of 2 steps passed. Next: 2 Diagnose (get_diagnostics). Next checkpoint: none. For the person: none.\n"
                + "The plan has an open step and nothing blocks you: do it, or say what blocks you.", check.told()));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: a pm whose last passed step is a checkpoint is not sent back, because a checkpoint is where it stops for the person")
    void aPmWhoseLastPassedStepIsACheckpointIsNotSentBack() {
        entered("m3-checkpoint", List.of(checkpointed("Compile", "compile_workspace"), checked("Diagnose", "get_diagnostics")), "compile_workspace");

        assertTrue(plans.standingOf("m3-checkpoint").orElseThrow().checkpointReached(), "the plan is not at a checkpoint");
        assertEquals(Optional.empty(), PlanStandingSendBack.of(plans, hires, "m3-checkpoint"));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: a pm whose plan has a step of the person standing next is not sent back, because that step is not the pm's to do")
    void aPmWithAStepOfThePersonNextIsNotSentBack() {
        entered("m3-person", List.of(checked("Compile", "compile_workspace"), person("Sign off"), checked("Diagnose", "get_diagnostics")),
            "compile_workspace");

        assertTrue(plans.standingOf("m3-person").orElseThrow().personNext(), "no step of the person stands next");
        assertEquals(Optional.empty(), PlanStandingSendBack.of(plans, hires, "m3-person"));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: a pm with a hire of its own still running is not sent back, and is sent back once that hire has ended")
    void aPmWithAHireOfItsOwnRunningIsNotSentBackUntilThatHireHasEnded() {
        entered("m3-hire-out", List.of(checked("Compile", "compile_workspace"), checked("Diagnose", "get_diagnostics")), "compile_workspace");

        try (HireRegistry.Registration ignored = hires.begin("developer", "m3 the hire of m3-hire-out", "m3-hire-out")) {
            assertEquals(1, hires.runningNamesFor("m3-hire-out").size(), "the hire is not registered as the pm's own");
            assertEquals(Optional.empty(), PlanStandingSendBack.of(plans, hires, "m3-hire-out"));
        }
        assertTrue(PlanStandingSendBack.of(plans, hires, "m3-hire-out").isPresent(), "the pm's hire has ended and the pm was still not sent back");
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: a pm whose context has reached its role's limit is not sent back, because it is told to finish and report")
    void aPmWhoseContextReachedItsLimitIsNotSentBack() {
        String lead = "m3-at-its-limit";
        entered(lead, List.of(checked("Compile", "compile_workspace"), checked("Diagnose", "get_diagnostics")), "compile_workspace");

        try (HireRegistry.Registration itself = hires.begin("pm", "m3 the run of " + lead, "m3-the-principal")) {
            hires.planOpened(lead);
            itself.replaceAt(1_000);
            assertTrue(PlanStandingSendBack.of(plans, hires, lead).isPresent(), "CONTROL: below its limit the pm is sent back");
            hires.noteUsage(java.util.OptionalLong.of(1_000), java.util.OptionalDouble.empty());
            assertTrue(hires.replacementNotice(lead).isPresent(), "the registry did not note that the pm reached its limit");
            assertEquals(Optional.empty(), PlanStandingSendBack.of(plans, hires, lead));
        }
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3, the controls: a lead that entered no plan, a lead that asks about another lead's plan and a pm whose plan has every step passed are not sent back")
    void aLeadWithNoPlanOfItsOwnOrWithAFinishedPlanIsNotSentBack() {
        List<PlannedStep> steps = List.of(checked("Compile", "compile_workspace"), checked("Diagnose", "get_diagnostics"));
        assertEquals(Optional.empty(), PlanStandingSendBack.of(plans, hires, "m3-no-plan"), "no plan was entered at all");

        entered("m3-the-owner", steps, "compile_workspace");
        assertEquals(Optional.empty(), PlanStandingSendBack.of(plans, hires, "m3-not-the-owner"), "the plan belongs to another lead");

        entered("m3-finished", steps, "compile_workspace", "get_diagnostics");
        assertTrue(plans.standingOf("m3-finished").isPresent(), "the finished plan is not in the ledger");
        assertEquals(Optional.empty(), PlanStandingSendBack.of(plans, hires, "m3-finished"), "every step of the plan has passed");
    }
}
