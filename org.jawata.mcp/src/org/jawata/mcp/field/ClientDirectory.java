package org.jawata.mcp.field;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session → client attribution (Sprint 28b, D1). The MCP initialize handshake
 * carries {@code clientInfo.name}; the protocol handler records it here keyed
 * by the transport's session id, and the {@link FieldRecorder} resolves it per
 * call.
 *
 * <p>Attribution is a CLOSED VOCABULARY, never a transform (C1 audit F2): a
 * transform like "replace punctuation with underscores" preserves the content
 * it was supposed to reject — a workspace name or a secret survives modulo
 * punctuation. Here an unrecognized client name contributes nothing but
 * {@code unknown}; no character of it is ever stored.</p>
 */
public final class ClientDirectory {

    /** Known client families, matched as case-insensitive substrings of the
     *  reported name ("claude-code", "Claude Code", "cursor-agent" …). */
    private static final String[][] KNOWN = {
        {"claude", "claude_code"},
        {"cursor", "cursor"},
        {"codex", "codex"},
        {"copilot", "copilot"},
        {"grok", "grok"},
        {"windsurf", "windsurf"},
        {"zed", "zed"},
    };

    private final Map<String, Token> bySession = new ConcurrentHashMap<>();

    /** Records the initializing session's client, by closed-vocabulary match. */
    public void record(String sessionId, String clientName) {
        if (sessionId == null) {
            return;
        }
        bySession.put(sessionId, classify(clientName));
    }

    static Token classify(String clientName) {
        if (clientName == null) {
            return Token.UNKNOWN;
        }
        String lower = clientName.toLowerCase();
        for (String[] entry : KNOWN) {
            if (lower.contains(entry[0])) {
                return new Token(entry[1]);
            }
        }
        return Token.UNKNOWN;
    }

    /** The session's client token; {@link Token#UNKNOWN} for an unseen session. */
    public Token clientOf(String sessionId) {
        Token token = sessionId == null ? null : bySession.get(sessionId);
        return token == null ? Token.UNKNOWN : token;
    }
}
