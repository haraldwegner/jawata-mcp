package org.jawata.mcp.models;

import java.util.Objects;

/**
 * Who a tool call came from, and HOW we know (Sprint 28g S9a M4).
 *
 * <p>This replaces a bare session-id {@code String}, and the reason is a defect
 * rather than tidiness. Four sites wrote {@code sessionId == null ? "local" :
 * sessionId}, which manufactures an identity out of an absence and makes it
 * indistinguishable from a real one — a caller with no session and a caller
 * whose session is literally named {@code local} arrive at the ledger as the
 * same row.</p>
 *
 * <h2>The live defect this exists to fix is FRAGMENTATION, not collapse</h2>
 *
 * <p>Measured before this type was written: no production path reaches the
 * {@code "local"} bucket at all — both transports mint an id, and the two
 * hardcoding overloads have 41 references, every one a test. What production
 * really does is the opposite: for any HTTP client that does not echo
 * {@code Mcp-Session-Id}, the transport minted a FRESH id <em>per request</em>,
 * so one agent's work was scattered across as many identities as it made calls.
 * Every per-session check — a seat's gate, an unaudited ask, the eviction
 * journal — reads a ledger that can never accumulate.</p>
 *
 * <p>The fix mints ONCE per transport, which converts fragmentation into a
 * SHARED BUCKET for all header-less callers of that transport. That is a
 * deliberate trade and not a silent one: {@link Origin#RESIDENT_MINTED} is what
 * stops a reader mistaking that bucket's history for one agent's session. An id
 * is never absent, so "no identity" is unrepresentable rather than something
 * every reader has to check for.</p>
 *
 * @param id     the identity as every downstream column still needs it — never
 *     null, never blank
 * @param origin how we came to have it, which is the fact a bare string could
 *     not carry
 */
public record CallerIdentity(String id, Origin origin) {

    /** How an identity came to be, which decides what its history means. */
    public enum Origin {

        /** The client echoed a session id, so this identity is THEIRS. */
        CLIENT_SUPPLIED,

        /**
         * The client supplied none and the resident minted one. Shared by every
         * header-less caller of that transport, so its history is a transport's
         * and not an agent's.
         */
        RESIDENT_MINTED,

        /**
         * No transport at all — something inside this process called a tool
         * directly. Declared rather than defaulted: the old code reached this
         * state by a null check and called it {@code "local"}, which read like a
         * session.
         */
        IN_PROCESS,

        /**
         * An editor's language client, through the engine's language server (Sprint 28h, S16a, R1). Not an agent and not a transport's
         * anonymous bucket: its calls are what a person's typing asks for, so a reader of the ledger can tell them from an agent's steps.
         */
        EDITOR
    }

    /**
     * The one in-process identity. Its id is deliberately NOT {@code "local"} —
     * that literal is what the four null-coalescing sites manufactured, and a
     * reader who meets it cannot tell a real session from an absent one.
     */
    public static final CallerIdentity IN_PROCESS =
        new CallerIdentity("in-process", Origin.IN_PROCESS);

    public CallerIdentity {
        Objects.requireNonNull(id, "an identity with no id is not an identity");
        if (id.isBlank()) {
            throw new IllegalArgumentException("a blank id is not an identity");
        }
        Objects.requireNonNull(origin, "an identity that cannot say where it came from"
            + " is the defect this type replaces");
    }

    /** An identity the client sent us, so the history under it is theirs. */
    public static CallerIdentity supplied(String headerValue) {
        return new CallerIdentity(oneValueOf(headerValue), Origin.CLIENT_SUPPLIED);
    }

    /**
     * A header a client sends more than once reaches us joined with commas, and the first live run with Cursor showed its agent sends the session
     * header four or five times, so every hire was asked for by {@code key, key, key, key} and no registry lookup by the key ever matched. A session
     * id is one value, so the first is the identity; a value with no comma, or whose first part is blank, is left as it came.
     */
    private static String oneValueOf(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        int comma = headerValue.indexOf(',');
        if (comma < 0) {
            return headerValue;
        }
        String first = headerValue.substring(0, comma).strip();
        return first.isEmpty() ? headerValue : first;
    }

    /**
     * An identity the resident minted because the client supplied none. The
     * channel names which transport minted it, so two transports' anonymous
     * callers do not share one bucket.
     */
    public static CallerIdentity minted(String channel) {
        Objects.requireNonNull(channel, "a minted identity must say which transport minted it");
        return new CallerIdentity(channel + "-anonymous", Origin.RESIDENT_MINTED);
    }

    /** The identity of one editor connection to the language server; {@code connection} tells two editors apart. */
    public static CallerIdentity editor(String connection) {
        Objects.requireNonNull(connection, "an editor identity must say which connection it is");
        return new CallerIdentity("editor-" + connection, Origin.EDITOR);
    }

    /** The caller of the tool call being served on this thread; none outside a call the registry dispatched. */
    private static final ThreadLocal<CallerIdentity> SERVING = new ThreadLocal<>();

    /**
     * Runs {@code call} with this identity as the one {@link #serving()} answers on this thread, and puts back whoever was serving before.
     * The registry wraps each tool's execution in it, so a tool that must tell its callers apart (a hire continuing the brain session of the
     * caller's own earlier hire, Sprint 28h D4) can ask without every tool's signature carrying the caller.
     */
    public <T> T speaking(java.util.function.Supplier<T> call) {
        CallerIdentity before = SERVING.get();
        SERVING.set(this);
        try {
            return call.get();
        } finally {
            if (before == null) {
                SERVING.remove();
            } else {
                SERVING.set(before);
            }
        }
    }

    /** The caller of the tool call being served on this thread, or none when no registry call is being served here. */
    public static java.util.Optional<CallerIdentity> serving() {
        return java.util.Optional.ofNullable(SERVING.get());
    }

    @Override
    public String toString() {
        return id + " (" + origin + ")";
    }
}
