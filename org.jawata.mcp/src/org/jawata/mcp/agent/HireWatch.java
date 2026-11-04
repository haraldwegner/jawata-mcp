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
import java.util.OptionalLong;
import java.util.function.LongSupplier;

import org.jawata.mcp.execution.RunEffects;
import org.jawata.mcp.execution.RunningHires;
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
        String answer = "";
        String seatFile = "";
        Optional<String> brain = Optional.empty();
        Optional<String> model = Optional.empty();
        Optional<String> parent = Optional.empty();
        Optional<String> brainSessionId = Optional.empty();
        List<String> changedFiles = List.of();
        boolean asked;
        Optional<String> verdict = Optional.empty();
        String state = HireEntry.RUNNING;
        long endedMs = -1;
        long tokens;
        OptionalDouble cost = OptionalDouble.empty();
        /** Tool calls the hire made, as its answer counted them (D10). */
        int toolSteps;
        /** Code changes the hire's brain made by hand, outside any engine tool, as its answer counted them (D10). */
        int editsByHand;
        /** Whether an issuer gave the hire instructions that name tools, so an edit it made by hand was made where a tool was named (D10). */
        boolean instructed;

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

    /**
     * The per-role figures of the measures (D10) over every hire seen, from the hires' own answers: the tokens and tool calls each reported,
     * and, for a developer, the code changes its brain made by hand in a hire whose instructions named tools. A chief engineer's edit orders
     * given where a tool existed are not counted: an instruction names a tool and does not label an edit order, so that part of D10 has
     * nothing to read yet and is reported by the server as not measured.
     */
    synchronized List<RoleFigures> figures() {
        return RoleFigures.of(hires.values().stream()
            .map(h -> new RoleFigures.HireFacts(h.seat, h.tokens, h.toolSteps, h.instructed ? h.editsByHand : 0, 0))
            .toList());
    }

    /** Every hire seen, in the order they began. */
    synchronized List<HireEntry> entries() {
        return hires.values().stream().map(this::entryOf).toList();
    }

    /** The hire with this id, if the agent saw it. */
    synchronized Optional<HireEntry> entry(String planId) {
        return Optional.ofNullable(hires.get(planId)).map(this::entryOf);
    }

    /**
     * The files a finished hire changed when the person has not yet been asked about them: present once per hire, for a hire that ended well and
     * changed files. That list is the hire's proposal. A hire that failed, changed nothing or has already been asked about is not asked again.
     */
    synchronized Optional<List<String>> proposalToAsk(String planId) {
        Live hire = hires.get(planId);
        if (hire == null || hire.asked || !HireEntry.DONE.equals(hire.state) || hire.changedFiles.isEmpty()) {
            return Optional.empty();
        }
        hire.asked = true;
        return Optional.of(hire.changedFiles);
    }

    /** Records the person's answer to a hire's proposal and gives the entry as it now stands; none when the agent never saw the hire. */
    synchronized Optional<HireEntry> answered(String planId, String verdict) {
        Live hire = hires.get(planId);
        if (hire == null) {
            return Optional.empty();
        }
        hire.verdict = Optional.of(verdict);
        return Optional.of(entryOf(hire));
    }

    /** One thing said in a hire: who said it, and the words. */
    record Said(String from, String text) {
    }

    /**
     * What was said in one hire, in order: the brief its issuer gave, then the answer the hire gave once it has finished. A hire still running
     * has said nothing yet and an answer that holds no text adds no turn, so a failure is never shown as an empty reply. None when the agent
     * never saw a hire with this id.
     */
    synchronized Optional<List<Said>> transcript(String planId) {
        Live hire = hires.get(planId);
        if (hire == null) {
            return Optional.empty();
        }
        List<Said> turns = new java.util.ArrayList<>();
        turns.add(new Said(hire.hirer.isEmpty() ? MAIN : hire.hirer, hire.task));
        if (!hire.answer.isEmpty()) {
            turns.add(new Said(hire.seat, hire.answer));
        }
        return Optional.of(turns);
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
        hire.answer = answer.path("answer").asText("");
        hire.brainSessionId = answer.hasNonNull("brainSessionId") ? Optional.of(answer.get("brainSessionId").asText()) : Optional.empty();
        List<String> files = new java.util.ArrayList<>();
        answer.path("changedFiles").forEach(file -> files.add(file.asText()));
        hire.changedFiles = List.copyOf(files);
        hire.toolSteps = answer.path("toolSteps").asInt(0);
        hire.editsByHand = answer.path("codeChangesByHand").asInt(0);
        hire.instructed = answer.path("instructionScore").path("instructionsIssued").asInt(0) > 0;
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
        // While the hire runs, what its brain has reported so far is read from the engine's registry of running hires; once it has answered,
        // the figures are the answer's own.
        Optional<RunningHires.Progress> live = HireEntry.RUNNING.equals(hire.state)
            ? RunningHires.shared().progress(hire.seat, hire.task) : Optional.empty();
        OptionalDouble cost = live.isPresent() && live.get().costUsd().isPresent() ? live.get().costUsd() : hire.cost;
        int steps = live.map(RunningHires.Progress::toolSteps).orElse(hire.toolSteps);
        OptionalLong context = live.map(RunningHires.Progress::contextTokens).orElse(OptionalLong.empty());
        return new HireEntry(hire.id, hire.seat, hire.brain, hire.model, hire.state, end - hire.startedMs, hire.tokens, cost,
            hire.task, hire.parent, hire.brainSessionId, hire.verdict, levelOf(hire), steps, context);
    }

    /** How many hires the agent saw above this one, so a root is level 0, the chief engineer it hires 1, and the developer under that 2. */
    private int levelOf(Live hire) {
        int level = 0;
        Optional<String> above = hire.parent;
        while (above.isPresent() && level < 16) {
            Live up = hires.get(above.get());
            if (up == null) {
                break;
            }
            level++;
            above = up.parent;
        }
        return level;
    }
}
