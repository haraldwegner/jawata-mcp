package org.jawata.mcp.execution;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the engine's {@code hire} tool answered for each hire it ran (Sprint 28h, S17): the answer as the hire tool built it, kept so the
 * agent face can show a hire's outcome, tokens, cost and changed files even when the hiring brain's stream does not carry the answer back.
 * Cursor's agent reports the end of a tool call with {@code {"success": true}} and nothing else, so a face that only reads the brain's echo
 * shows every hire it ran as done, including one the hire tool refused. The engine runs the hire itself, so it is the first-hand source.
 *
 * <p>Hires are matched by seat and brief, as {@link RunningHires} matches a running hire. The record is bounded: the newest
 * {@value #KEPT} answers are kept, and a later hire of the same seat and brief replaces the earlier answer.</p>
 */
public final class FinishedHires {

    /** How many answers are kept. */
    static final int KEPT = 200;

    private static final FinishedHires SHARED = new FinishedHires();

    private final Map<String, JsonNode> answers = new LinkedHashMap<>();

    /** The one record of this engine: the hire tool writes it and the agent face reads it. */
    public static FinishedHires shared() {
        return SHARED;
    }

    /** Keeps the answer of the hire of this seat for this brief, replacing an earlier one and dropping the oldest past the bound. */
    public synchronized void record(String seat, String brief, JsonNode answer) {
        String key = keyOf(seat, brief);
        answers.remove(key);
        answers.put(key, answer);
        while (answers.size() > KEPT) {
            answers.remove(answers.keySet().iterator().next());
        }
    }

    /** The answer of the latest hire of this seat for this brief, or none when this engine ran no such hire. */
    public synchronized Optional<JsonNode> answerOf(String seat, String brief) {
        return Optional.ofNullable(answers.get(keyOf(seat, brief)));
    }

    private static String keyOf(String seat, String brief) {
        return seat + "\u0000" + brief.strip();
    }
}
