package org.jawata.mcp.field;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.mcp.models.ToolResponse;

/**
 * The D1 recording consumer (Sprint 28b): installed on the Sprint-26
 * {@code EventTap} beside the experience-loop recorder, so every tool outcome
 * — success and structured error alike — becomes one sanitized
 * {@link FieldEvent} in the local {@link FieldPile} as a side effect of the
 * call itself. Nothing here can fail the observed call, and nothing here can
 * store content: every value crosses the {@link Token} whitelist.
 */
public final class FieldRecorder {

    private final FieldPile pile;
    private final ClientDirectory clients;
    private final Token version;

    /** @param version the jawata version, pre-tokenized (dots → underscores). */
    public FieldRecorder(FieldPile pile, ClientDirectory clients, String version) {
        this.pile = pile;
        this.clients = clients;
        this.version = Token.of(version == null
            ? null
            : version.toLowerCase().replaceAll("[^a-z0-9_]", "_"));
    }

    /** The pile this recorder writes (status surfaces + tests). */
    public FieldPile pile() {
        return pile;
    }

    /** Records one completed call. Kind is the tool's own discriminator
     *  argument where one exists ({@code kind}, {@code action}, or
     *  {@code direction}); the value is sanitized by {@link Token#of}. */
    public void onCall(String sessionId, String name, JsonNode arguments,
            ToolResponse response, long durationMs) {
        String rawKind = null;
        if (arguments != null) {
            for (String key : new String[] {"kind", "action", "direction"}) {
                String value = arguments.path(key).asText(null);
                if (value != null && !value.isEmpty()) {
                    rawKind = value;
                    break;
                }
            }
        }
        pile.append(new FieldEvent(
            System.currentTimeMillis(),
            Token.of(name),
            Token.of(rawKind),
            response != null && response.isSuccess(),
            ErrorCodes.of(response),
            FieldEvent.bucket(durationMs),
            clients.clientOf(sessionId),
            version));
    }
}
