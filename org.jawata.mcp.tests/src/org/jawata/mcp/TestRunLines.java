package org.jawata.mcp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lines the in-framework runner prints WHILE a run is still going, for the events a person
 * watching a long run needs at once.
 *
 * <p>A skipped test already printed {@code ~~ ABORTED name: reason} the moment it was skipped. A
 * FAILED test printed nothing: its class finishes successfully as a container, and the failure
 * reached the log only in the summary after the whole suite. On 2026-10-18 a failing class ran
 * 3.5 minutes into a 34-minute job and the job said nothing for the other 30. The failure line has
 * its own prefix, {@code ~~ FAILED}, so the abort budget, which reads {@code ~~ ABORTED}, never
 * counts a failure as a skip.</p>
 */
final class TestRunLines {

    private static final String PREFIX = "        ~~ FAILED ";
    private static final int MAX_REASON = 300;
    private static final Pattern CLASS_AND_METHOD =
        Pattern.compile("\\[class:([^\\]]+)\\]/\\[method:([^\\]]+)\\]");

    private TestRunLines() {
    }

    /**
     * The line for one failed test method: which test, and why in one line. The reason is the
     * cause's first line only, cut to a few hundred characters, because an assertion message can
     * run to a whole answer and the full text is still printed in the summary.
     */
    static String failed(String uniqueId, Throwable cause) {
        return PREFIX + shortName(uniqueId) + ": " + oneLine(cause);
    }

    /** {@code pkg.Class#method(...)} from JUnit's unique id, or the id itself when it has no such shape. */
    static String shortName(String uniqueId) {
        Matcher m = CLASS_AND_METHOD.matcher(uniqueId == null ? "" : uniqueId);
        return m.find() ? m.group(1) + "#" + m.group(2) : String.valueOf(uniqueId);
    }

    private static String oneLine(Throwable cause) {
        if (cause == null) {
            return "(no reason given)";
        }
        String text = String.valueOf(cause);
        int newline = text.indexOf('\n');
        String first = newline >= 0 ? text.substring(0, newline) : text;
        return first.length() > MAX_REASON ? first.substring(0, MAX_REASON) + "..." : first;
    }
}
