package org.jawata.mcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The plan's board (Sprint 28h, S10, D18): the plan ledger drawn into the sprint's plan file, between two markers, so the file a person
 * reads says what the gates say and not what anyone remembered. It rewrites only the text between the markers, and only in a file named
 * {@code ...-plan.md} that already carries both markers, so it cannot touch any other file or any other part of the plan.
 */
public final class PlanBoardTool implements Tool {

    static final String BEGIN = "<!-- jawata:board:begin -->";
    static final String END = "<!-- jawata:board:end -->";

    private final PlanLedger plans;

    public PlanBoardTool(PlanLedger plans) {
        this.plans = plans;
    }

    @Override
    public String getName() {
        return "plan_board";
    }

    @Override
    public String getDescription() {
        return """
            Draws the plan ledger into a sprint's plan file, between the board markers, and changes nothing else in it.

            USAGE: plan_board(file="<sprint>-plan.md")

            The file must be named ...-plan.md and must already carry the two markers on lines of their own:
            <!-- jawata:board:begin --> and <!-- jawata:board:end -->. Whatever stands between them is replaced with one row per plan the
            ledger holds, open or closed: its state, how many steps passed, and each step with the gate that moves it. A hand edit inside
            the markers is overwritten at the next call; text outside them is never touched. Answers {file, plans, closed, rewritten};
            rewritten is false when the file already said what the ledger says. Call it after a gate advances a plan.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of("type", "object",
            "properties", Map.of("file", Map.of("type", "string",
                "description", "Path of the sprint plan file, named ...-plan.md, carrying the board markers.")),
            "required", List.of("file"));
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        String file = arguments == null || !arguments.hasNonNull("file") ? "" : arguments.get("file").asText();
        if (file.isBlank()) {
            return ToolResponse.invalidParameter("file", "name the sprint plan file to draw the board into");
        }
        Path path = Path.of(file);
        if (!path.getFileName().toString().endsWith("-plan.md")) {
            return ToolResponse.invalidParameter("file", "a board is drawn only into a file named ...-plan.md, not " + path.getFileName());
        }
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ToolResponse.error("FILE_NOT_FOUND", "the plan file could not be read: " + e.getMessage(), "check the path");
        }
        int begin = text.indexOf(BEGIN);
        int end = text.indexOf(END);
        if (begin < 0 || end < 0 || end < begin || text.indexOf(BEGIN, begin + 1) >= 0 || text.indexOf(END, end + 1) >= 0) {
            return ToolResponse.error("PRECONDITION_NOT_MET",
                "the plan file does not carry the board markers exactly once each, begin before end",
                "add " + BEGIN + " and " + END + " on lines of their own where the board goes; nothing is written without them");
        }
        List<PlanLedger.PlanView> view = plans.view();
        String drawn = text.substring(0, begin + BEGIN.length()) + "\n" + render(view) + text.substring(end);
        boolean rewritten = !drawn.equals(text);
        if (rewritten) {
            try {
                Files.writeString(path, drawn, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return ToolResponse.error("INTERNAL_ERROR", "the plan file could not be written: " + e.getMessage(), "check the path is writable");
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("file", path.toString());
        data.put("plans", view.size());
        data.put("closed", view.stream().filter(PlanLedger.PlanView::closed).count());
        data.put("rewritten", rewritten);
        return ToolResponse.success(data);
    }

    /** The board: one row per plan, each step with its gate and whether it passed, was never reached, or is still waiting. */
    static String render(List<PlanLedger.PlanView> view) {
        StringBuilder board = new StringBuilder("_Drawn by plan_board from the plan ledger; edit outside the markers._\n\n");
        if (view.isEmpty()) {
            return board.append("_The ledger holds no plans._\n").toString();
        }
        board.append("| plan | state | passed | steps |\n|---|---|---|---|\n");
        for (PlanLedger.PlanView plan : view) {
            StringBuilder steps = new StringBuilder();
            for (GatedStep step : plan.steps()) {
                if (steps.length() > 0) {
                    steps.append("; ");
                }
                String state = step.index() <= plan.passedStep() ? "passed" : plan.closed() ? "not reached" : "waiting";
                steps.append(step.index()).append(' ').append(step.tool()).append(": ").append(state);
            }
            board.append("| ").append(plan.id()).append(" | ").append(plan.closed() ? "closed" : "open").append(" | ")
                .append(plan.passedStep() + 1).append(" of ").append(plan.steps().size()).append(" | ").append(steps).append(" |\n");
        }
        return board.toString();
    }
}
