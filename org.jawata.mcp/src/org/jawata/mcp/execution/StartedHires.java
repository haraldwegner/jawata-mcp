package org.jawata.mcp.execution;

import java.io.IOException;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads what a {@code hire} tool call answered, from one tool-call update of a brain's stream (28h Stage 27, finding 28h-C27-1). A hired lead's
 * hire calls reach the engine under a caller id that is not the lead's plan id, since a brain over the agent protocol is not sure to present the
 * session header its endpoint was given, so the lead's own inbox could not tell which hires were its own; the call's answer says so first-hand, as
 * it does for the chat's own brain.
 */
public final class StartedHires {

    private static final ObjectMapper JSON = new ObjectMapper();

    private StartedHires() {
    }

    /**
     * The plan id a finished {@code hire} call answered with, when the answer says the hire is running; none for any other update.
     *
     * @param update a {@code tool_call_update} as the brain streamed it
     * @return the hire's plan id, or none
     */
    public static Optional<String> planOf(JsonNode update) {
        JsonNode answer = answerOf(update);
        if ("running".equals(answer.path("state").asText("")) && answer.hasNonNull("hireId")) {
            return Optional.of(answer.get("hireId").asText());
        }
        return Optional.empty();
    }

    /**
     * The hire tool's answer as JSON: the call's raw output, or the text of its content, with the tool's {@code data} wrapper removed; a missing
     * node when the update carries no answer that reads as JSON.
     *
     * @param update a {@code tool_call_update} as the brain streamed it
     * @return the answer
     */
    public static JsonNode answerOf(JsonNode update) {
        String text = null;
        JsonNode raw = update.path("rawOutput");
        if (raw.isTextual()) {
            text = raw.asText();
        } else if (raw.isObject()) {
            return raw.has("data") ? raw.get("data") : raw;
        } else {
            for (JsonNode block : update.path("content")) {
                String piece = block.path("content").path("text").asText("");
                if (!piece.isEmpty()) {
                    text = piece;
                    break;
                }
            }
        }
        if (text == null) {
            return JSON.missingNode();
        }
        try {
            JsonNode parsed = JSON.readTree(text);
            return parsed.has("data") ? parsed.get("data") : parsed;
        } catch (IOException e) {
            return JSON.missingNode();
        }
    }
}
