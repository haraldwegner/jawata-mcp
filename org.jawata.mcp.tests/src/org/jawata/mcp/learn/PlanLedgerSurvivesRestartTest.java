package org.jawata.mcp.learn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jawata.mcp.step.GateOutcome;
import org.jawata.mcp.step.PlanId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Sprint 28h item 9 (i) slice 2 (M6): a sprint plan entered into a ledger that has a plan file, and the steps it has passed, survive a restart. */
class PlanLedgerSurvivesRestartTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path directory;

    private static JsonNode empty() {
        return JSON.createObjectNode();
    }

    private static PlannedStep checked(String title, String tool) {
        return new PlannedStep(title, tool, empty(), null, false, false);
    }

    private static PlannedStep person(String title) {
        return new PlannedStep(title, null, null, null, false, true);
    }

    @Test
    @DisplayName("an entered plan and the steps it has passed are the same in a second ledger built on the same file")
    void anEnteredPlanAndItsPassedStepsSurviveARestart() {
        Path file = directory.resolve("plans.json");
        PlanLedger first = new PlanLedger(file);
        PlanId id = first.openSprint("sprint", List.of(
            checked("A", "compile_workspace"), checked("B", "get_diagnostics"), person("P"), checked("C", "run_tests")));
        GateOutcome passed = new GateOutcome("compile_workspace", empty(), 0, true);
        first.advanceIfGatePassed(id, passed);

        PlanLedger second = new PlanLedger(file);

        assertEquals(first.view(), second.view());
        assertEquals(id, second.view().get(0).id());
        assertEquals(first.marks(id), second.marks(id));
        assertEquals(0, second.currentStep(id).getAsInt());
        assertEquals(List.of(passed), second.passedOutcomes(id));
        assertEquals(1, second.pending().size());
        assertEquals(0, second.pending().get(0).passedStep());
        assertEquals("get_diagnostics", second.pending().get(0).next().tool());
    }

    @Test
    @DisplayName("a closed entered plan is still closed in a second ledger, and nothing is pending")
    void aClosedPlanStaysClosedAfterARestart() {
        Path file = directory.resolve("plans.json");
        PlanLedger first = new PlanLedger(file);
        PlanId id = first.openSprint("sprint", List.of(checked("A", "compile_workspace")));
        first.close(id);

        PlanLedger second = new PlanLedger(file);

        assertEquals(1, second.view().size());
        assertEquals(true, second.view().get(0).closed());
        assertEquals(List.of(), second.pending());
    }

    @Test
    @DisplayName("a restored plan belongs to no session, so it is pending for any session, while a plan owned by one session is not pending for another")
    void aRestoredPlanBelongsToNoSession() {
        Path file = directory.resolve("plans.json");
        PlanLedger first = new PlanLedger(file);
        PlanId id = first.openSprint("sprint", List.of(checked("A", "compile_workspace")));
        first.ownedBy(id, "session-1");
        assertEquals(List.of(), first.pendingFor("another session"));

        PlanLedger second = new PlanLedger(file);

        assertEquals("", second.ownerOf(id));
        assertEquals(1, second.pendingFor("any session").size());
        assertEquals(id, second.pendingFor("any session").get(0).id());
    }

    @Test
    @DisplayName("a plan file that is not JSON is kept as .corrupt, the plan file is gone and the ledger starts empty")
    void aPlanFileThatIsNotJsonIsKeptAsCorrupt() throws IOException {
        Path file = directory.resolve("plans.json");
        Files.writeString(file, "this is not json");

        PlanLedger ledger = new PlanLedger(file);

        assertEquals(0, ledger.planCount());
        assertEquals("this is not json", Files.readString(directory.resolve("plans.json.corrupt")));
        assertEquals(false, Files.exists(file));
    }

    @Test
    @DisplayName("a ledger that set a corrupt file aside still works: a plan entered on it is written and a further ledger holds it")
    void aLedgerThatSetACorruptFileAsideKeepsWorking() throws IOException {
        Path file = directory.resolve("plans.json");
        Files.writeString(file, "this is not json");
        PlanLedger first = new PlanLedger(file);
        PlanId id = first.openSprint("sprint", List.of(checked("A", "compile_workspace")));

        PlanLedger second = new PlanLedger(file);

        assertEquals(1, second.planCount());
        assertEquals(id, second.view().get(0).id());
    }

    @Test
    @DisplayName("a plan file whose stored passed step is for another tool than its step is kept whole as .corrupt and the ledger starts empty")
    void aPlanFileThatDoesNotReplayIsKeptAsCorrupt() throws IOException {
        Path file = directory.resolve("plans.json");
        String text = "{\"plans\":[{\"id\":\"plan-1\",\"name\":\"n\",\"closed\":false,\"steps\":[{\"title\":\"A\",\"tool\":\"compile_workspace\","
            + "\"arguments\":{},\"expect\":null,\"checkpoint\":false}],\"person\":[],\"passed\":[{\"tool\":\"run_tests\",\"arguments\":{},"
            + "\"errorCount\":0,\"succeeded\":true}]}]}";
        Files.writeString(file, text);

        PlanLedger ledger = new PlanLedger(file);

        assertEquals(0, ledger.planCount());
        assertEquals(text, Files.readString(directory.resolve("plans.json.corrupt")));
    }

    @Test
    @DisplayName("a plan file that is missing is not corrupt: the ledger is empty and no .corrupt file appears")
    void aMissingPlanFileIsNotCorrupt() {
        Path file = directory.resolve("plans.json");

        PlanLedger ledger = new PlanLedger(file);

        assertEquals(0, ledger.planCount());
        assertEquals(false, Files.exists(directory.resolve("plans.json.corrupt")));
    }

    @Test
    @DisplayName("when the second of two stored plans does not restore, the first is discarded with it and the ledger starts empty")
    void aPlanThatDoesNotRestoreTakesTheEarlierOneWithIt() throws IOException {
        Path file = directory.resolve("plans.json");
        PlanLedger first = new PlanLedger(file);
        first.openSprint("one", List.of(checked("A", "compile_workspace")));
        PlanId second = first.openSprint("two", List.of(checked("B", "run_tests")));
        first.advanceIfGatePassed(second, new GateOutcome("run_tests", empty(), 0, true));
        String stored = Files.readString(file);
        String tool = "\"tool\" : \"run_tests\"";
        int passedTool = stored.lastIndexOf(tool);
        Files.writeString(file, stored.substring(0, passedTool) + "\"tool\" : \"get_diagnostics\"" + stored.substring(passedTool + tool.length()));

        PlanLedger third = new PlanLedger(file);

        assertEquals(0, third.planCount());
    }

    @Test
    @DisplayName("a plan opened from gated steps is not written to the plan file: only the entered sprint plan comes back")
    void aPlanOpenedFromGatedStepsIsNotPersisted() {
        Path file = directory.resolve("plans.json");
        PlanLedger first = new PlanLedger(file);
        first.open(List.of(new GatedStep(0, "compile_workspace", empty())));
        PlanId sprint = first.openSprint("sprint", List.of(checked("A", "run_tests")));

        PlanLedger second = new PlanLedger(file);

        assertEquals(1, second.planCount());
        assertEquals(sprint, second.view().get(0).id());
    }

    @Test
    @DisplayName("a plan entered after a restart is kept together with the earlier one")
    void aPlanEnteredAfterARestartIsKeptWithTheEarlierOne() {
        Path file = directory.resolve("plans.json");
        PlanId one = new PlanLedger(file).openSprint("one", List.of(checked("A", "compile_workspace")));
        PlanId two = new PlanLedger(file).openSprint("two", List.of(checked("B", "run_tests")));

        PlanLedger third = new PlanLedger(file);

        assertEquals(2, third.planCount());
        assertEquals(true, third.marks(one).isPresent());
        assertEquals(true, third.marks(two).isPresent());
    }

    @Test
    @DisplayName("after a write the folder holds only the plan file: the temporary file has been moved away")
    void noTemporaryFileIsLeftBesideThePlanFile() throws IOException {
        Path file = directory.resolve("plans.json");
        PlanLedger ledger = new PlanLedger(file);
        PlanId id = ledger.openSprint("sprint", List.of(checked("A", "compile_workspace")));
        ledger.advanceIfGatePassed(id, new GateOutcome("compile_workspace", empty(), 0, true));

        try (var files = Files.list(directory)) {
            assertEquals(List.of(file), files.toList());
        }
    }
}
