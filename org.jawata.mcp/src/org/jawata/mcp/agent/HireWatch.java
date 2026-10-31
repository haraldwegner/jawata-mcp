package org.jawata.mcp.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.LongSupplier;

import org.jawata.mcp.execution.RunEffects;
import org.jawata.mcp.execution.SeatHire;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads the hires out of the tool calls a brain streams (Sprint 28h, S9, E3): a call to the engine's {@code hire} tool is a hire, and its
 * updates say who was hired, with what brief, and in the end how it went. Nothing is estimated: the seat and brief come from the call's
 * input, the brain and model from the seat file the call names, and the tokens and cost from the answer the hire gave. An update that is
 * not about a hire is ignored.
 *
 * <p>A hire's identity here is its tool call's id, because the engine's own plan id exists only in the answer, and an identity that changed
 * when the hire finished would make one row two.</p>
 */
final class HireWatch {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MAIN = "main";

    /** What is known of one hire so far. */
    private static final class Live {
        final String id;
        final long startedMs;
        String seat = "";
        String task = "";
        String hirer = "";
        String seatFile = "";
        Optional<String> brain = Optional.empty();
        Optional<String> model = Optional.empty();
        Optional<String> parent = Optional.empty();
        String state = HireEntry.RUNNING;
        long endedMs = -1;
        long tokens;
        OptionalDouble cost = OptionalDouble.empty();

        Live(String id, long startedMs) {
            this.id = id;
            this.startedMs = startedMs;
        }
    }

    private final LongSupplier clock;
    private final Map<String, Live> hires = new LinkedHashMap<>();

    /** @param clock the time in milliseconds, supplied so a test can say how long a hire ran */
    HireWatch(LongSupplier clock) {
        this.clock = clock;
    }

    /** The entry this update shows, or none when the update is not about a hire. */
    synchronized Optional<HireEntry> observe(JsonNode update) {
        String id = update.path("toolCallId").asText("");
        if (id.isEmpty()) {
            return Optional.empty();
        }
        Live hire = hires.get(id);
        if (hire == null) {
            if (!"tool_call".equals(update.path("sessionUpdate").asText()) || !isHireCall(update)) {
                return Optional.empty();
            }
            hire = new Live(id, clock.getAsLong());
            hires.put(id, hire);
        }
        readInput(hire, update.path("rawInput"));
        String status = update.path("status").asText("");
        if ("failed".equals(status)) {
            finish(hire, HireEntry.FAILED);
        } else if ("completed".equals(status)) {
            readAnswer(hire, update);
        }
        return Optional.of(entryOf(hire));
    }

    /** Every hire seen, in the order they began. */
    synchronized List<HireEntry> entries() {
        return hires.values().stream().map(this::entryOf).toList();
    }

    /** The hire with this id, if the agent saw it. */
    synchronized Optional<HireEntry> entry(String planId) {
        return Optional.ofNullable(hires.get(planId)).map(this::entryOf);
    }

    private static boolean isHireCall(JsonNode update) {
        for (String name : List.of(update.path("title").asText(""), update.path("_meta").path("claudeCode").path("toolName").asText(""))) {
            if (!name.isEmpty() && "hire".equals(RunEffects.bareName(name))) {
                return true;
            }
        }
        return false;
    }

    private void readInput(Live hire, JsonNode input) {
        if (input.hasNonNull("seat")) {
            hire.seat = input.get("seat").asText();
        }
        if (input.hasNonNull("brief")) {
            hire.task = input.get("brief").asText();
        }
        if (input.hasNonNull("hirer")) {
            hire.hirer = input.get("hirer").asText();
        }
        if (input.hasNonNull("seatFile") && !input.get("seatFile").asText().equals(hire.seatFile)) {
            hire.seatFile = input.get("seatFile").asText();
            readSeatFile(hire);
        }
        if (hire.parent.isEmpty() && !hire.hirer.isEmpty() && !MAIN.equals(hire.hirer)) {
            hire.parent = hires.values().stream().filter(other -> other != hire && other.endedMs < 0 && other.seat.equals(hire.hirer))
                .reduce((first, second) -> second).map(other -> other.id);
        }
    }

    private static void readSeatFile(Live hire) {
        try {
            SeatHire file = SeatHire.read(Files.readString(Path.of(hire.seatFile)));
            hire.brain = Optional.of(file.brain().name().toLowerCase(Locale.ROOT).replace("_code", ""));
            hire.model = file.model() == null || file.model().isBlank() ? Optional.empty() : Optional.of(file.model());
        } catch (IOException | RuntimeException e) {
            hire.brain = Optional.empty();
            hire.model = Optional.empty();
        }
    }

    /** The finished hire's own answer: whether it ended clean, what it spent, what it cost. */
    private void readAnswer(Live hire, JsonNode update) {
        JsonNode answer = answerOf(update);
        boolean clean = answer.isMissingNode() || !(answer.path("cleanExit").asText("true").equals("false")
            || answer.path("timedOut").asBoolean(false));
        finish(hire, clean ? HireEntry.DONE : HireEntry.FAILED);
        JsonNode tokens = answer.path("tokens");
        if (tokens.isObject()) {
            hire.tokens = tokens.hasNonNull("spent") ? tokens.get("spent").asLong()
                : tokens.path("input").asLong() + tokens.path("output").asLong() + tokens.path("reasoning").asLong();
        }
        if (answer.path("costUsd").isNumber()) {
            hire.cost = OptionalDouble.of(answer.get("costUsd").asDouble());
        }
    }

    /** The hire tool's answer as JSON: the call's raw output, or the text of its content, with the tool's {@code data} wrapper removed. */
    private static JsonNode answerOf(JsonNode update) {
        String text = null;
        JsonNode raw = update.path("rawOutput");
        if (raw.isTextual()) {
            text = raw.asText();
        } else if (raw.isObject()) {
            return raw.has("data") ? raw.get("data") : raw;
        } else {
            for (JsonNode block : update.path("content")) {
                String piece = block.path("content").path("text").asText("");
                if (!piece.isEmpty()) {
                    text = piece;
                    break;
                }
            }
        }
        if (text == null) {
            return JSON.missingNode();
        }
        try {
            JsonNode parsed = JSON.readTree(text);
            return parsed.has("data") ? parsed.get("data") : parsed;
        } catch (IOException e) {
            return JSON.missingNode();
        }
    }

    private void finish(Live hire, String state) {
        if (hire.endedMs < 0) {
            hire.endedMs = clock.getAsLong();
        }
        hire.state = state;
    }

    private HireEntry entryOf(Live hire) {
        long end = hire.endedMs >= 0 ? hire.endedMs : clock.getAsLong();
        return new HireEntry(hire.id, hire.seat, hire.brain, hire.model, hire.state, end - hire.startedMs, hire.tokens, hire.cost,
            hire.task, hire.parent);
    }
}
