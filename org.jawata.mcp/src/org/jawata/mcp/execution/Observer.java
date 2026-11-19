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
 * <p><b>The judging tier</b> is a hired seat (the observer seat), reached through a {@link BatchJudge}. It is asked about flagged steps only,
 * and ONCE for a whole run over every flag the rules raised, never once per flag (Stage 27 (z1)). A point it says to halt becomes a
 * {@link Correction} that names the number of the instruction it came from, and the corrections go to the issuer, the pm, because the pm
 * owns the instruction and not the developer who ran it (D13).</p>
 *
 * <p>An absent or unanswering judge is not a clear verdict: a flag nobody judged is reported as <em>unjudged</em> beside the corrections,
 * so "nothing was found" is never told for a step that was not looked at.</p>
 */
public final class Observer {

    /** "instruction 3: ..." as {@link Instructions#score} words a deviation: the number and the reason. */
    private static final Pattern NUMBERED = Pattern.compile("^instruction (\\d+): (.*)$");

    /** One point of the judge's answer about a whole run: {@code VERDICT: CLEAR 5} or {@code VERDICT: HALT 3: <correction>}, the number optional. */
    private static final Pattern POINT = Pattern.compile("(?m)^\\s*VERDICT:\\s*(CLEAR|HALT)\\b[ \\t]*(?:(\\d+)[ \\t]*:?)?[ \\t]*(.*)$");

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

    /**
     * One point of the judge's one answer about a run (Stage 27 (z1)).
     *
     * @param pointNumber the number of the point it answers: the place of a flag in the list the judge was shown, counted from 1, so that two
     *                    flags about the same instruction, or about none, are told apart; 0 when the answer gave no number
     * @param halt        whether the step shows the worker going around its instruction
     * @param correction  what to tell the issuer, or empty when nothing is to be said
     */
    public record Point(int pointNumber, boolean halt, String correction) {
    }

    /** The judging tier asked once about everything a run was flagged for: the flags, the instructions and the worker's calls. */
    @FunctionalInterface
    public interface BatchJudge {
        /** One point per flag it can judge, or empty when the judge could not answer at all. */
        Optional<List<Point>> judge(List<Flag> flags, List<Instructions.Instruction> issued, List<ToolCall> window);
    }

    /**
     * Observes one run (Stage 27 (z1)): the rule tier's flags and {@code more} flags from other rules are collected, and the judge is asked ONCE
     * about all of them when there is at least one, never once per flag.
     * @param more  flags other rules raised over the same run, such as production code written before its test was seen failing
     * @param judge the judging tier, or null when none is configured
     * @param editorWritesAreWork TODO
     */
    public static Observation observe(List<Instructions.Instruction> issued, List<ToolCall> made,
            Function<String, Optional<Boolean>> jawataChanges, List<Flag> more, BatchJudge judge, boolean editorWritesAreWork) {
        // With no tool instruction nothing was asked for, so a code-changing call is not one no instruction asked for: a worker briefed only with
        // BUILD instructions writes code as its work (Stage 27 (z5)), and the flags of other rules are all that is left to judge.
        List<Flag> flagged = new ArrayList<>(issued.isEmpty() ? List.of() : flags(Instructions.score(issued, made, jawataChanges)));
        flagged.addAll(more);
        if (flagged.isEmpty()) {
            return new Observation(List.of(), List.of(), List.of());
        }
        Optional<List<Point>> answered = judge == null ? Optional.empty() : judge.judge(List.copyOf(flagged), issued, made);
        if (answered.isEmpty()) {
            return new Observation(List.copyOf(flagged), List.of(), List.copyOf(flagged));
        }
        // A point answers one flag, by its place in the list the judge was shown (C27 audit): two flags about the same instruction, or about none,
        // are told apart, and a verdict that names no point judges no flag.
        List<Correction> corrections = answered.get().stream().filter(Point::halt)
            .map(p -> new Correction(p.pointNumber() >= 1 && p.pointNumber() <= flagged.size() ? flagged.get(p.pointNumber() - 1).instruction() : 0,
                p.correction())).toList();
        List<Integer> judged = answered.get().stream().map(Point::pointNumber).toList();
        List<Flag> unjudged = new ArrayList<>();
        for (int n = 1; n <= flagged.size(); n++) {
            if (!judged.contains(n)) {
                unjudged.add(flagged.get(n - 1));
            }
        }
        return new Observation(List.copyOf(flagged), corrections, List.copyOf(unjudged));
    }

    /** What the judge is shown about a whole run: every flag by its instruction number, the instructions and the worker's calls. */
    public static String briefFor(List<Flag> flags, List<Instructions.Instruction> issued, List<ToolCall> window) {
        StringBuilder brief = new StringBuilder("An issuer gave a worker numbered instructions, and the worker may have gone around some of them. "
            + "Judge each point below and nothing else.\n\nTHE INSTRUCTIONS THE POINTS ARE ABOUT:\n");
        List<Integer> numbers = flags.stream().map(Flag::instruction).distinct().toList();
        for (Instructions.Instruction instruction : issued) {
            if (numbers.contains(instruction.number())) {
                brief.append("INSTRUCTION ").append(instruction.number()).append("\nTOOL: ").append(instruction.tool())
                    .append("\nARGUMENTS: ").append(instruction.arguments()).append("\n\n");
            }
        }
        brief.append("THE POINTS THE RULES FOUND, each by its point number and the instruction it is about (0 is no instruction):\n");
        for (int n = 1; n <= flags.size(); n++) {
            brief.append("- POINT ").append(n).append(" (INSTRUCTION ").append(flags.get(n - 1).instruction()).append("): ")
                .append(flags.get(n - 1).why()).append('\n');
        }
        brief.append("\nTHE WORKER'S CALLS, in order:\n");
        for (int i = 0; i < window.size(); i++) {
            String arguments = String.valueOf(window.get(i).arguments());
            brief.append('#').append(i + 1).append(' ').append(window.get(i).name()).append(' ')
                .append(arguments.length() > 300 ? arguments.substring(0, 300) + "..." : arguments).append('\n');
        }
        brief.append("\nAnswer with one line per point: VERDICT: CLEAR <point number>, or VERDICT: HALT <point number>: "
            + "<the correction, addressed to the issuer, naming the call number>.");
        return brief.toString();
    }

    /** The points the judge's answer holds, one per {@code VERDICT:} line; empty when it holds none. */
    public static List<Point> pointsOf(String answer) {
        List<Point> points = new ArrayList<>();
        if (answer == null) {
            return points;
        }
        Matcher found = POINT.matcher(answer);
        while (found.find()) {
            boolean halt = "HALT".equals(found.group(1));
            points.add(new Point(found.group(2) == null ? 0 : Integer.parseInt(found.group(2)), halt, halt ? found.group(3).trim() : ""));
        }
        return points;
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
}
