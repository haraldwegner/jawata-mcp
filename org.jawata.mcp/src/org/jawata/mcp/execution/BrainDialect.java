package org.jawata.mcp.execution;

import java.util.List;

/**
 * How ONE hired brain speaks — the per-brain output dialect (Sprint 28g S9a M5a).
 *
 * <p>Java has hired a child process before: {@code ForkedTestRunner} spawns a JVM and reads OUR OWN
 * event format back. What it has never done is hire a brain, and the difference is this interface.
 * A coding-agent CLI reports its progress, its cost and its tool calls in a shape nobody here
 * chose, and every brain chooses differently — which is why the dialect is the one genuinely new
 * thing on the Java side rather than a port. Measured before writing it:
 * {@code find_string_literals("stream-json")} returns ZERO matches over 1135 files, and so do the
 * cost and turn-count field names, so nothing in this product could read a brain today.</p>
 *
 * <h2>Why {@link #toolStepsOf} has no default</h2>
 *
 * <p>Because a bound only some callers reach is not a bound. If this interface defaulted the count
 * to zero, a brain whose adapter forgot to implement it would run with its step ceiling silently
 * unenforced, and nothing would fail. The Rust reference states the same rule in the same words
 * about the same method, and it is the one piece of that trait worth copying verbatim.</p>
 *
 * <h2>What a dialect must NOT do</h2>
 *
 * <p>It parses and it does not judge. It never decides a run is over, never applies an edit, never
 * trusts a line's claim about what the brain DID — a hired run PROPOSES, because nobody is watching
 * it. A tool-call count is evidence for a ceiling; it is not evidence that work happened. The only
 * thing that establishes a step passed is a gate jawata itself ran.</p>
 */
public interface BrainDialect {

    /** The dialect's own name, for a proposal record and a journal row. */
    String name();

    /**
     * The command line that asks this brain ONE question.
     *
     * <p>Returned as argv rather than a string, so nothing has to quote a prompt that contains a
     * newline, a quote or a shell metacharacter — the prompt is a whole seat brief and routinely
     * contains all three.</p>
     *
     * @param prompt the whole brief, travelling as one argument
     * @param tools the tools this run may use. <b>A dialect whose {@link #toolAttachment()} is not
     *     {@link ToolAttachment.Reach#PER_RUN} IGNORES this, and that is not a defect</b> — it is
     *     the measured truth about that CLI, which is why the hirer reads the attachment rather
     *     than assuming the list took effect. An empty list means "ask for nothing in
     *     particular", never "no tools": a brain with its own configured tools keeps them.
     */
    List<String> command(String prompt, List<String> tools);

    /**
     * How this brain can be told which tools to attach — or that it cannot be told (D36).
     *
     * <p><b>This exists so that a brain which cannot be told is REPORTED rather than quietly left
     * out of the count</b>, which is C16's clause verbatim. Measured 2026-10-08, the four brains
     * answer three different ways and one of them is {@code NONE}, so a seam that assumed an
     * allowlist would have made cursor-agent either unreportable or silently misreported.</p>
     */
    ToolAttachment toolAttachment();

    /**
     * What stops this brain writing as THIS dialect is configured — and whether that is a mechanism
     * or an instruction to the model (the architect's F1; S16-35).
     *
     * <p>NO DEFAULT, on the same rule {@link #toolAttachment()} follows: a default would let a fifth
     * brain ship without anyone having said what bounds its writes, which is how a flag credited
     * with a bound it does not have stayed credited for a checkpoint. The answer is read from a
     * fact the dialect already holds, and every {@code --flag} it cites must be one its
     * {@link #command} actually sends — a test sweeps that.</p>
     */
    WriteContainment writeContainment();

    /**
     * Does this dialect see EVERY tool call the brain makes, and by name?
     *
     * <p>The measure "this run performed no code-changing operation" is only as complete as the
     * stream it was read from. A dialect that counts a subset of the brain's actions (codex counts
     * only shell executions), cannot name what it counts (copilot), or was never measured on a run
     * that uses tools (cursor) leaves calls the classification cannot see, and a zero over those is
     * a clean bill for the runs it understood least. Defaults to false: a dialect must be MEASURED
     * to claim it, and a new brain is therefore incomplete until someone has looked.</p>
     */
    default boolean observesEveryCall() {
        return false;
    }

    /**
     * Read one line of this brain's output.
     *
     * <p>Must never throw: a hired brain can emit anything at all, including a stack trace from its
     * own crash, and a driver that dies on the child's bad line reports nothing about the child.
     * An unintelligible line is {@link BrainLine#unreadable()}, which is a fact rather than an
     * error.</p>
     */
    BrainLine read(String line);
}
