package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.step.GateOutcome;
import org.jawata.mcp.step.PlanId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 28h Stage 28 (i) M1, M2: {@code plan_steps} (list) tells its caller where the caller's own newest sprint plan stands, and whether the caller
 * may go on with it, so the engine's turn loop for main reads one standing and not a second copy of it.
 */
class PlanStepsOwnPlanTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlanLedger plans = new PlanLedger();
    private final PlanStepsTool tool = new PlanStepsTool(plans);

    private static ObjectNode step(String title, String gate, boolean checkpoint) {
        ObjectNode step = JSON.createObjectNode().put("title", title).put("checkpoint", checkpoint);
        step.putObject("check").put("tool", gate);
        return step;
    }

    /** {@code caller} enters a plan of a step, a checkpoint and a last step; answers the plan's id. */
    private PlanId enteredBy(CallerIdentity caller, String name) {
        ObjectNode arguments = JSON.createObjectNode().put("action", "enter").put("name", name);
        arguments.putArray("steps").add(step("First", "compile_workspace", false)).add(step("Checkpoint A", "get_diagnostics", true))
            .add(step("Last", "run_tests", false));
        JsonNode answer = JSON.valueToTree(caller.speaking(() -> tool.execute(arguments)).getData());
        return new PlanId(answer.path("planId").asText());
    }

    private void pass(PlanId id, String gate) {
        plans.advanceIfGatePassed(id, new GateOutcome(gate, JSON.createObjectNode(), 0, true));
    }

    private JsonNode listedFor(CallerIdentity caller) {
        return JSON.valueToTree(caller.speaking(() -> tool.execute(JSON.createObjectNode())).getData());
    }

    @Test
    @DisplayName("28h Stage 28 (i) M1: a caller that entered a plan with nothing reached is answered planStanding open and goOn true")
    void anOpenPlanOfTheCallerCanGoOn() {
        CallerIdentity main = CallerIdentity.supplied("main-1");
        PlanId id = enteredBy(main, "sprint");
        pass(id, "compile_workspace");

        JsonNode answer = listedFor(main);

        assertAll(
            () -> assertTrue(answer.path("planStanding").isObject(), "no planStanding in " + answer),
            () -> assertTrue(answer.path("planStanding").path("open").asBoolean(), "the plan is not open in " + answer),
            () -> assertTrue(answer.path("goOn").isBoolean() && answer.path("goOn").asBoolean(), "goOn is not true in " + answer));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M2: after the plan's checkpoint step passed the answer carries goOn false and the checkpoint's title as reachedCheckpoint")
    void aReachedCheckpointStopsTheCaller() {
        CallerIdentity main = CallerIdentity.supplied("main-1");
        PlanId id = enteredBy(main, "sprint");
        pass(id, "compile_workspace");
        pass(id, "get_diagnostics");

        JsonNode answer = listedFor(main);

        assertAll(
            () -> assertTrue(answer.path("goOn").isBoolean() && !answer.path("goOn").asBoolean(), "goOn is not false in " + answer),
            () -> assertEquals("Checkpoint A", answer.path("planStanding").path("reachedCheckpoint").asText(), "in " + answer));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M1: a caller that entered nothing is answered neither planStanding nor goOn")
    void aCallerWithNoPlanOfItsOwnIsAnsweredNeitherKey() {
        enteredBy(CallerIdentity.supplied("main-1"), "sprint");

        JsonNode answer = listedFor(CallerIdentity.supplied("someone-else"));

        assertAll(
            () -> assertFalse(answer.has("planStanding"), "planStanding in " + answer),
            () -> assertFalse(answer.has("goOn"), "goOn in " + answer));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M1: another caller's plan never appears under planStanding, and a call with no caller or with all=true is answered neither key")
    void anotherCallersPlanNeverAppearsUnderPlanStanding() {
        CallerIdentity main = CallerIdentity.supplied("main-1");
        CallerIdentity other = CallerIdentity.supplied("other-1");
        enteredBy(main, "mine");
        PlanId theirs = enteredBy(other, "theirs");
        pass(theirs, "compile_workspace");
        pass(theirs, "get_diagnostics");

        JsonNode answer = listedFor(main);
        JsonNode noCaller = JSON.valueToTree(tool.execute(JSON.createObjectNode()).getData());
        JsonNode everyones = JSON.valueToTree(main.speaking(() -> tool.execute(JSON.createObjectNode().put("all", true))).getData());

        assertAll(
            () -> assertTrue(answer.path("planStanding").isObject(), "main was answered no standing: " + answer),
            () -> assertEquals(0, answer.path("planStanding").path("passed").asInt(), "main's standing shows the other caller's plan: " + answer),
            () -> assertTrue(answer.path("goOn").asBoolean(), "the other caller's reached checkpoint holds main: " + answer),
            () -> assertFalse(noCaller.has("planStanding") || noCaller.has("goOn"), "a call with no caller was answered a standing: " + noCaller),
            () -> assertFalse(everyones.has("planStanding") || everyones.has("goOn"), "all=true was answered a standing: " + everyones));
    }
}
