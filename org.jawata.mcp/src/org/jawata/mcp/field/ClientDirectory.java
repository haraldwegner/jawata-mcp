package org.jawata.mcp.field;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session → client attribution (Sprint 28b, D1). The MCP initialize handshake
 * carries {@code clientInfo.name}; the protocol handler records it here keyed
 * by the transport's session id, and the {@link FieldRecorder} resolves it per
 * call. Names are tokenized on the way in — hyphens and dots become
 * underscores so real client names ("claude-code", "cursor") survive the
 * whitelist; anything else coerces to {@code unknown}.
 */
public final class ClientDirectory {

    private final Map<String, Token> bySession = new ConcurrentHashMap<>();

    /** Records the initializing session's client name. */
    public void record(String sessionId, String clientName) {
        if (sessionId == null) {
            return;
        }
        String normalized = clientName == null
            ? null
            : clientName.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        bySession.put(sessionId, Token.of(normalized));
    }

    /** The session's client token; {@link Token#UNKNOWN} for an unseen session. */
    public Token clientOf(String sessionId) {
        Token token = sessionId == null ? null : bySession.get(sessionId);
        return token == null ? Token.UNKNOWN : token;
    }
}
