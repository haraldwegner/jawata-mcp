package org.jawata.mcp.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The PM's upward gate (Sprint 28g D13): <i>"an upward message is refused … for a count-as-reason,
 * an unnamed relation word, or a missing fact/cause/cost"</i>. Until this existed those were three
 * sentences in {@code pm.md} that the seat applied to its own message, and nothing refused a
 * message that broke them — the seat's file declared {@code gates: []}.
 *
 * <p><b>It reads one thing: the seat's answer.</b> A message is an upward message when the answer
 * carries a line naming itself so ({@code Upward}, in a heading or bold). No such line means none is
 * owed in this answer, and the gate passes: the PM says <i>"the upward message, if one is owed"</i>,
 * so demanding one every time would make the gate refuse an answer for the absence of a thing the
 * seat has no reason to send.</p>
 *
 * <p><b>It is a text check, and says what it can see.</b> Three deterministic rules over the message:</p>
 * <ul>
 *   <li><b>Fact, cause and cost</b>, each as a labelled element ({@code **Fact.**}, {@code Cause:},
 *       {@code - Cost —}). All three, every time.</li>
 *   <li><b>A count as the reason</b>: a cause that opens with a number, or one given as
 *       {@code because / since / due to <number> <things>}. What the numbers mean is the reason;
 *       the number is evidence for it.</li>
 *   <li><b>A relation word with no relation named</b>: {@code sibling, similar, same, related,
 *       equivalent} must be followed, within two words, by the word that names the relation
 *       ({@code as, to, in, with, of, than, from}). {@code "the same text as linux:55"} names it;
 *       {@code "a similar defect"} does not.</li>
 * </ul>
 *
 * <p>What this cannot judge is whether a stated cause is TRUE or a stated cost is the right one;
 * it refuses the shapes that leave the reader to guess, which is the failure the rule names.</p>
 */
public final class UpwardMessageGate {

    /** The step name a seat's {@code answer_gates} entry runs under, in the run's record. */
    public static final String STEP = "answer_gate:upward_message";

    private static final Pattern MESSAGE = Pattern.compile(
        "(?im)^\\s*(?:#{1,6}\\s*|\\*\\*|[-*]\\s*\\*\\*)?[^\\n]{0,40}\\bupward\\b");

    private static final String NUMBER_WORD =
        "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|several|many";

    private static final Pattern COUNT_LED_CAUSE = Pattern.compile(
        "(?i)^\\W*(\\d+|" + NUMBER_WORD + ")\\b");

    private static final Pattern COUNT_AS_BECAUSE = Pattern.compile(
        "(?i)\\b(because|since|due to)\\s+(\\d+|" + NUMBER_WORD + ")\\s+\\w+");

    private static final Pattern RELATION_WORD = Pattern.compile(
        "(?i)\\b(sibling|similar|same|related|equivalent)\\b");

    private static final Pattern RELATION_NAMED = Pattern.compile(
        "(?i)^\\s*(?:\\w+\\s+){0,2}(?:as|to|in|with|of|than|from)\\b");

    private UpwardMessageGate() {
    }

    /**
     * The reasons this answer's upward message is refused, each naming the rule and quoting what
     * broke it. Empty when no upward message is present or the one present is whole.
     */
    public static List<String> refusals(String answer) {
        List<String> out = new ArrayList<>();
        if (answer == null) {
            return out;
        }
        Matcher found = MESSAGE.matcher(answer);
        if (!found.find()) {
            return out;
        }
        String message = answer.substring(found.start());

        int[] fact = labelAt(message, "fact");
        int[] cause = labelAt(message, "cause");
        int[] cost = labelAt(message, "cost");
        if (fact == null) {
            out.add("omits the FACT: no element labelled Fact in the upward message");
        }
        if (cause == null) {
            out.add("omits the CAUSE: a fact with no cause cannot be decided on");
        }
        if (cost == null) {
            out.add("omits the COST: a cause with no cost cannot be weighed");
        }
        if (cause != null) {
            String causeText = elementText(message, cause[1]);
            Matcher led = COUNT_LED_CAUSE.matcher(causeText);
            Matcher because = COUNT_AS_BECAUSE.matcher(causeText);
            if (led.find()) {
                out.add("gives a COUNT as the reason: the cause opens with \"" + led.group(1)
                    + "\"; what the findings mean is the reason, the number is only evidence for it");
            } else if (because.find()) {
                out.add("gives a COUNT as the reason: \"" + because.group().strip() + "\"");
            }
        }
        Matcher relation = RELATION_WORD.matcher(message);
        while (relation.find()) {
            String after = message.substring(relation.end(), Math.min(message.length(),
                relation.end() + 60));
            if (!RELATION_NAMED.matcher(after).find()) {
                out.add("uses a RELATION WORD without naming the relation: \"" + relation.group()
                    + "\" (name the relation — \"same as X\" — or drop the word)");
            }
        }
        return out;
    }

    /**
     * Where a labelled element ({@code **Fact.**}, {@code Fact:}, {@code - Fact —}) starts and where
     * its label ends, or null when the message has none.
     */
    private static int[] labelAt(String message, String label) {
        Matcher m = Pattern.compile(
            "(?i)(?:\\*\\*\\s*" + label + "\\s*[.:—-]?\\s*\\*\\*|(?:^|[\\s(*_-])" + label
                + "\\s*[.:—-])").matcher(message);
        return m.find() ? new int[] {m.start(), m.end()} : null;
    }

    /** The text of the element whose label ended at {@code from}, up to the next label or 600 characters. */
    private static String elementText(String message, int from) {
        String rest = message.substring(from, Math.min(message.length(), from + 600));
        Matcher next = Pattern.compile("(?i)(?:\\*\\*\\s*(?:fact|cause|cost)\\b|\\n\\s*[-*]\\s*\\*\\*)")
            .matcher(rest);
        return next.find() ? rest.substring(0, next.start()) : rest;
    }
}
