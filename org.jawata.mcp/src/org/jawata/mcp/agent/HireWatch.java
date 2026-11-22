package org.jawata.mcp.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
        /** How many files the hire's answer says its run changed; absent when the answer carries no count (Stage 27 (u)): a count nobody took is not zero. */
        java.util.OptionalInt changedCount = java.util.OptionalInt.empty();
        /** How the hire's run used jawata, as its answer reported it (Stage 27 (o)); absent when the answer carries none. */
        Optional<JsonNode> toolUse = Optional.empty();
        /** Tool calls the hire made, as its answer counted them (D10). */
        int toolSteps;
        /** The names of the tool calls the hire made, in order, as its answer listed them (28h D57 (i)). */
        List<String> toolsCalled = List.of();
        /** Code changes the hire's brain made by hand, outside any engine tool, as its answer counted them (D10). */
        int editsByHand;
        /** Whether an issuer gave the hire instructions that name tools, so an edit it made by hand was made where a tool was named (D10). */
        boolean instructed;
        /** The engine answered the call with a refusal that opened no plan: no hire exists for it. */
        boolean refused;
        /** Why the engine refused the hire before any brain started (28h z39); absent for every other hire. */
        Optional<String> refusal = Optional.empty();

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
    /** How long a running hire may show no sign of life before its card says stalled. */
    private final long stallSeconds;
    private final java.util.function.Supplier<String> registry;
    private final Map<String, Live> hires = new LinkedHashMap<>();
    /** The ids of hire calls the engine refused and whose refusal has not been taken yet. */
    private final java.util.Set<String> refusedCalls = new java.util.HashSet<>();
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
        this(clock, registry, USE_THE_REGISTRYS_CEILING);
    }

    /** Stands for the ceiling the registry holds, which is the host's setting when it set one: read when a card is made, not when the watch is. */
    private static final long USE_THE_REGISTRYS_CEILING = -1;

    /**
     * @param clock        the time in milliseconds, supplied so a test can say how long a hire ran
     * @param registry     the text of the model registry setting, read each time a seat file names no model
     * @param stallSeconds how long a running hire may show no sign of life before its card says it has stalled (28h Stage 27, z28)
     */
    HireWatch(LongSupplier clock, java.util.function.Supplier<String> registry, long stallSeconds) {
        this.stallSeconds = stallSeconds;
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
            // A hire call that came back failed was refused or went out empty: no hire exists for it, so it leaves no card (28h D57 (i)). A hire
            // that did start and whose call the brain gave up on is shown by the engine's own events, as a card of its own.
            hires.remove(id);
            return Optional.empty();
        }
        if ("completed".equals(status)) {
            readAnswer(hire, update);
            if (hire.refused) {
                // 28h-C27-9: a refused call that named a seat had drawn a card from its input; the call opened no plan, so it ends with no
                // card, and the refusal is told once to whoever forwards the update, which then says the call failed so the chat takes the
                // card back.
                hires.remove(id);
                refusedCalls.add(id);
                return Optional.empty();
            }
        }
        if (hire.seat.isEmpty()) {
            // A call that names nobody, as the brain first streams it or as it ended (a completed call whose answer is the engine's refusal of
            // empty arguments), has no agent to show: a card with none in it would stay for the rest of the conversation. A hire that did start
            // is shown by the engine's own events, which carry its seat.
            if ("completed".equals(status)) {
                hires.remove(id);
            }
            return Optional.empty();
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

    /** Whether the engine refused the hire call with this id, true once: a refused call has no card, and the chat is told it failed. */
    synchronized boolean takeRefused(String callId) {
        return refusedCalls.remove(callId);
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
     * What was said in one hire, in order: the brief its issuer gave, the tool calls the hire made (one {@code tool} turn each: by name as its
     * answer listed them, or by title as its brain streamed them while it still runs), then the answer the hire gave once it has finished. A hire still running has said nothing yet and an answer that holds
     * no text adds no turn, so a failure is never shown as an empty reply. None when the agent never saw a hire with this id.
     */
    synchronized Optional<List<Said>> transcript(String planId) {
        Live hire = hires.get(planId);
        if (hire == null) {
            return Optional.empty();
        }
        List<Said> turns = new java.util.ArrayList<>();
        turns.add(new Said(hire.hirer.isEmpty() ? MAIN : hire.hirer, hire.task, "user"));
        // A finished hire's answer lists its calls; a hire still running has them in the registry, by the titles its brain streamed.
        List<String> calls = !hire.toolsCalled.isEmpty() ? hire.toolsCalled
            : HireRegistry.shared().progress(hire.seat, hire.task).map(HireRegistry.Progress::calls).orElse(List.of());
        calls.forEach(call -> turns.add(new Said(hire.seat, call, "tool")));
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

    /** The seat brains the Seats page stores, read each time a hire's seat file is read: what the page chose shows on a running card above the seat file's own. */
    private volatile java.util.function.Supplier<String> seatBrains = () -> "";

    /** Installs the source of the stored seat brains (application wiring); none offered reads as nothing stored. */
    void useSeatBrains(java.util.function.Supplier<String> stored) {
        this.seatBrains = stored == null ? () -> "" : stored;
    }

    private void readSeatFile(Live hire) {
        try {
            SeatHire file = SeatHire.read(Files.readString(Path.of(hire.seatFile)));
            // A seat file that names no brain does not become Claude on the card.
            if (file.brain() == null) {
                hire.brain = Optional.empty();
                hire.model = Optional.empty();
            } else {
                hire.brain = Optional.of(file.brain().label());
                // The seat file's own word first, then the registry's model for its brain: the order the hire tool runs the hire in.
                hire.model = file.model() == null || file.model().isBlank()
                    ? org.jawata.mcp.execution.Hirer.brainIn(registry.get(), file.brain().label())
                        .map(org.jawata.mcp.execution.Hirer.Brain::model).filter(offered -> !offered.isBlank())
                    : Optional.of(file.model());
            }
            hire.seatCommand = Optional.ofNullable(file.command()).map(command -> "/" + command);
            // 28h Stage 27 (z14) rule 2: the Seats page's choice for the seat is what the hire runs on, so it is what its card shows while it runs
            org.jawata.mcp.execution.SeatSettingsService.storedBrainOf(key -> Optional.of(seatBrains.get()), hire.seat).ifPresent(page ->
                org.jawata.mcp.execution.SeatHire.Brain.named(page[0]).ifPresent(chosen -> {
                    hire.brain = Optional.of(chosen.label());
                    hire.model = page[1] != null ? Optional.of(page[1])
                        : org.jawata.mcp.execution.Hirer.brainIn(registry.get(), chosen.label()).map(org.jawata.mcp.execution.Hirer.Brain::model).filter(offered -> !offered.isBlank());
                }));
        } catch (IOException | RuntimeException e) {
            hire.brain = Optional.empty();
            hire.model = Optional.empty();
            hire.seatCommand = Optional.empty();
        }
    }

    /** The finished hire's own answer: whether it ended clean, what it spent, what it cost. */
    private void readAnswer(Live hire, JsonNode update) {
        JsonNode answer = org.jawata.mcp.execution.StartedHires.answerOf(update);
        if ("running".equals(answer.path("state").asText("")) && answer.hasNonNull("hireId")) {
            // The background answer (Stage 24, M7): the hire is out and no result is in yet. The card stays running until the engine says it
            // ended; this answer only names the hire.
            hire.enginePlan = answer.get("hireId").asText();
            if (answer.hasNonNull("name")) {
                hire.name = Optional.of(answer.get("name").asText());
            }
            return;
        }
        if (answer.path("success").isBoolean() && !answer.get("success").asBoolean() && !answer.hasNonNull("hireId")) {
            // 28h D57 (i): a hire call the engine refused (the access policy, a refused seat, a bad argument) opened no plan, so no engine
            // event will ever end its card; it ends here, and Agent Control, which shows only working hires, shows nothing for a hire that never ran.
            // 28h z39: unless the engine kept its own answer saying it refused this very hire before any brain started: then the card stays,
            // as refused, and says why, where taking it back would leave nothing to say it.
            Optional<JsonNode> keptRefusal = HireRegistry.shared().answerOf(hire.seat, hire.task).filter(kept -> kept.path("refusedByEngine").asBoolean(false));
            if (keptRefusal.isPresent()) {
                applyAnswer(hire, keptRefusal.get());
                return;
            }
            hire.refused = true;
            return;
        }
        if (!answer.has("cleanExit")) {
            // The brain's echo carries no answer (Cursor sends {"success": true} only). The engine keeps what a hire answered once it ends; with
            // nothing kept the hire is still running, and the engine's own event ends this card, so an echo must not (found live, 2026-11-10).
            Optional<JsonNode> kept = HireRegistry.shared().answerOf(hire.seat, hire.task);
            if (kept.isEmpty()) {
                return;
            }
            answer = kept.get();
        }
        applyAnswer(hire, answer);
    }

    private void applyAnswer(Live hire, JsonNode answer) {
        if (answer.path("refusedByEngine").asBoolean(false)) {
            // 28h z39: the engine refused the hire before any brain started, so no program failed: the card ends as refused and names no
            // brain and no model, though the seat file names them, and carries the reason.
            finish(hire, HireEntry.REFUSED);
            hire.brain = Optional.empty();
            hire.model = Optional.empty();
            hire.refusal = Optional.of("the engine refused this hire before any brain started: " + answer.path("error").asText("")
                + (answer.hasNonNull("hireThrough") ? "; hire it through " + answer.get("hireThrough").asText() : ""));
            return;
        }
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
        hire.changedCount = answer.path("changedFileCount").isInt() ? java.util.OptionalInt.of(answer.get("changedFileCount").asInt()) : java.util.OptionalInt.empty();
        hire.toolUse = answer.path("toolUse").isObject() ? Optional.of(answer.get("toolUse")) : Optional.empty();
        List<TreeAttribution.FileChange> shown = new java.util.ArrayList<>();
        answer.path("changes").forEach(file -> shown.add(new TreeAttribution.FileChange(file.path("path").asText(),
            file.hasNonNull("oldText") ? file.get("oldText").asText() : null, file.hasNonNull("newText") ? file.get("newText").asText() : null,
            file.path("cleanBefore").asBoolean(false))));
        hire.changes = List.copyOf(shown);
        List<String> omitted = new java.util.ArrayList<>();
        answer.path("changesOmitted").forEach(name -> omitted.add(name.asText()));
        hire.changesOmitted = List.copyOf(omitted);
        hire.toolSteps = answer.path("toolSteps").asInt(0);
        List<String> called = new java.util.ArrayList<>();
        answer.path("toolsCalled").forEach(name -> called.add(name.asText()));
        hire.toolsCalled = List.copyOf(called);
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
            // Only a card whose call is still open can be the one this event is for: a refused call's card has ended, and a retry of the same call
            // (found by the C24 audit of the live runs) would otherwise have its events land on that failed card.
            boolean byCall = other.endedMs < 0 && other.enginePlan.isEmpty() && other.seat.equals(event.seat()) && other.task.equals(event.brief());
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
        // A running hire that has shown no sign of life for the first ceiling says so, before the engine's second ceiling stops it (28h Stage 27, z28).
        if (HireEntry.RUNNING.equals(state)
            && HireRegistry.shared().planOf(hire.seat, hire.task).map(plan -> HireRegistry.shared().idleSecondsOf(plan))
                .filter(idle -> idle.isPresent() && idle.getAsLong() >= (stallSeconds >= 0 ? stallSeconds : HireRegistry.shared().quietCeiling())).isPresent()) {
            state = HireEntry.STALLED;
        }
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
            hire.enginePlan.isBlank() ? Optional.empty() : Optional.of(hire.enginePlan), hire.name, hire.changedCount, hire.toolUse, hire.refusal);
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
