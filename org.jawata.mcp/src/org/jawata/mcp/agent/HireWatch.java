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

import org.jawata.mcp.execution.HireRegistry;
import org.jawata.mcp.execution.RunEffects;
import org.jawata.mcp.execution.SeatHire;
import org.jawata.mcp.execution.TreeAttribution;

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
        /** The folder the hire ran in, from its call's {@code workingDirectory}: where a rejected proposal's files are put back. */
        String workingDirectory = "";
        String answer = "";
        String seatFile = "";
        /** The engine's own plan id for this hire, from its answer; the name a later hire gives in {@code fixes}. */
        String enginePlan = "";
        Optional<String> brain = Optional.empty();
        Optional<String> model = Optional.empty();
        Optional<String> parent = Optional.empty();
        Optional<String> brainSessionId = Optional.empty();
        /** The first name the hire carries: read from the registry while it runs and from its answer once it has answered (Stage 24, D55). */
        Optional<String> name = Optional.empty();
        /** The slash command the seat file gives the seat, when it gives one. */
        Optional<String> seatCommand = Optional.empty();
        List<String> changedFiles = List.of();
        /** The change file by file as old and new text, when the hire's answer carried it. */
        List<TreeAttribution.FileChange> changes = List.of();
        /** The changed files whose texts were too large to carry. */
        List<String> changesOmitted = List.of();
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

    /**
     * What the hire tool's answer says in {@code brain.model} when the seat file, the person's pick and the registry setting all named no
     * model (the tool's own wording, in {@code HireTool}); it is the absence of a model and is never shown as one.
     */
    private static final String NO_MODEL_NAMED = "THE BRAIN'S OWN DEFAULT";

    private final LongSupplier clock;
    private final java.util.function.Supplier<String> registry;
    private final Map<String, Live> hires = new LinkedHashMap<>();
    /** The id of the hire an observed update has just marked accepted-with-fix, until {@link #takeFixed()} hands it on. */
    private String justFixed;

    /** @param clock the time in milliseconds, supplied so a test can say how long a hire ran */
    HireWatch(LongSupplier clock) {
        this(clock, () -> "");
    }

    /**
     * @param clock    the time in milliseconds, supplied so a test can say how long a hire ran
     * @param registry the text of the model registry setting, read each time a seat file names no model: the engine runs the hire on the
     *                 registry's model for the seat's brain then, so the card names it while the hire runs
     */
    HireWatch(LongSupplier clock, java.util.function.Supplier<String> registry) {
        this.clock = clock;
        this.registry = registry;
    }

    /** The entry this update shows, or none when the update is not about a hire. */
    synchronized Optional<HireEntry> observe(JsonNode update) {
        String id = update.path("toolCallId").asText("");
        if (id.isEmpty()) {
            return Optional.empty();
        }
        Live hire = hires.get(id);
        if (hire == null) {
            if (!isHireCall(update)) {
                return Optional.empty();
            }
            hire = new Live(id, clock.getAsLong());
            hires.put(id, hire);
        }
        JsonNode rawInput = update.path("rawInput");
        // Cursor wraps a tool's arguments: {providerIdentifier, toolName, args}; Claude sends the arguments themselves.
        readInput(hire, rawInput.has("toolName") && rawInput.path("args").isObject() ? rawInput.get("args") : rawInput);
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
    synchronized Optional<Proposal> proposalToAsk(String planId) {
        Live hire = hires.get(planId);
        // A chat puts a permission request to the person only when it carries a diff, and answers any other "allow once" at once; a request with
        // nothing to show would record a yes the person never gave, so a hire with no file to draw is not put to anyone.
        if (hire == null || hire.asked || !HireEntry.DONE.equals(hire.state) || hire.changedFiles.isEmpty() || hire.changes.isEmpty()) {
            return Optional.empty();
        }
        hire.asked = true;
        return Optional.of(new Proposal(hire.changedFiles, hire.changes, hire.changesOmitted, hire.workingDirectory));
    }

    /**
     * What a hire proposes: the files it changed, each with its old and new text where the hire's answer carried it, those it could not show,
     * and the folder it ran in (empty when its call named none).
     */
    record Proposal(List<String> files, List<TreeAttribution.FileChange> changes, List<String> omitted, String directory) {

        /**
         * What the person's no does: puts back, in the folder the hire ran in, the files this proposal changed, each only when that is exact and
         * safe, and says what it left and why ({@link TakeBack}).
         */
        TakeBack.Result takeBack(org.jawata.core.host.HostProcesses host) {
            return TakeBack.of(host, directory, this);
        }
    }

    /**
     * The card an observed hire has just marked accepted-with-fix, handed over once so the server can show and record the change; none when
     * no update since the last call marked one.
     */
    synchronized Optional<HireEntry> takeFixed() {
        String id = justFixed;
        justFixed = null;
        return id == null ? Optional.empty() : entry(id);
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
    record Said(String from, String text, String role) {
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
        turns.add(new Said(hire.hirer.isEmpty() ? MAIN : hire.hirer, hire.task, "user"));
        if (!hire.answer.isEmpty()) {
            turns.add(new Said(hire.seat, hire.answer, "assistant"));
        }
        return Optional.of(turns);
    }

    private static boolean isHireCall(JsonNode update) {
        // Claude names the call in its title (mcp__server__hire) and in _meta; Cursor names the tool in rawInput.toolName.
        for (String name : List.of(update.path("title").asText(""), update.path("_meta").path("claudeCode").path("toolName").asText(""),
                update.path("rawInput").path("toolName").asText(""))) {
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
        if (input.hasNonNull("workingDirectory")) {
            hire.workingDirectory = input.get("workingDirectory").asText();
        }
        if (input.hasNonNull("fixes") && !input.get("fixes").asText().isEmpty()) {
            // This hire corrects an earlier one, named by the plan id that hire's answer gave: the earlier one was accepted, with a fix to come.
            String corrected = input.get("fixes").asText();
            for (Live other : hires.values()) {
                if (other != hire && corrected.equals(other.enginePlan) && !other.verdict.equals(Optional.of(HireEntry.ACCEPTED_WITH_FIX))) {
                    other.verdict = Optional.of(HireEntry.ACCEPTED_WITH_FIX);
                    justFixed = other.id;
                }
            }
        }
        if (input.hasNonNull("seatFile") && !input.get("seatFile").asText().equals(hire.seatFile)) {
            hire.seatFile = input.get("seatFile").asText();
            readSeatFile(hire);
        }
    }

    private void readSeatFile(Live hire) {
        try {
            SeatHire file = SeatHire.read(Files.readString(Path.of(hire.seatFile)));
            hire.brain = Optional.of(file.brain().name().toLowerCase(Locale.ROOT).replace("_code", ""));
            // The seat file's own word first, then the registry's model for its brain: the order the hire tool runs the hire in.
            hire.model = file.model() == null || file.model().isBlank()
                ? org.jawata.mcp.execution.Hirer.brainIn(registry.get(), file.brain().label())
                    .map(org.jawata.mcp.execution.Hirer.Brain::model).filter(offered -> !offered.isBlank())
                : Optional.of(file.model());
            hire.seatCommand = Optional.ofNullable(file.command()).map(command -> "/" + command);
        } catch (IOException | RuntimeException e) {
            hire.brain = Optional.empty();
            hire.model = Optional.empty();
            hire.seatCommand = Optional.empty();
        }
    }

    /** The finished hire's own answer: whether it ended clean, what it spent, what it cost. */
    private void readAnswer(Live hire, JsonNode update) {
        JsonNode answer = answerOf(update);
        if ("running".equals(answer.path("state").asText("")) && answer.hasNonNull("hireId")) {
            // The background answer (Stage 24, M7): the hire is out and no result is in yet. The card stays running until the engine says it
            // ended; this answer only names the hire.
            hire.enginePlan = answer.get("hireId").asText();
            if (answer.hasNonNull("name")) {
                hire.name = Optional.of(answer.get("name").asText());
            }
            return;
        }
        if (!answer.has("cleanExit")) {
            // The brain's echo carries no answer (Cursor sends {"success": true} only): the engine ran the hire and kept what it answered.
            answer = HireRegistry.shared().answerOf(hire.seat, hire.task).orElse(answer);
        }
        applyAnswer(hire, answer);
    }

    private void applyAnswer(Live hire, JsonNode answer) {
        boolean clean = answer.isMissingNode() || !(answer.path("cleanExit").asText("true").equals("false")
            || answer.path("timedOut").asBoolean(false));
        finish(hire, clean ? HireEntry.DONE : HireEntry.FAILED);
        // The model the hire tool says ran is the first-hand word: it includes the person's pick in the Agent Control panel, which neither the
        // seat file nor the registry shows.
        String ran = answer.path("brain").path("model").asText("");
        if (!ran.isBlank() && !NO_MODEL_NAMED.equals(ran)) {
            hire.model = Optional.of(ran);
        }
        // The same for the brain: after a Replace it is another brain than the one the seat file named when the hire began (D53 (i)).
        String answeredBy = answer.path("brain").path("brain").asText("");
        if (!answeredBy.isBlank()) {
            hire.brain = Optional.of(answeredBy);
        }
        hire.enginePlan = answer.path("plan").asText("");
        if (answer.hasNonNull("name")) {
            hire.name = Optional.of(answer.get("name").asText());
        }
        hire.answer = answer.path("answer").asText("");
        hire.brainSessionId = answer.hasNonNull("brainSessionId") ? Optional.of(answer.get("brainSessionId").asText()) : Optional.empty();
        List<String> files = new java.util.ArrayList<>();
        answer.path("changedFiles").forEach(file -> files.add(file.asText()));
        hire.changedFiles = List.copyOf(files);
        List<TreeAttribution.FileChange> shown = new java.util.ArrayList<>();
        answer.path("changes").forEach(file -> shown.add(new TreeAttribution.FileChange(file.path("path").asText(),
            file.hasNonNull("oldText") ? file.get("oldText").asText() : null, file.hasNonNull("newText") ? file.get("newText").asText() : null,
            file.path("cleanBefore").asBoolean(false))));
        hire.changes = List.copyOf(shown);
        List<String> omitted = new java.util.ArrayList<>();
        answer.path("changesOmitted").forEach(name -> omitted.add(name.asText()));
        hire.changesOmitted = List.copyOf(omitted);
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

    /** A card this watch shows for a hire: the entry, and whether the host has not seen this card before. */
    record Seen(HireEntry entry, boolean created) {
    }

    /**
     * What the engine's registry says about a hire, as a card (Stage 24, M8): a hire that started or finished. A hire the principal's own brain
     * issued already has a card from that brain's call, which the event links to by seat and brief and by the plan id the call's answer gave; a
     * hire a lead issued has none, and the event creates it, with the plan id as its id. Nothing here reads a brain's echo.
     */
    synchronized Optional<Seen> engineEvent(HireRegistry.Event event) {
        Live hire = null;
        for (Live other : hires.values()) {
            boolean byPlan = !event.planId().isEmpty() && event.planId().equals(other.enginePlan);
            boolean byCall = other.enginePlan.isEmpty() && other.seat.equals(event.seat()) && other.task.equals(event.brief());
            if (byPlan || byCall) {
                hire = other;
                break;
            }
        }
        boolean created = false;
        if (hire == null) {
            if (event.planId().isEmpty()) {
                return Optional.empty();
            }
            hire = new Live(event.planId(), clock.getAsLong());
            hire.seat = event.seat();
            hire.task = event.brief();
            hires.put(hire.id, hire);
            created = true;
        }
        hire.enginePlan = event.planId();
        if (!event.name().isEmpty()) {
            hire.name = Optional.of(event.name());
        }
        if ("finished".equals(event.kind()) && event.answer() != null) {
            applyAnswer(hire, event.answer());
        }
        return Optional.of(new Seen(entryOf(hire), created));
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
        Optional<HireRegistry.Progress> live = HireEntry.RUNNING.equals(hire.state)
            ? HireRegistry.shared().progress(hire.seat, hire.task) : Optional.empty();
        OptionalDouble cost = live.isPresent() && live.get().costUsd().isPresent() ? live.get().costUsd() : hire.cost;
        int steps = live.map(HireRegistry.Progress::toolSteps).orElse(hire.toolSteps);
        OptionalLong context = live.map(HireRegistry.Progress::contextTokens).orElse(OptionalLong.empty());
        // A hire whose brain is compacting its context is still running; its card says what it is doing.
        String state = HireEntry.RUNNING.equals(hire.state) && HireRegistry.shared().compacting(hire.seat, hire.task)
            ? HireEntry.COMPACTING : hire.state;
        if (hire.name.isEmpty()) {
            hire.name = HireRegistry.shared().nameOf(hire.seat, hire.task);
        }
        // THE PARENT FROM WHO ISSUED THE HIRE (Stage 24, D55): the caller's id is the plan id of the lead's own hire, so the lead is the hire
        // whose plan id it names. This wins over the guess by seat name that readInput makes, which cannot tell two project managers apart.
        HireRegistry.shared().leadPlanOf(hire.seat, hire.task).ifPresent(leadPlan -> hires.values().stream()
            .filter(other -> other != hire && leadPlan.equals(HireRegistry.shared().planOf(other.seat, other.task).orElse(other.enginePlan)))
            .findFirst().ifPresent(lead -> hire.parent = Optional.of(lead.id)));
        return new HireEntry(hire.id, hire.seat, hire.brain, hire.model, state, end - hire.startedMs, hire.tokens, cost,
            hire.task, hire.parent, hire.brainSessionId, hire.verdict, levelOf(hire), steps, context, hire.seatCommand,
            hire.enginePlan.isBlank() ? Optional.empty() : Optional.of(hire.enginePlan), hire.name);
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
