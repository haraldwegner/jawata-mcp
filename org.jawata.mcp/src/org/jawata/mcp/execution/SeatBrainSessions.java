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

    /**
     * How long a pm may be idle and still be continued (28h z203): the life of the brain's prompt cache. After it the whole earlier
     * conversation is written again, which cost more than a fresh pm (measured 2026-11-28). Only the pm was ruled; other seats continue.
     */
    private static final long PM_IDLE_LIMIT_MS = java.util.concurrent.TimeUnit.HOURS.toMillis(1);

    /** A brain session and when its seat last answered in it. */
    private record Answered(String brainSessionId, long atMs) {
    }

    private final Map<String, Answered> sessions = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;

    SeatBrainSessions() {
        this(System::currentTimeMillis);
    }

    SeatBrainSessions(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    /** The one store every hire in this process uses. */
    public static SeatBrainSessions shared() {
        return SHARED;
    }

    /** Keeps {@code brainSessionId} as the session {@code seat} last answered in for {@code principalSession}. */
    public void remember(String principalSession, String seat, String brainSessionId) {
        sessions.put(key(principalSession, seat), new Answered(brainSessionId, clock.getAsLong()));
    }

    /** The brain session {@code seat} last answered in for {@code principalSession}, or none; none too for a pm idle an hour or more. */
    public Optional<String> recall(String principalSession, String seat) {
        return Optional.ofNullable(sessions.get(key(principalSession, seat)))
            .filter(last -> !"pm".equals(seat) || clock.getAsLong() - last.atMs() < PM_IDLE_LIMIT_MS)
            .map(Answered::brainSessionId);
    }

    private static String key(String principalSession, String seat) {
        return principalSession + "\n" + seat;
    }
}
