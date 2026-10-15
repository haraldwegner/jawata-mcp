package org.jawata.mcp.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Any executable as a hired brain (Sprint 28g S9a M5a) — the fixture vehicle and the escape hatch.
 *
 * <p>{@code command args… <prompt>}, output treated as plain text. It exists for two jobs: a test
 * can hire a shell script and drive the whole loop end to end without a model, an API key or a
 * network; and a CLI with no dialect of its own is still hireable rather than unsupported.</p>
 *
 * <h2>A plain-text brain DECLARES what a structured one emits</h2>
 *
 * <p>There are no events to read, so the two signals a ceiling needs are declared on their own
 * lines: {@code COST-USD: <n>} and {@code TOOL-STEPS: <n>}. A script that declares neither is taken
 * at its word — no cost seen, no steps — and that is the adapter's honest answer rather than a
 * silent pass. The distinction matters because {@link BrainLine#costUsd()} absent means <em>nothing
 * reported a cost</em>, which a driver must not round down to zero before deciding a cost ceiling
 * was respected.</p>
 *
 * <p><b>Every other line is text, and is READ rather than merely accepted.</b> A plain-text dialect
 * cannot have an unintelligible line, so {@link BrainLine#unreadable()} never arises here — which
 * is itself worth knowing: a driver counting unreadable lines will correctly count zero for a
 * script, and a non-zero count against this dialect would mean the driver, not the child, is
 * confused.</p>
 */
public final class ScriptDialect implements BrainDialect {

    private static final String COST_PREFIX = "COST-USD:";
    private static final String STEPS_PREFIX = "TOOL-STEPS:";

    private final String command;
    private final List<String> args;

    public ScriptDialect(String command, List<String> args) {
        this.command = command;
        this.args = args == null ? List.of() : List.copyOf(args);
    }

    @Override
    public String name() {
        return "script:" + command;
    }

    /**
     * {@code tools} is deliberately IGNORED here, and the ignoring is reported rather than
     * silent — see {@link #toolAttachment()}. An arbitrary script has no flag this class could
     * know, and inventing one (a {@code --tools} argument, an environment variable) would mean
     * every script that does not happen to read it gets a tool list it silently drops while the
     * run reports the tools as attached. That is the shape D36's reporting clause exists against.
     */
    @Override
    public List<String> command(String prompt, List<String> tools) {
        List<String> argv = new ArrayList<>();
        argv.add(command);
        argv.addAll(args);
        argv.add(prompt == null ? "" : prompt);
        return List.copyOf(argv);
    }

    /** A script writes whatever its own argv lets it; this hire adds no bound to that. */
    @Override
    public WriteContainment writeContainment() {
        return WriteContainment.none("the script's own argv decides what it can reach; this hire"
            + " adds no write bound of its own");
    }

    /**
     * A script cannot be told which tools to attach, and what it CAN be bounded by is the hirer's
     * own containment rather than anything this dialect passes.
     *
     * <p>The second string is required by {@link ToolAttachment} and is the honest answer rather
     * than the word "none": whoever configured the script chose its argv, so the tools it can
     * reach were decided there — and the hire's wall clock, cost and step ceilings still bound it
     * whatever it reaches for. Reporting a bare "unsupported" would read as "uncontained", which
     * is false.</p>
     */
    @Override
    public ToolAttachment toolAttachment() {
        return ToolAttachment.cannotBeTold(
            "the script's own argv, fixed when it was configured, plus the hire's wall-clock,"
                + " cost and step ceilings");
    }

    @Override
    public BrainLine read(String line) {
        if (line == null) {
            return BrainLine.nothing();
        }
        OptionalDouble cost = declaredNumber(line, COST_PREFIX);
        if (cost.isPresent()) {
            return new BrainLine(cost, Optional.empty(), 0, true);
        }
        OptionalDouble steps = declaredNumber(line, STEPS_PREFIX);
        if (steps.isPresent()) {
            return new BrainLine(OptionalDouble.empty(), Optional.empty(),
                (int) steps.getAsDouble(), true);
        }
        return new BrainLine(OptionalDouble.empty(),
            line.isEmpty() ? Optional.empty() : Optional.of(line), 0, true);
    }

    /** A declared numeric signal, or absent — a malformed number is absent, never zero. */
    private static OptionalDouble declaredNumber(String line, String prefix) {
        if (!line.startsWith(prefix)) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(Double.parseDouble(line.substring(prefix.length()).trim()));
        } catch (NumberFormatException e) {
            // A script that says COST-USD: banana has reported NO cost. Reading it as zero would
            // let a cost ceiling pass on a number nobody published.
            return OptionalDouble.empty();
        }
    }
}
