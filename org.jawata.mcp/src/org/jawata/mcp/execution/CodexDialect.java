package org.jawata.mcp.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Codex CLI headless, as a dialect (Sprint 28g S16, D36) — <b>the brain whose tools can be set, but
 * not by the hirer and not for one run.</b>
 *
 * <p>Measured 2026-10-08 from {@code codex exec --help} and then by RUNNING it, twice: once with a
 * prompt needing no tools, and once with a prompt that forced a shell call, because the tool-call
 * event shape is not stated anywhere and a step ceiling built on a guessed event name is a ceiling
 * that never fires.</p>
 *
 * <h2>Why {@link ToolAttachment.Reach#OUT_OF_BAND} and not {@code NONE}</h2>
 *
 * <p>Its tools genuinely CAN be set — {@code codex mcp} manages servers and {@code -c key=value}
 * overrides config — but both happen before the hire and apply to every run. The hirer can REPORT
 * what is attached and cannot choose it per brief, which is a materially different promise from
 * claude's and copilot's per-run flags and is exactly why the reach has three values rather than
 * two. Reporting this brain as unsettable would be as wrong as reporting it as settable.</p>
 *
 * <h2>What it CAN be told per run is a sandbox, and that is passed</h2>
 *
 * <p>{@code -s/--sandbox read-only|workspace-write|danger-full-access} is a real per-run
 * containment, and {@code read-only} is the default here for the reason {@link CursorDialect} gives
 * for {@code --mode plan}: {@link HiredBrain}'s own contract is that a hired run PROPOSES, so a
 * brain that may not apply anything should not be able to.</p>
 *
 * <h2>Two things the runs found that no {@code --help} states</h2>
 *
 * <p><b>It refuses to start outside a trusted directory.</b> First run: <i>"Not inside a trusted
 * directory and {@code --skip-git-repo-check} was not specified"</i>, exit 1, no events. A dialect
 * omitting the flag hires a brain that never runs, and the symptom is an EMPTY ANSWER — the state
 * {@link HiredBrain.Outcome#saidNothing()} exists to keep apart from genuine silence.</p>
 *
 * <p><b>It writes an {@code ERROR} line to stderr on a SUCCESSFUL run</b> — measured:
 * {@code failed to load models cache: missing field supports_parallel_tool_calls} — while its stdout
 * is pure JSONL, with zero non-JSON lines on a clean run. That is {@link HiredBrain}'s decision to
 * keep stderr SEPARATE vindicated by a real brain: merged, this dialect would have counted
 * unreadable lines and a driver could have read a clean run's stderr as a crash.</p>
 *
 * <h2>Its text arrives in PIECES, and its tool calls arrive TWICE</h2>
 *
 * <p>Measured on the tool-using run: {@code agent_message} appeared twice — once before the shell
 * call and once after — so the answer is the JOIN of those items and not the last of them. And one
 * shell call produced BOTH an {@code item.started} and an {@code item.completed} for the same item
 * id, so counting every non-message item would double every tool call and halve the effective step
 * ceiling. Only {@code item.completed} is counted.</p>
 *
 * <h2>IT PUBLISHES NO DOLLAR COST</h2>
 *
 * <p>{@code turn.completed} carries {@code usage.{input_tokens,cached_input_tokens,
 * cache_write_input_tokens,output_tokens,reasoning_output_tokens}} and no currency figure, so
 * {@link BrainLine#costUsd()} is always ABSENT here and
 * {@link HiredBrain.Ceilings#maxCostUsd()} cannot fire on this brain. Token counts are not dollars
 * and are not published as though they were.</p>
 */
public final class CodexDialect implements BrainDialect {

    /** The item type a completed shell call arrives under — measured, not assumed. */
    private static final String TOOL_ITEM = "command_execution";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String binary;
    private final String sandbox;

    /** Read-only, which is what a run that may not apply anything should be. */
    public CodexDialect() {
        this("codex", "read-only");
    }

    /**
     * @param sandbox the per-run sandbox policy: {@code read-only}, {@code workspace-write} or
     *     {@code danger-full-access}. Not validated here — the CLI owns that list, and a copy of it
     *     in this class would be one more place for it to go stale.
     */
    public CodexDialect(String binary, String sandbox) {
        this.binary = binary == null || binary.isBlank() ? "codex" : binary;
        this.sandbox = sandbox == null || sandbox.isBlank() ? "read-only" : sandbox;
    }

    @Override
    public String name() {
        return "codex";
    }

    /**
     * {@code tools} is IGNORED, and the ignoring is reported rather than silent — see
     * {@link #toolAttachment()}. This CLI has no per-run tool allowlist; its servers are configured
     * by {@code codex mcp} before the hire, and writing that config from here would change what
     * every other run of this brain on this machine can reach.
     */
    @Override
    public List<String> command(String prompt, List<String> tools) {
        List<String> argv = new ArrayList<>(List.of(
            binary, "exec",
            // Measured: 'Print events to stdout as JSONL'.
            "--json",
            "--sandbox", sandbox,
            // WITHOUT THIS IT NEVER STARTS outside a trusted directory — see the class javadoc.
            "--skip-git-repo-check"));
        // The prompt travels LAST and as one argument, so a brief carrying newlines, quotes or
        // shell metacharacters needs no escaping and cannot be mis-escaped.
        argv.add(prompt == null ? "" : prompt);
        return List.copyOf(argv);
    }

    /** The CLI's own sandbox, observed rejecting an attempted apply_patch — a mechanism while read-only. */
    @Override
    public WriteContainment writeContainment() {
        if ("read-only".equals(sandbox)) {
            return WriteContainment.mechanism("--sandbox read-only: the CLI's own sandbox, observed"
                + " rejecting an attempted apply_patch");
        }
        return WriteContainment.none("--sandbox " + sandbox + " permits writes, by explicit request");
    }

    @Override
    public ToolAttachment toolAttachment() {
        return ToolAttachment.outOfBand("codex mcp (servers) and -c key=value (config), both before"
            + " the hire and shared by every run; per run this hire passes --sandbox " + sandbox);
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
            return BrainLine.unreadable();
        }
        if (event == null || !event.isObject()) {
            return BrainLine.unreadable();
        }
        // ONLY the completed item. A tool call also arrives as `item.started` with the same id, and
        // counting both would double every step — see the class javadoc.
        if ("item.completed".equals(event.path("type").asText(""))) {
            JsonNode item = event.path("item");
            String kind = item.path("type").asText("");
            if (TOOL_ITEM.equals(kind)) {
                return new BrainLine(OptionalDouble.empty(), java.util.Optional.empty(), 1, true);
            }
            if ("agent_message".equals(kind)) {
                // One of possibly SEVERAL. The driver joins what each line reports, which is why
                // this returns its own piece rather than trying to be the whole answer.
                return new BrainLine(OptionalDouble.empty(),
                    StreamJsonEvents.textOf(item.path("text")), 0, true);
            }
            // A completed item of a kind this dialect has not measured. Read, understood, and
            // deliberately NOT counted as a tool step: inventing a step for an unknown item type
            // would make the ceiling fire on events nobody has seen.
            return BrainLine.nothing();
        }
        // thread.started, turn.started, item.started, and turn.completed — which is terminal and
        // carries token counts rather than a cost, so there is nothing here to publish.
        return BrainLine.nothing();
    }
}
