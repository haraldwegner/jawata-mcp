package org.jawata.mcp.execution;

import java.util.regex.Pattern;

/**
 * Leaves DEBUG and TRACE log lines out of a run's output tail (S19-20).
 *
 * <p>A test run's output tail is meant to show what went wrong. A library whose logging nobody
 * configured prints every step at DEBUG (design-patterns' page-object: 80 lines of "Mapping ... to
 * ...", and the line that names the cause was not among them: it was in the failure's message).
 * Those lines carry no news. When more of them follow a line that matters than the tail holds, the
 * 100 lines that are left are all DEBUG and the line is cut off, which is why the tail drops them
 * before it takes the last 100 lines, not after. A line is a DEBUG or TRACE log line when its
 * level word follows an optional time or
 * date-time and an optional {@code [thread]}: logback's default, slf4j-simple's and ISO
 * timestamps. A line that only mentions DEBUG later on is kept.</p>
 */
public final class LogNoise {

    private static final Pattern DEBUG_LINE = Pattern.compile(
        "^(?:\\d{2}:\\d{2}:\\d{2}[.,]\\d+\\s+|\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}[.,]\\d+\\s+)?"
            + "(?:\\[[^\\]]*\\]\\s+)?(?:DEBUG|TRACE)\\s.*$", Pattern.DOTALL);

    private LogNoise() {}

    /**
     * @param line one line of a run's output, with or without its line ending
     * @return whether it is a DEBUG or TRACE log line
     */
    public static boolean isNoise(String line) {
        return DEBUG_LINE.matcher(line).matches();
    }

}
