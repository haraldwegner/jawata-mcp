package org.jawata.mcp.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Leaves DEBUG and TRACE log lines out of a run's output tail (S19-20).
 *
 * <p>A test run's output tail is meant to show what went wrong. A library whose logging nobody
 * configured prints every step at DEBUG (design-patterns' page-object: 80 lines of "Mapping ... to
 * ...") and those fill the 100 lines that are left, so the one INFO line with the cause is cut
 * off. A line is a DEBUG or TRACE log line when its level word follows an optional time or
 * date-time and an optional {@code [thread]}: logback's default, slf4j-simple's and ISO
 * timestamps. A line that only mentions DEBUG later on is kept.</p>
 */
public final class LogNoise {

    private static final Pattern DEBUG_LINE = Pattern.compile(
        "^(?:\\d{2}:\\d{2}:\\d{2}[.,]\\d+\\s+|\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}[.,]\\d+\\s+)?"
            + "(?:\\[[^\\]]*\\]\\s+)?(?:DEBUG|TRACE)\\s.*$", Pattern.DOTALL);

    private LogNoise() {}

    /**
     * @param text a run's captured output
     * @return the text without its DEBUG and TRACE log lines and a last line saying how many were
     *     left out; the text as it came when it holds none, or when nothing else would be left
     */
    public static String withoutDebug(String text) {
        List<String> kept = new ArrayList<>();
        int dropped = 0;
        for (String line : text.split("\n", -1)) {
            if (DEBUG_LINE.matcher(line).matches()) {
                dropped++;
            } else {
                kept.add(line);
            }
        }
        boolean somethingElse = kept.stream().anyMatch(l -> !l.isBlank());
        if (dropped == 0 || !somethingElse) {
            return text;
        }
        while (!kept.isEmpty() && kept.get(kept.size() - 1).isEmpty()) {
            kept.remove(kept.size() - 1);
        }
        kept.add("[" + dropped + " DEBUG/TRACE lines left out]");
        return String.join("\n", kept) + "\n";
    }
}
