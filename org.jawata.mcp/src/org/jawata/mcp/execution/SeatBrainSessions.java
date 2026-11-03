package org.jawata.mcp.execution;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The brain session each hired seat last answered in, per principal session (Sprint 28h, D4): a later hire of the same seat in the same
 * principal session continues that brain's own session, so it can answer a question only the earlier hire saw. Process-wide, like the
 * list of running hires, because each hire builds its own run and nothing else outlives the call.
 *
 * <p>The key is the principal session and the seat together. A principal session is a caller that sent a session id of its own: a caller
 * with none shares a transport's anonymous bucket, and continuing one anonymous caller's brain session for another would hand a stranger
 * the earlier conversation, so for such a caller nothing is remembered and nothing is recalled.</p>
 */
public final class SeatBrainSessions {

    private static final SeatBrainSessions SHARED = new SeatBrainSessions();

    private final Map<String, String> sessions = new ConcurrentHashMap<>();

    /** The one store every hire in this process uses. */
    public static SeatBrainSessions shared() {
        return SHARED;
    }

    /** Keeps {@code brainSessionId} as the session {@code seat} last answered in for {@code principalSession}. */
    public void remember(String principalSession, String seat, String brainSessionId) {
        sessions.put(key(principalSession, seat), brainSessionId);
    }

    /** The brain session {@code seat} last answered in for {@code principalSession}, or none. */
    public Optional<String> recall(String principalSession, String seat) {
        return Optional.ofNullable(sessions.get(key(principalSession, seat)));
    }

    private static String key(String principalSession, String seat) {
        return principalSession + "\n" + seat;
    }
}
