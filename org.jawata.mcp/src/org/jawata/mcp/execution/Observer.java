package org.jawata.mcp.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The observer (Sprint 28h, S11, D13, D50a): it watches what a hired worker did against the instructions its issuer gave, and says when
 * the worker went around them. Two tiers, as the signed design has it.
 *
 * <p><b>The rule tier</b> is deterministic and always on: an instruction that did not run as written, or a code-changing call that no
 * instruction asked for, is a {@link Flag}. It reads {@link Instructions#score}, so "as written" means one thing in the PM's measure and
 * here. A worker that ran every instruction as written produces no flag, and no judge is asked.</p>
 *
 * <p><b>The judging tier</b> is a hired seat (the observer seat), reached through a {@link Judge}. It is asked about flagged steps only.
 * A step it says to halt becomes a {@link Correction} that names the number of the instruction it came from, and the correction goes to
 * the issuer, the pm, because the pm owns the instruction and not the developer who ran it (D13).</p>
 *
 * <p>An absent or unanswering judge is not a clear verdict: a flag nobody judged is reported as <em>unjudged</em> beside the corrections,
 * so "nothing was found" is never told for a step that was not looked at.</p>
 */
public final class Observer {

    /** "instruction 3: ..." as {@link Instructions#score} words a deviation: the number and the reason. */
    private static final Pattern NUMBERED = Pattern.compile("^instruction (\\d+): (.*)$");

    /** The observer seat's one-line answer: {@code VERDICT: CLEAR} or {@code VERDICT: HALT <n>: <correction>}. */
    private static final Pattern VERDICT = Pattern.compile("(?m)^\\s*VERDICT:\\s*(CLEAR|HALT)\\b[ \\t]*(?:\\d+[ \\t]*:)?[ \\t]*(.*)$");

    private Observer() {
    }

    /**
     * One step the rule tier flags.
     *
     * @param instruction the number of the instruction it deviates from, or 0 when it deviates from none (a code-changing call no
     *                    instruction asked for)
     * @param why         the deviation, in words
     */
    public record Flag(int instruction, String why) {
    }

    /**
     * What the judging tier said about one flag.
     *
     * @param halt       whether the step shows the worker going around its instruction
     * @param correction what to tell the issuer, or empty when nothing is to be said
     */
    public record Verdict(boolean halt, String correction) {
    }

    /** A correction for the issuer, carrying the number of the instruction it came from (0 for none). */
    public record Correction(int instruction, String text) {
    }

    /**
     * What one observation found.
     *
     * @param flagged     every flag the rule tier raised
     * @param corrections the corrections the judging tier gave, one per flag it halted
     * @param unjudged    the flags no judge answered, including every flag when there was no judge
     */
    public record Observation(List<Flag> flagged, List<Correction> corrections, List<Flag> unjudged) {
    }

    /** The judging tier: asked about one flag, with the instruction it deviates from (null for none) and the worker's calls. */
    @FunctionalInterface
    public interface Judge {
        /** The verdict on this flag, or empty when the judge could not answer. */
        Optional<Verdict> judge(Flag flag, Instructions.Instruction instruction, List<ToolCall> window);
    }

    /** The rule tier: the deviations {@link Instructions#score} found, as flags. */
    public static List<Flag> flags(Instructions.Score score) {
        List<Flag> flags = new ArrayList<>();
        for (String deviation : score.notAsWritten()) {
            Matcher numbered = NUMBERED.matcher(deviation);
            flags.add(numbered.matches()
                ? new Flag(Integer.parseInt(numbered.group(1)), numbered.group(2))
                : new Flag(0, deviation));
        }
        for (String call : score.extraCodeChangingCalls()) {
            flags.add(new Flag(0, "a code-changing call that no instruction asked for: " + call));
        }
        return List.copyOf(flags);
    }

    /**
     * What the judging tier is shown for one flagged step: the instruction, the deviation the rule tier found, and the worker's calls,
     * one line each. The answer it is asked for is the one line {@link #verdictOf} reads.
     */
    public static String briefFor(Flag flag, Instructions.Instruction instruction, List<ToolCall> window) {
        StringBuilder brief = new StringBuilder("An issuer gave a worker numbered instructions, and the worker may have gone around one of them. "
            + "Judge that and nothing else.\n\n");
        if (instruction == null) {
            brief.append("THE INSTRUCTION: none. The deviation is a call that no instruction asked for.\n");
        } else {
            brief.append("THE INSTRUCTION:\nINSTRUCTION ").append(instruction.number()).append("\nTOOL: ").append(instruction.tool())
                .append("\nARGUMENTS: ").append(instruction.arguments()).append('\n');
        }
        brief.append("THE DEVIATION THE RULES FOUND: ").append(flag.why()).append("\n\nTHE WORKER'S CALLS, in order:\n");
        for (int i = 0; i < window.size(); i++) {
            String arguments = String.valueOf(window.get(i).arguments());
            brief.append('#').append(i + 1).append(' ').append(window.get(i).name()).append(' ')
                .append(arguments.length() > 300 ? arguments.substring(0, 300) + "..." : arguments).append('\n');
        }
        brief.append("\nAnswer with exactly one line: VERDICT: CLEAR, or VERDICT: HALT <instruction number>: <the correction, addressed to the issuer, naming the call number>.");
        return brief.toString();
    }

    /** The judge's answer read as a verdict, or empty when it holds no verdict line. */
    public static Optional<Verdict> verdictOf(String answer) {
        if (answer == null) {
            return Optional.empty();
        }
        Matcher found = VERDICT.matcher(answer);
        if (!found.find()) {
            return Optional.empty();
        }
        boolean halt = "HALT".equals(found.group(1));
        return Optional.of(new Verdict(halt, halt ? found.group(2).trim() : ""));
    }

    /**
     * Observes one worker: flags by the rules, asks the judge about each flag, and returns what was found.
     *
     * @param judge the judging tier, or null when none is configured
     */
    public static Observation observe(List<Instructions.Instruction> issued, List<ToolCall> made,
            Function<String, Optional<Boolean>> jawataChanges, Judge judge) {
        List<Flag> flagged = flags(Instructions.score(issued, made, jawataChanges));
        List<Correction> corrections = new ArrayList<>();
        List<Flag> unjudged = new ArrayList<>();
        for (Flag flag : flagged) {
            Instructions.Instruction instruction = issued.stream().filter(i -> i.number() == flag.instruction()).findFirst().orElse(null);
            Optional<Verdict> verdict = judge == null ? Optional.empty() : judge.judge(flag, instruction, made);
            if (verdict.isEmpty()) {
                unjudged.add(flag);
            } else if (verdict.get().halt()) {
                corrections.add(new Correction(flag.instruction(), verdict.get().correction()));
            }
        }
        return new Observation(flagged, List.copyOf(corrections), List.copyOf(unjudged));
    }
}
