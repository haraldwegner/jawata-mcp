package org.jawata.mcp.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * cursor-agent headless, as a dialect (Sprint 28g S16, D36) — <b>the brain that CANNOT be told which
 * tools to attach</b>, which is what makes it C16's subject rather than its fourth entry.
 *
 * <p>Everything below was measured on 2026-10-08 by reading this CLI's own {@code --help} and then
 * RUNNING it, in an empty directory, with a prompt that needs no tools. Nothing here is inferred
 * from another vendor's shape, and the two places where cursor and Claude Code genuinely agree are
 * named as such rather than assumed.</p>
 *
 * <h2>Why it cannot be told, and why that is NOT "it cannot be contained"</h2>
 *
 * <p>Its {@code -p/--print} says outright: <i>"Has access to all tools, including write and
 * shell."</i> There is no allowlist flag — the only tool-adjacent controls are {@code --mode
 * plan|ask} (read-only), {@code --approve-mcps} (approve ALL) and {@code --sandbox}, and MCP servers
 * are managed out of band by the {@code mcp} subcommand. So {@link #toolAttachment()} answers
 * {@link ToolAttachment.Reach#NONE} — and it answers with the containment this brain DOES offer,
 * because reporting a bare <i>unsupported</i> would have a reader conclude cursor is unusable when
 * what is true is narrower: it is uncontainable <em>per tool</em>.</p>
 *
 * <h2>{@code --mode plan} is ON by default, and that is this class's one real decision</h2>
 *
 * <p>Not caution — alignment with the stance the whole hire path already takes. {@link HiredBrain}'s
 * own contract is that <i>a hired run PROPOSES, because nobody is watching it</i>, and this is the
 * one brain of the four whose unattended default is write-and-shell access to the working directory.
 * Read-only is therefore what this product's own rule already implies for it, and defaulting it ON
 * makes the containment REAL rather than merely reported. A caller who means to let it write says so
 * explicitly, which is the direction a decision like this should have to travel.</p>
 *
 * <h2>Two things the RUN found that no {@code --help} states</h2>
 *
 * <p><b>It refuses an untrusted directory and does nothing.</b> First run: <i>"Workspace Trust
 * Required … To proceed, you can either: run 'agent' interactively to decide; pass {@code --trust},
 * {@code --yolo}, or {@code -f}"</i>, exit 1, no events at all. A dialect that omits the flag hires a
 * brain that never starts, and the symptom is an EMPTY ANSWER — which is precisely the state
 * {@link HiredBrain.Outcome#saidNothing()} exists to keep apart from genuine silence. {@code --trust}
 * is used and not {@code -f}/{@code --yolo}: it is the narrow one, and it trusts the directory the
 * HIRER chose rather than disabling the question.</p>
 *
 * <p><b>Its events are Claude Code's shape.</b> Measured, not assumed: the assistant event is
 * {@code {"type":"assistant","message":{"role":"assistant","content":[{"type":"text",...}]}}} and the
 * terminal event is {@code {"type":"result","subtype":"success","result":"ok",...}}. That is why the
 * content-array walk lives in {@link StreamJsonEvents} and is CALLED here rather than copied — the
 * architect seat's rule refuses a second implementation of a job the system already does.</p>
 *
 * <h2>IT REPORTS NO DOLLAR COST, and nothing here pretends otherwise</h2>
 *
 * <p>Its terminal event carries {@code usage:{inputTokens,outputTokens,cacheReadTokens,
 * cacheWriteTokens}} and <b>no dollar figure of any kind</b>. So {@link BrainLine#costUsd()} is
 * always ABSENT from this dialect, which {@link HiredBrain.Outcome} already keeps distinct from zero
 * — and the consequence belongs in a caller's view rather than hidden here:
 * {@link HiredBrain.Ceilings#maxCostUsd()} can never fire on this brain. Publishing a token count
 * under the name of a dollar cost would be a unit error wearing the contracted measure's name, which
 * is worse than reporting nothing.</p>
 */
public final class CursorDialect implements BrainDialect {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String binary;
    private final boolean readOnly;
    private final String model;

    /** Read-only, which is what a run that may not apply anything should be. */
    public CursorDialect() {
        this("cursor-agent", true, null);
    }

    /**
     * @param readOnly whether to pass {@code --mode plan}. Defaulting this to true is deliberate —
     *     see the class javadoc. Passing false lets the hired brain write and run shell commands in
     *     the working directory the caller supplied.
     * @param model the model to ask for, or null to take the CLI's own default (it reports
     *     {@code "model":"Auto"} when none is named).
     */
    public CursorDialect(String binary, boolean readOnly, String model) {
        this.binary = binary == null || binary.isBlank() ? "cursor-agent" : binary;
        this.readOnly = readOnly;
        this.model = model;
    }

    @Override
    public String name() {
        return "cursor-agent";
    }

    /**
     * {@code tools} is IGNORED, and the ignoring is reported rather than silent — see
     * {@link #toolAttachment()}. This CLI has no allowlist flag to put them in, and inventing one
     * would mean a run reporting tools as attached while the brain kept every tool it had.
     */
    @Override
    public List<String> command(String prompt, List<String> tools) {
        List<String> argv = new ArrayList<>(List.of(
            binary, "--print",
            "--output-format", "stream-json",
            // WITHOUT THIS IT NEVER STARTS — measured, see the class javadoc. The narrow flag:
            // it trusts the directory the hirer chose, rather than suppressing the question.
            "--trust"));
        if (readOnly) {
            argv.add("--mode");
            argv.add("plan");
            // AND THE SANDBOX, because --mode plan ALONE WAS MEASURED TO FAIL. C16 told this seat,
            // under plan mode, to create a file: across five runs with identical flags it refused
            // four times — each answer citing plan mode and "markdown-only edits" — and ONCE IT
            // WROTE THE FILE. Plan mode is an instruction the model carries out, not a bound on
            // what it can do, and an instruction a model follows four times in five is not
            // containment.
            //
            // AND ITS INDEPENDENT EFFECT ON WRITES IS NOW MEASURED: THERE IS NONE. An earlier
            // version of this comment said "NOT MEASURED: that it is what stops a write ... its
            // independent effect is unestablished", and kept the flag on the reasoning that it is
            // the only lever stronger than an instruction. The measurement that was missing could
            // not be taken through the hire path, which always sends both flags; taken at the CLI
            // with --mode plan OMITTED, this flag stopped nothing — TEN runs across two
            // independent probe runs, TEN writes, and the CLI's own init event reporting
            // permissionMode:"default" each time.
            //
            // SO THE FLAG STAYS AND THE CLAIM GOES, which is the smaller change and the honest
            // one. What it is documented for is "sandbox mode" at large; whether it bounds SHELL
            // or NETWORK is not measured here and may well be real. What it does not do is bound
            // a write to the working directory, so no part of this hire's write containment may
            // rest on it. Removing it would be a behaviour change justified by a measurement
            // scoped to one of the things it claims.
            argv.add("--sandbox");
            argv.add("enabled");
        }
        if (model != null && !model.isBlank()) {
            argv.add("--model");
            argv.add(model);
        }
        // The prompt travels LAST and as one argument, so a brief containing newlines, quotes or
        // shell metacharacters needs no escaping and cannot be mis-escaped.
        argv.add(prompt == null ? "" : prompt);
        return List.copyOf(argv);
    }

    /**
     * {@code --mode plan} is an instruction: measured, a brief to create a file was refused in
     * ten runs and carried out in five of fifteen pooled. {@code --sandbox enabled}, which this
     * dialect also sends, was measured to add NO write bound, so it is not named as a basis.
     */
    @Override
    public WriteContainment writeContainment() {
        if (readOnly) {
            return WriteContainment.instruction("--mode plan",
                "breached in 5 of 15 measured runs; --sandbox enabled, also sent, added no write"
                    + " bound (ten of ten runs wrote with --mode plan omitted)");
        }
        return WriteContainment.none("--mode plan is not passed, by explicit request: writes and"
            + " shell commands are permitted in the working directory");
    }

    @Override
    public ToolAttachment toolAttachment() {
        // THE WORDING NO LONGER PROMISES WHAT WAS MEASURED TO FAIL. It used to read "read-only, so
        // it analyses and proposes and cannot edit or run shell commands" — and under exactly
        // those flags this brain wrote a file once in five runs. A record that states a bound the
        // product does not have is worse than one that states none, because a reader stops
        // checking.
        return ToolAttachment.cannotBeTold(readOnly
            ? "--mode plan plus --sandbox enabled, both passed by this hire. MEASURED: NEITHER"
                + " BOUNDS A WRITE. Plan mode is an instruction this brain carries out rather than"
                + " a bound — told to write a file under it, it wrote 5 times in 15 runs across"
                + " three independent measurements, and no run ever produced a mechanism refusal."
                + " The sandbox, measured alone with plan mode omitted, stopped nothing at all: 10"
                + " runs, 10 writes. Treat a cursor hire as UNCONTAINED for writes"
            : "--mode plan|ask (NOT passed by this hire, which therefore has write and shell"
                + " access), --sandbox, and the hire's own wall-clock, cost and step ceilings");
    }

    @Override
    public BrainLine read(String line) {
        if (line == null || line.isBlank()) {
            return BrainLine.nothing();
        }
        JsonNode event;
        try {
            event = MAPPER.readTree(line);
        } catch (Exception e) {
            // A fact the driver can count, not an error and not silence.
            return BrainLine.unreadable();
        }
        if (event == null || !event.isObject()) {
            return BrainLine.unreadable();
        }
        return switch (event.path("type").asText("")) {
            // The terminal event. NO cost is published here by this brain — see the class javadoc.
            case "result" -> new BrainLine(OptionalDouble.empty(),
                StreamJsonEvents.textOf(event.path("result")), 0, true, List.of(),
                Optional.empty(), true,
                // cursor's usage block names its counts inputTokens/outputTokens/cacheReadTokens/
                // cacheWriteTokens; whether inputTokens already includes the cached part is not
                // established, so the cached part is added and the basis line says so.
                TokenUsage.read(event.path("usage"), "inputTokens", false,
                    new String[] {"cacheReadTokens", "cacheWriteTokens"}, "outputTokens", null,
                    "cursor: inputTokens counted without the cached part, which is added; whether cursor's own figure"
                        + " includes it is not established"));
            case "assistant" -> new BrainLine(OptionalDouble.empty(),
                StreamJsonEvents.assistantText(event), StreamJsonEvents.toolUseBlocks(event), true,
                StreamJsonEvents.toolUseNames(event), Optional.empty(), false, Optional.empty(),
                StreamJsonEvents.toolUseCalls(event));
            // THINKING IS NOT THE ANSWER, and it arrives as a stream of deltas — measured: four
            // `{"type":"thinking","subtype":"delta","text":...}` events for a one-word reply.
            // Collecting them would put the brain's reasoning into the record as though it were
            // what the brain said, and would do it in fragments.
            case "thinking", "system", "user" -> BrainLine.nothing();
            // An event shape this dialect does not act on: read, understood, nothing to say.
            default -> BrainLine.nothing();
        };
    }
}
