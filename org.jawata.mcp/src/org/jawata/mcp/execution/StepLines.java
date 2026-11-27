package org.jawata.mcp.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The progress lines of one running hire (28h z85, z166), taken from its brain's text as it streams: the finished lines that start
 * {@code STEP:}, without the mark, and the line still being written. Only the new text of each chunk is read, so what a hire has written
 * before is not gone through again, and text that is no progress line is not kept. A line is finished by its newline, or by the hire's next
 * tool call: a brain need not send a newline before it calls a tool.
 */
final class StepLines {

    private static final String MARK = "STEP:";

    private final List<String> finished = new ArrayList<>();
    private final StringBuilder open = new StringBuilder();

    /** Takes the next piece of what the brain wrote; true when it finished a progress line. */
    synchronized boolean add(String chunk) {
        int before = finished.size();
        int from = 0;
        for (int end = chunk.indexOf('\n'); end >= 0; end = chunk.indexOf('\n', from)) {
            open.append(chunk, from, end);
            endLine();
            from = end + 1;
        }
        open.append(chunk, from, chunk.length());
        return finished.size() > before;
    }

    /** Ends the line being written; true when that line was a progress line. */
    synchronized boolean endLine() {
        Optional<String> line = stepIn(open);
        open.setLength(0);
        line.ifPresent(finished::add);
        return line.isPresent();
    }

    /** Every progress line in the order written; one still being written is included, as far as it has come. */
    synchronized List<String> all() {
        List<String> lines = new ArrayList<>(finished);
        stepIn(open).ifPresent(lines::add);
        return List.copyOf(lines);
    }

    /** The last finished progress line; none before the first. */
    synchronized Optional<String> latest() {
        return finished.isEmpty() ? Optional.empty() : Optional.of(finished.get(finished.size() - 1));
    }

    /** The words of this line when it is a progress line that says something. */
    private static Optional<String> stepIn(CharSequence text) {
        String line = text.toString().strip();
        return line.startsWith(MARK) ? Optional.of(line.substring(MARK.length()).strip()).filter(words -> !words.isEmpty()) : Optional.empty();
    }
}
