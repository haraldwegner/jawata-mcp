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
 * it offers, how its command line names one) is kept in the engine alone. The actions: the models each chat brain's own session offers, which
 * programs this machine has and whether they are logged in, a one-word test run through a brain and model before a seat keeps the choice, the
 * stored brain and model of every seat, and the storing of one seat's choice, of the whole set at once, or the removal of one.
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
                   brains(action="choices")                       — the stored brain and model of every seat
                   brains(action="set", seat="pm", brain="claude-code", model="Haiku 5.5") — store one seat's choice
                   brains(action="replace", choices=[{seat, brain, model}, ...]) — store the whole set at once
                   brains(action="remove", seat="pm")             — drop one seat's choice

            models answers {brains: [{brain, label, models}]}, the brain's own default left out; with no brain named, a brain whose session could
            not be opened carries {error} instead. status answers {brains: [{id, label, installed, login, reason, loginCommand, models, hirable,
            takesModel, writesHeldByInstruction}]}. probe answers {brain, ok, answer, modelsUsed, costUsd, seconds, error, loginCommand}; a model is
            named as a Brain list shows it, and the engine gives the program's command line what it takes. A brain or model the engine does not know
            is refused by name, never answered with an empty list. choices answers {seats: [{seat, brain, model}]}. set checks the brain and
            model against the registry and answers {seat, brain, model, applies}; a wrong one is refused and not stored. replace writes the set in
            one step and answers {seats, refused: [{seat, reason}], removed, applies}: a refused seat keeps its old choice, an unlisted one loses
            it. remove answers {seat, removed, applies}. The stored choice is what the next hire of that seat starts on. A hire that is already
            running is not moved.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of("type", "string", "enum", List.of("models", "status", "probe", "choices", "set", "replace", "remove"), "description", "What to ask (default models)."));
        properties.put("choices", Map.of("type", "array", "description", "replace: every stored choice, one per seat.",
            "items", Map.of("type", "object", "properties", Map.of("seat", Map.of("type", "string"), "brain", Map.of("type", "string"), "model", Map.of("type", "string")))));
        properties.put("brain", Map.of("type", "string", "description", "The brain's id (e.g. claude-code, cursor). models: omit for every ready chat brain; probe and set: required."));
        properties.put("model", Map.of("type", "string", "description", "probe and set: the model, as a Brain list shows it; omit for the brain's own default."));
        properties.put("seat", Map.of("type", "string", "description", "set and remove: the seat whose stored choice is changed."));
        properties.put("prompt", Map.of("type", "string", "description", "probe: a prompt to time; with it, repeat or keepRunning, the probe times runs."));
        properties.put("repeat", Map.of("type", "integer", "description", "probe: how many timed runs, 1 to 20 (default 1)."));
        properties.put("keepRunning", Map.of("type", "boolean", "description", "probe: start the program once and time each run from its prompt."));
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
            // A probe that names a prompt, a repeat count or keep-running is timed run by run (28h z93); without them it is the screen's one-word check.
            case "probe" -> arguments.hasNonNull("prompt") || arguments.hasNonNull("repeat") || arguments.path("keepRunning").asBoolean(false)
                ? timedProbe(door, brain, arguments) : probe(door, brain, arguments.path("model").asText(""));
            case "choices" -> choices(door);
            case "set" -> set(door, arguments.path("seat").asText(""), brain, arguments.path("model").asText(""));
            case "replace" -> replace(door, arguments.path("choices"));
            case "remove" -> remove(door, arguments.path("seat").asText(""));
            default -> ToolResponse.invalidParameter("action", "action must be models, status, probe, choices, set, replace or remove, not '" + action + "'");
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

    private static ToolResponse choices(SeatSettings door) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("seats", door.storedSeatChoices());
            return ToolResponse.success(data);
        } catch (IllegalArgumentException refused) {
            return ToolResponse.error("PRECONDITION_NOT_MET", refused.getMessage(), org.jawata.mcp.execution.SeatBrains.WHERE);
        }
    }

    private static ToolResponse set(SeatSettings door, String seat, String brain, String model) {
        if (seat.isBlank()) {
            return ToolResponse.invalidParameter("seat", "a stored choice names the seat");
        }
        if (brain.isBlank()) {
            return ToolResponse.invalidParameter("brain", "a stored choice names the brain");
        }
        try {
            return ToolResponse.success(new LinkedHashMap<>(door.storedSeatChoice(seat, brain, model)));
        } catch (IllegalArgumentException refused) {
            return ToolResponse.error("PRECONDITION_NOT_MET", refused.getMessage(), org.jawata.mcp.execution.SeatBrains.WHERE);
        } catch (IllegalStateException closed) {
            return ToolResponse.error("PRECONDITION_NOT_MET", closed.getMessage(), org.jawata.mcp.execution.SeatBrains.WHERE);
        }
    }

    /** The whole set of choices in one write (28h D58 (d)); a call that does not say the list, or names a seat twice, stores nothing. */
    private static ToolResponse replace(SeatSettings door, JsonNode choices) {
        if (!choices.isArray()) {
            return ToolResponse.invalidParameter("choices", "a replace names the whole list of choices; an empty list drops them all");
        }
        List<Map<String, String>> asked = new ArrayList<>();
        java.util.Set<String> seats = new java.util.HashSet<>();
        for (JsonNode each : choices) {
            String seat = each.path("seat").asText("").strip();
            if (seat.isEmpty() || !seats.add(seat)) {
                return ToolResponse.invalidParameter("choices", "each choice names its seat, and a seat only once");
            }
            asked.add(Map.of("seat", seat, "brain", each.path("brain").asText(""), "model", each.path("model").asText("")));
        }
        return changed(() -> door.replaceStoredSeatChoices(asked));
    }

    /** One seat's stored choice dropped (28h z173). */
    private static ToolResponse remove(SeatSettings door, String seat) {
        if (seat.isBlank()) {
            return ToolResponse.invalidParameter("seat", "a removal names the seat");
        }
        return changed(() -> door.removeStoredSeatChoice(seat));
    }

    /** A change to the stored choices, answered with what the door says, or with why it could not be made. */
    private static ToolResponse changed(Supplier<Map<String, Object>> change) {
        try {
            return ToolResponse.success(change.get());
        } catch (IllegalArgumentException | IllegalStateException refused) {
            return ToolResponse.error("PRECONDITION_NOT_MET", refused.getMessage(), org.jawata.mcp.execution.SeatBrains.WHERE);
        }
    }

    private static ToolResponse answer(List<Map<String, Object>> rows) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("brains", rows);
        return ToolResponse.success(data);
    }

    /** The timed runs a lead asks for (28h z93): the prompt as often as asked, kept running or not, with each run's figures. */
    private static ToolResponse timedProbe(SeatSettings door, String brain, JsonNode arguments) {
        if (brain.isBlank()) {
            return ToolResponse.invalidParameter("brain", "a timed run needs the brain to run it through");
        }
        String model = arguments.path("model").asText("");
        boolean keepRunning = arguments.path("keepRunning").asBoolean(false);
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("brain", brain);
            data.put("keepRunning", keepRunning);
            data.put("runs", door.timedProbe(brain, model.isBlank() ? null : model, arguments.path("prompt").asText("Answer with the one word: ready"),
                arguments.path("repeat").asInt(1), keepRunning));
            return ToolResponse.success(data);
        } catch (IllegalArgumentException | java.io.UncheckedIOException refused) {
            return ToolResponse.invalidParameter("brain", refused.getMessage());
        }
    }
}
