package org.jawata.mcp.execution;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Everything one line of a hired brain's output carries — read ONCE (Sprint 28g S9a M5a).
 *
 * <p>A hired brain is a coding-agent CLI jawata spawns and drives. Each writes its progress as
 * lines on stdout in its own dialect, and this record is what a {@link BrainDialect} makes of one
 * such line.</p>
 *
 * <h2>Why one record rather than three parse calls</h2>
 *
 * <p>The Rust reference this is ported from ({@code jawata-studio/src-tauri/src/runner.rs}, the
 * {@code CliAdapter} trait) exposes {@code parse_cost}, {@code parse_text} and
 * {@code parse_tool_steps} as three separate methods, and the driver calls them on the same line —
 * so one line is deserialised up to three times, and the three answers can disagree about what the
 * line even was. One read, one record, and the disagreement is unrepresentable.</p>
 *
 * <h2>{@code parsed} is the half the reference loses</h2>
 *
 * <p>Each of the three Rust readers returns an absent value both for <em>this line carries no
 * cost</em> and for <em>this line is not JSON at all</em>, which are different facts: the first is
 * ordinary and the second means the child is not speaking the dialect we think it is. The same
 * conflation is written into {@code ForkedTestRunner$EventTailer}, which drops an unparsable line in
 * an empty catch. {@link #parsed()} separates them, so a driver can count lines it could not read
 * and a run against the wrong dialect announces itself instead of looking like a quiet one.</p>
 *
 * @param costUsd   the cost this line reports, absent when it reports none. ABSENT IS NOT ZERO:
 *                  Rust defect #2 is that its reader only ever sees the terminal event, so a run
 *                  reaped on its wall clock books 0.00 and the cost ceiling cannot fire on exactly
 *                  the expensive runs. A driver must keep "no cost seen yet" distinct from "the
 *                  cost was zero", which this type makes possible and does not by itself enforce
 * @param text      the answer text this line carries, absent when it carries none
 * @param toolSteps how many TOOL CALLS this line reports — blocks, not events, because one
 *                  assistant turn may contain several and a turn ceiling cannot see inside it
 * @param parsed    whether the line was intelligible to the dialect at all
 * @param toolNames the NAMES of the tools this line's calls invoked, in order. May be SHORTER than
 *                  {@code toolSteps}: a dialect that counts calls but does not see their names
 *                  (codex, copilot) reports a count and an empty list, and a reader must treat the
 *                  difference as calls it could not name rather than as calls that were harmless.
 *                  The whole reason this exists is that a count cannot be classified — "the seat
 *                  made no code-changing call" is a claim about WHICH calls, and a number of calls
 *                  answers none of it
 * @param terminal  whether this is the brain's CLOSING line — the result event that restates the
 *                  answer its earlier lines already gave. A driver keeps its text only when nothing
 *                  else was said (S16-18): a one-turn reply is in both the assistant line and the
 *                  closing one, and collecting both made "READY" read "READY\nREADY"
 */
public record BrainLine(OptionalDouble costUsd, Optional<String> text, int toolSteps,
                        boolean parsed, java.util.List<String> toolNames, java.util.Optional<java.util.List<String>> toolsOffered, boolean terminal, java.util.Optional<TokenUsage> tokens, java.util.List<ToolCall> toolCalls) {

    /** The shape every dialect used before names were recorded; callers that cannot name keep it. */
    public BrainLine(OptionalDouble costUsd, Optional<String> text, int toolSteps,
                     boolean parsed) {
        this(costUsd, text, toolSteps, parsed, java.util.List.of());
    }

    /** A line the dialect could not read at all — not JSON, or not this dialect's shape. */
    public static BrainLine unreadable() {
        return new BrainLine(OptionalDouble.empty(), Optional.empty(), 0, false);
    }

    /** A line that was read and carried nothing a driver acts on. */
    public static BrainLine nothing() {
        return new BrainLine(OptionalDouble.empty(), Optional.empty(), 0, true);
    }

    public BrainLine(OptionalDouble costUsd, Optional<String> text, int toolSteps, boolean parsed, java.util.List<String> toolNames) {
        this(costUsd, text, toolSteps, parsed, toolNames, java.util.Optional.empty());
    }

    public BrainLine(OptionalDouble costUsd, Optional<String> text, int toolSteps, boolean parsed, java.util.List<String> toolNames, java.util.Optional<java.util.List<String>> toolsOffered) {
        this(costUsd, text, toolSteps, parsed, toolNames, toolsOffered, false);
    }

    public BrainLine(OptionalDouble costUsd, Optional<String> text, int toolSteps, boolean parsed, java.util.List<String> toolNames, java.util.Optional<java.util.List<String>> toolsOffered, boolean terminal) {
        this(costUsd, text, toolSteps, parsed, toolNames, toolsOffered, terminal, java.util.Optional.empty());
    }

    public BrainLine(OptionalDouble costUsd, Optional<String> text, int toolSteps, boolean parsed, java.util.List<String> toolNames, java.util.Optional<java.util.List<String>> toolsOffered, boolean terminal, java.util.Optional<TokenUsage> tokens) {
        this(costUsd, text, toolSteps, parsed, toolNames, toolsOffered, terminal, tokens, java.util.List.of());
    }
}
