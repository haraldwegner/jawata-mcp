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
     */
    List<String> command(String prompt);

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
