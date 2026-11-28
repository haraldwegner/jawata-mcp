package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.learn.PlannedStep;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.step.GateOutcome;
import org.jawata.mcp.step.PlanId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h item 9 (i) slice 3: the board draws what an entered sprint plan marks beside its chain: the title of each chain step, its checkpoint
 * mark, and the person's steps in the place they hold in the plan. A plan opened without marks is drawn exactly as before.
 */
class PlanBoardMarksTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlanLedger ledger = new PlanLedger();

    private static JsonNode noArguments() {
        return JSON.createObjectNode();
    }

    private static PlannedStep checked(String title, String gate, boolean checkpoint) {
        return new PlannedStep(title, gate, noArguments(), null, checkpoint, false);
    }

    private static PlannedStep person(String title) {
        return new PlannedStep(title, null, null, null, false, true);
    }

    @Test
    @DisplayName("a sprint plan's row shows each step's title, the checkpoint mark and the person's step between the chain steps it falls between")
    void aSprintPlansRowShowsItsMarks() {
        PlanId id = ledger.openSprint("sprint", List.of(
            checked("A", "compile_workspace", false), checked("B", "get_diagnostics", true), person("P"), checked("C", "run_tests", false)));
        ledger.advanceIfGatePassed(id, new GateOutcome("compile_workspace", noArguments(), 0, true));

        String board = PlanBoardTool.render(ledger.view(), ledger::marks);

        assertEquals(true, board.contains("| " + id + " | open, no hire running | 1 of 3 | 0 A (compile_workspace): passed; "
            + "1 B [checkpoint] (get_diagnostics): not run; person: P: waits for the person; 2 C (run_tests): not run |"), board);
    }

    @Test
    @DisplayName("the board drawn into a real plan file by the tool carries the checkpoint mark and the person's step of an entered plan")
    void theToolDrawsTheMarksIntoThePlanFile() throws IOException {
        ledger.openSprint("sprint", List.of(checked("A", "compile_workspace", false), checked("B", "get_diagnostics", true), person("P")));
        Path file = Files.createTempDirectory("plan-board-marks").resolve("s-plan.md");
        Files.writeString(file, "# Plan\n\n" + PlanBoardTool.BEGIN + "\n" + PlanBoardTool.END + "\n");

        ToolResponse response = new PlanBoardTool(ledger).execute(JSON.createObjectNode().put("file", file.toString()));

        String text = Files.readString(file);
        assertEquals(true, response.isSuccess(), String.valueOf(response));
        assertEquals(true, text.contains("[checkpoint]"), text);
        assertEquals(true, text.contains("person: P: waits for the person"), text);
    }

    @Test
    @DisplayName("a person step in front of the first chain step is drawn before step 0")
    void aPersonStepFirstIsDrawnBeforeTheFirstChainStep() {
        ledger.openSprint("sprint", List.of(person("P"), checked("X", "run_tests", false)));

        String board = PlanBoardTool.render(ledger.view(), ledger::marks);

        assertEquals(true, board.contains("| 0 of 1 | person: P: waits for the person; 0 X (run_tests): not run |"), board);
    }

    @Test
    @DisplayName("a person step after the last chain step is drawn after it")
    void aPersonStepLastIsDrawnAfterTheLastChainStep() {
        ledger.openSprint("sprint", List.of(checked("X", "run_tests", false), person("P")));

        String board = PlanBoardTool.render(ledger.view(), ledger::marks);

        assertEquals(true, board.contains("| 0 of 1 | 0 X (run_tests): not run; person: P: waits for the person |"), board);
    }

    @Test
    @DisplayName("a closed plan with marks draws its unpassed chain steps as not reached, and the person's step still waits for the person")
    void aClosedPlanWithMarksDrawsItsStepsAsNotReached() {
        PlanId id = ledger.openSprint("sprint", List.of(checked("A", "compile_workspace", false), person("P"), checked("B", "run_tests", false)));
        ledger.close(id);

        String board = PlanBoardTool.render(ledger.view(), ledger::marks);

        assertEquals(true, board.contains("| " + id + " | closed | 0 of 2 | 0 A (compile_workspace): not reached; "
            + "person: P: waits for the person; 1 B (run_tests): not reached |"), board);
    }

    @Test
    @DisplayName("a plan opened from gated steps is drawn byte for byte as before, with the old render and with the new one given no marks")
    void aPlanWithoutMarksIsDrawnByteForByteAsBefore() {
        PlanId id = ledger.open(List.of(new GatedStep(0, "compile_workspace", noArguments()), new GatedStep(1, "run_tests", noArguments())));
        String drawn = "_Drawn by plan_board from the plan ledger; edit outside the markers._\n\n| plan | state | passed | steps |\n|---|---|---|---|\n| "
            + id + " | open, no hire running | 0 of 2 | 0 compile_workspace: not run; 1 run_tests: not run |\n";

        assertEquals(drawn, PlanBoardTool.render(ledger.view()));
        assertEquals(drawn, PlanBoardTool.render(ledger.view(), planId -> Optional.empty()));
    }
}
