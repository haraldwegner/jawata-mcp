package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.step.GateOutcome;
import org.jawata.mcp.step.PlanId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h, S10 (D18): the board of the sprint plan is the plan ledger drawn between two markers, so what a person reads in the file is
 * what the gates say. The tool writes only there, and only into a plan file that opted in by carrying the markers.
 */
class PlanBoardToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FRAME = "# Plan\n\nBefore the board.\n\n" + PlanBoardTool.BEGIN + "\nhand written\n" + PlanBoardTool.END
        + "\n\nAfter the board.\n";

    private Path dir;
    private PlanLedger ledger;
    private PlanBoardTool tool;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("plan-board-test");
        ledger = new PlanLedger();
        tool = new PlanBoardTool(ledger);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private Path planFile(String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private ToolResponse draw(Path file) {
        return tool.execute(JSON.createObjectNode().put("file", file.toString()));
    }

    private PlanId openPlan() {
        JsonNode none = JSON.createObjectNode();
        return ledger.open(List.of(new GatedStep(0, "compile_workspace", none), new GatedStep(1, "run_tests", none)));
    }

    @Test
    @DisplayName("the board is the ledger drawn between the markers: an open plan with a passed step and a closed one with a step never reached, and the text outside the markers is untouched")
    void theBoardIsTheLedgerAndOnlyBetweenTheMarkers() throws IOException {
        PlanId open = openPlan();
        ledger.advanceIfGatePassed(open, new GateOutcome("compile_workspace", JSON.createObjectNode(), 0, true));
        PlanId closed = openPlan();
        ledger.close(closed);
        Path file = planFile("s-plan.md", FRAME);

        ToolResponse response = draw(file);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(response.isSuccess(), String.valueOf(response));
        assertTrue(text.startsWith("# Plan\n\nBefore the board.\n\n" + PlanBoardTool.BEGIN + "\n"), "the text before the board changed");
        assertTrue(text.endsWith(PlanBoardTool.END + "\n\nAfter the board.\n"), "the text after the board changed");
        assertFalse(text.contains("hand written"), "the old text between the markers stayed");
        assertTrue(text.contains("| " + open + " | open | 1 of 2 | 0 compile_workspace: passed; 1 run_tests: waiting |"), text);
        assertTrue(text.contains("| " + closed + " | closed | 0 of 2 | 0 compile_workspace: not reached; 1 run_tests: not reached |"), text);
    }

    @Test
    @DisplayName("a hand edit inside the markers is overwritten at the next call, and a call that finds the file already saying what the ledger says rewrites nothing")
    void aHandEditInsideTheMarkersIsOverwritten() throws IOException {
        openPlan();
        Path file = planFile("s-plan.md", FRAME);
        assertTrue(draw(file).isSuccess());
        String drawn = Files.readString(file, StandardCharsets.UTF_8);

        assertEquals(false, JSON.valueToTree(draw(file).getData()).path("rewritten").asBoolean(), "an unchanged board was rewritten");

        Files.writeString(file, drawn.replace("waiting", "ALL DONE"), StandardCharsets.UTF_8);
        JsonNode second = JSON.valueToTree(draw(file).getData());

        assertEquals(true, second.path("rewritten").asBoolean());
        assertEquals(drawn, Files.readString(file, StandardCharsets.UTF_8), "the hand edit inside the markers survived");
    }

    @Test
    @DisplayName("a file that is not named ...-plan.md, or lacks the markers, or carries one twice, is refused and left exactly as it was")
    void aFileThatDidNotOptInIsRefusedAndUntouched() throws IOException {
        openPlan();
        Path misnamed = planFile("notes.md", FRAME);
        Path unmarked = planFile("a-plan.md", "# Plan\n\nNo board here.\n");
        Path doubled = planFile("b-plan.md", FRAME + PlanBoardTool.BEGIN + "\n");

        for (Path file : List.of(misnamed, unmarked, doubled)) {
            String before = Files.readString(file, StandardCharsets.UTF_8);
            ToolResponse response = draw(file);
            assertFalse(response.isSuccess(), file.getFileName() + " was drawn into");
            assertEquals(before, Files.readString(file, StandardCharsets.UTF_8), file.getFileName() + " was written despite the refusal");
        }
    }
}
