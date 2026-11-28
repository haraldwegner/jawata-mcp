package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.learn.PlanMarks;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.step.PlanId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Sprint 28h item 9 (i) slice 3: {@code plan_steps(action=enter)} records a pm's sprint plan in the plan ledger, or refuses it and records nothing. */
class PlanStepsEnterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlanLedger ledger = new PlanLedger();
    private final PlanStepsTool tool = new PlanStepsTool(ledger);

    private ToolResponse enter(String arguments) throws Exception {
        return tool.execute(JSON.readTree(arguments));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("entering a sprint plan answers its id, name and counts, and the ledger holds the chain of the checked steps and the person's step beside it")
    void aSprintPlanIsEntered() throws Exception {
        ToolResponse response = enter("{\"action\":\"enter\",\"name\":\"sprint\",\"steps\":["
            + "{\"title\":\"A\",\"check\":{\"tool\":\"compile_workspace\"}},"
            + "{\"title\":\"B\",\"check\":{\"tool\":\"get_diagnostics\"},\"checkpoint\":true},"
            + "{\"title\":\"P\",\"person\":true},"
            + "{\"title\":\"C\",\"check\":{\"tool\":\"run_tests\"}}]}");

        assertEquals(true, response.isSuccess());
        Map<String, Object> data = (Map<String, Object>) response.getData();
        assertEquals("sprint", data.get("name"));
        assertEquals(3, data.get("steps"));
        assertEquals(1, data.get("reserved"));
        assertEquals(List.of(1), data.get("checkpoints"));
        PlanMarks marks = ledger.marks(new PlanId((String) data.get("planId"))).orElseThrow();
        assertEquals(List.of("A", "B", "C"), marks.titles());
        assertEquals(List.of(new PlanMarks.Reserved("P", 2)), marks.reserved());
    }

    @Test
    @DisplayName("a person step that carries a check is refused as an invalid parameter naming that step, and no plan is opened")
    void aPersonStepWithACheckIsRefused() throws Exception {
        ToolResponse response = enter("{\"action\":\"enter\",\"name\":\"sprint\",\"steps\":["
            + "{\"title\":\"A\",\"check\":{\"tool\":\"compile_workspace\"}},"
            + "{\"title\":\"P\",\"person\":true,\"check\":{\"tool\":\"run_tests\"}}]}");

        assertEquals(false, response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
        assertEquals(true, response.getError().getMessage().contains("steps[1]"), response.getError().getMessage());
        assertEquals(0, ledger.planCount());
    }

    @Test
    @DisplayName("a step that is not the person's and has no check is refused naming that step, and no plan is opened")
    void aStepWithoutACheckIsRefused() throws Exception {
        ToolResponse response = enter("{\"action\":\"enter\",\"name\":\"sprint\",\"steps\":["
            + "{\"title\":\"A\",\"check\":{\"tool\":\"compile_workspace\"}},"
            + "{\"title\":\"B\"}]}");

        assertEquals(false, response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
        assertEquals(true, response.getError().getMessage().contains("steps[1]"), response.getError().getMessage());
        assertEquals(0, ledger.planCount());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a name an open plan holds is refused as PLAN_EXISTS naming that plan, and the same name enters once that plan is closed")
    void aNameAnOpenPlanHoldsIsRefused() throws Exception {
        String sprint = "{\"action\":\"enter\",\"name\":\"sprint\",\"steps\":[{\"title\":\"A\",\"check\":{\"tool\":\"compile_workspace\"}}]}";
        String firstId = (String) ((Map<String, Object>) enter(sprint).getData()).get("planId");

        ToolResponse again = enter(sprint);

        assertEquals(false, again.isSuccess());
        assertEquals("PLAN_EXISTS", again.getError().getCode());
        assertEquals(true, again.getError().getMessage().contains(firstId), again.getError().getMessage());
        assertEquals(1, ledger.planCount());
        ledger.close(new PlanId(firstId));
        assertEquals(true, enter(sprint).isSuccess());
    }

    @Test
    @DisplayName("an empty list of steps is refused as an invalid parameter and no plan is opened")
    void anEmptyListOfStepsIsRefused() throws Exception {
        ToolResponse response = enter("{\"action\":\"enter\",\"name\":\"sprint\",\"steps\":[]}");

        assertEquals(false, response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
        assertEquals(true, response.getError().getMessage().contains("steps"), response.getError().getMessage());
        assertEquals(0, ledger.planCount());
    }

    @Test
    @DisplayName("an action other than list or enter is refused as an invalid parameter and no plan is opened")
    void anUnknownActionIsRefused() throws Exception {
        ToolResponse response = enter("{\"action\":\"entre\",\"name\":\"sprint\",\"steps\":[{\"title\":\"A\",\"check\":{\"tool\":\"compile_workspace\"}}]}");

        assertEquals(false, response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
        assertEquals(true, response.getError().getMessage().contains("action"), response.getError().getMessage());
        assertEquals(0, ledger.planCount());
    }

    @Test
    @DisplayName("a person step marked as a checkpoint is refused as an invalid parameter naming that step, and no plan is opened")
    void aPersonStepMarkedAsACheckpointIsRefused() throws Exception {
        ToolResponse response = enter("{\"action\":\"enter\",\"name\":\"sprint\",\"steps\":["
            + "{\"title\":\"A\",\"check\":{\"tool\":\"compile_workspace\"}},"
            + "{\"title\":\"P\",\"person\":true,\"checkpoint\":true}]}");

        assertEquals(false, response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
        assertEquals(true, response.getError().getMessage().contains("steps[1]"), response.getError().getMessage());
        assertEquals(0, ledger.planCount());
    }

    @Test
    @DisplayName("a missing name and a blank name are each refused as an invalid parameter naming the name, and no plan is opened")
    void aMissingOrBlankNameIsRefused() throws Exception {
        String steps = "\"steps\":[{\"title\":\"A\",\"check\":{\"tool\":\"compile_workspace\"}}]";

        ToolResponse missing = enter("{\"action\":\"enter\"," + steps + "}");
        ToolResponse blank = enter("{\"action\":\"enter\",\"name\":\"  \"," + steps + "}");

        for (ToolResponse refused : List.of(missing, blank)) {
            assertEquals(false, refused.isSuccess());
            assertEquals("INVALID_PARAMETER", refused.getError().getCode());
            assertEquals(true, refused.getError().getMessage().contains("name"), refused.getError().getMessage());
        }
        assertEquals(0, ledger.planCount());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a check with no arguments and no expect is held as the empty object and no expect, and one given both is held with both")
    void aChecksArgumentsAndExpectAreHeldAsGiven() throws Exception {
        ToolResponse response = enter("{\"action\":\"enter\",\"name\":\"sprint\",\"steps\":["
            + "{\"title\":\"A\",\"check\":{\"tool\":\"compile_workspace\"}},"
            + "{\"title\":\"B\",\"check\":{\"tool\":\"compile_workspace\",\"arguments\":{\"projectKey\":\"jawata-mcp\"},\"expect\":{\"errorCount\":0}}}]}");

        List<GatedStep> chain = ledger.steps(new PlanId((String) ((Map<String, Object>) response.getData()).get("planId")));

        assertEquals(JSON.createObjectNode(), chain.get(0).gateArguments());
        assertEquals(null, chain.get(0).expect());
        assertEquals(JSON.readTree("{\"projectKey\":\"jawata-mcp\"}"), chain.get(1).gateArguments());
        assertEquals(JSON.readTree("{\"errorCount\":0}"), chain.get(1).expect());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("the caller that entered a plan is recorded as its owner, and a plan entered with no caller has the empty owner")
    void theCallerThatEnteredAPlanOwnsIt() throws Exception {
        JsonNode byTheCaller = JSON.readTree("{\"action\":\"enter\",\"name\":\"one\",\"steps\":[{\"title\":\"A\",\"check\":{\"tool\":\"compile_workspace\"}}]}");
        JsonNode byNobody = JSON.readTree("{\"action\":\"enter\",\"name\":\"two\",\"steps\":[{\"title\":\"A\",\"check\":{\"tool\":\"compile_workspace\"}}]}");

        ToolResponse owned = CallerIdentity.supplied("pm-1").speaking(() -> tool.execute(byTheCaller));
        ToolResponse ownerless = tool.execute(byNobody);

        assertEquals("pm-1", ledger.ownerOf(new PlanId((String) ((Map<String, Object>) owned.getData()).get("planId"))));
        assertEquals("", ledger.ownerOf(new PlanId((String) ((Map<String, Object>) ownerless.getData()).get("planId"))));
    }

    @Test
    @DisplayName("a plan of only the person's steps is refused as an invalid parameter naming the steps, and no plan is opened")
    void aPlanOfOnlyPersonStepsIsRefused() throws Exception {
        ToolResponse response = enter("{\"action\":\"enter\",\"name\":\"sprint\",\"steps\":["
            + "{\"title\":\"P\",\"person\":true},{\"title\":\"Q\",\"person\":true}]}");

        assertEquals(false, response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
        assertEquals(true, response.getError().getMessage().contains("steps"), response.getError().getMessage());
        assertEquals(0, ledger.planCount());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a name with surrounding spaces is answered stripped, and the stripped name is then held by the open plan")
    void aNameWithSurroundingSpacesIsAnsweredStripped() throws Exception {
        String steps = "\"steps\":[{\"title\":\"A\",\"check\":{\"tool\":\"compile_workspace\"}}]";

        ToolResponse padded = enter("{\"action\":\"enter\",\"name\":\"  sprint  \"," + steps + "}");
        ToolResponse again = enter("{\"action\":\"enter\",\"name\":\"sprint\"," + steps + "}");

        assertEquals("sprint", ((Map<String, Object>) padded.getData()).get("name"));
        assertEquals(false, again.isSuccess());
        assertEquals("PLAN_EXISTS", again.getError().getCode());
    }
}
