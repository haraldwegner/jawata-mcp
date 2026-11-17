package org.jawata.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.jawata.mcp.engine.SeatSettings;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The engine's one answer about the agent programs a seat can run on (Sprint 28h, D57 (c) and the architect's watch of C26), for a host that is
 * not the engine's own process: the studio asks it instead of checking and testing each program itself, so a rule about a brain (which models
 * it offers, how its command line names one) is kept in the engine alone. Three questions: the models each chat brain's own session offers, which
 * programs this machine has and whether they are logged in, and a one-word test run through a brain and model before a seat keeps the choice.
 */
public final class BrainsTool implements Tool {

    private final Supplier<SeatSettings> seats;

    /**
     * @param seats the engine's seats door, asked per call because the settings it reads may change between calls
     */
    public BrainsTool(Supplier<SeatSettings> seats) {
        this.seats = seats;
    }

    @Override
    public String getName() {
        return "brains";
    }

    @Override
    public String getDescription() {
        return """
            The agent programs a seat can run on, as the engine knows them.

            USAGE: brains(action="models", brain="claude-code")   — one chat brain's models, as its own session names them
                   brains(action="models")                        — every ready chat brain's; what a deploy runs to discover them
                   brains(action="status")                        — every program this machine has: installed, logged in, its models
                   brains(action="probe", brain="claude-code", model="Haiku 5.5") — a one-word run through it (Claude: under a cent)

            models answers {brains: [{brain, label, models}]}, the brain's own default left out; with no brain named, a brain whose session could
            not be opened carries {error} instead. status answers {brains: [{id, label, installed, login, reason, loginCommand, models, hirable,
            takesModel, writesHeldByInstruction}]}. probe answers {brain, ok, answer, modelsUsed, costUsd, seconds, error, loginCommand}; a model is
            named as a Brain list shows it, and the engine gives the program's command line what it takes. A brain or model the engine does not know
            is refused by name, never answered with an empty list.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of("type", "string", "enum", List.of("models", "status", "probe"), "description", "What to ask (default models)."));
        properties.put("brain", Map.of("type", "string", "description", "The brain's id (e.g. claude-code, cursor). models: omit for every ready chat brain; probe: required."));
        properties.put("model", Map.of("type", "string", "description", "probe: the model to run on, as a Brain list shows it; omit for the brain's own default."));
        return Map.of("type", "object", "properties", properties);
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        SeatSettings door = seats.get();
        String action = arguments.path("action").asText("models");
        String brain = arguments.path("brain").asText("");
        return switch (action) {
            case "models" -> models(door, brain);
            case "status" -> status(door);
            case "probe" -> probe(door, brain, arguments.path("model").asText(""));
            default -> ToolResponse.invalidParameter("action", "action must be models, status or probe, not '" + action + "'");
        };
    }

    private static ToolResponse models(SeatSettings door, String brain) {
        if (!brain.isBlank()) {
            try {
                return answer(List.of(modelsRow(brain, labelOf(door, brain), door.conversationModels(brain))));
            } catch (IllegalArgumentException | java.io.UncheckedIOException refused) {
                return ToolResponse.invalidParameter("brain", refused.getMessage());
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SeatSettings.ConversationBrain one : door.conversationBrains()) {
            if (!one.ready()) {
                continue;
            }
            try {
                rows.add(modelsRow(one.id(), one.label(), door.conversationModels(one.id())));
            } catch (RuntimeException failed) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("brain", one.id());
                row.put("label", one.label());
                row.put("error", String.valueOf(failed.getMessage()));
                rows.add(row);
            }
        }
        return answer(rows);
    }

    /** Every program this machine has, as the engine's own check reads it; the studio shows these on its Seats page. */
    private static ToolResponse status(SeatSettings door) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SeatSettings.Brain one : door.brains()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", one.id());
            row.put("label", one.label());
            row.put("installed", one.installed());
            row.put("login", one.login());
            row.put("reason", one.reason().orElse(null));
            row.put("loginCommand", one.loginCommand());
            row.put("models", one.models().orElse(null));
            row.put("hirable", one.hirable());
            row.put("takesModel", one.takesModel());
            row.put("writesHeldByInstruction", one.writesHeldByInstruction());
            rows.add(row);
        }
        return answer(rows);
    }

    /** The one-word run a screen makes before it keeps a seat's brain and model; the model's command-line value is the engine's to give. */
    private static ToolResponse probe(SeatSettings door, String brain, String model) {
        if (brain.isBlank()) {
            return ToolResponse.invalidParameter("brain", "a test run needs the brain to run it through");
        }
        try {
            SeatSettings.Probe run = door.probe(brain, model.isBlank() ? null : model);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("brain", run.brain());
            data.put("ok", run.ok());
            data.put("answer", run.answer().orElse(null));
            data.put("modelsUsed", run.modelsUsed());
            data.put("costUsd", run.costUsd().orElse(null));
            data.put("seconds", run.seconds());
            data.put("error", run.error().orElse(null));
            data.put("loginCommand", run.loginCommand());
            return ToolResponse.success(data);
        } catch (IllegalArgumentException refused) {
            return ToolResponse.invalidParameter("brain", refused.getMessage());
        }
    }

    private static String labelOf(SeatSettings door, String brain) {
        return door.conversationBrains().stream().filter(one -> one.id().equals(brain)).map(SeatSettings.ConversationBrain::label).findFirst().orElse(brain);
    }

    private static Map<String, Object> modelsRow(String brain, String label, List<String> models) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("brain", brain);
        row.put("label", label);
        row.put("models", models);
        return row;
    }

    private static ToolResponse answer(List<Map<String, Object>> rows) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("brains", rows);
        return ToolResponse.success(data);
    }
}
