package org.jawata.mcp.agent;

import java.util.Objects;

import org.jawata.mcp.models.CallerIdentity;

/**
 * One ACP session of the agent (Sprint 28h, D1, seam S3). Its identity is the ACP session id the agent minted, supplied back by the
 * host on every request, so every per-session check of the engine sees this session as its own and never as a share of an anonymous
 * bucket (D30).
 *
 * <p>It also holds the session's grant of autonomy (S8, D19): whether the person has said the agent may carry on without asking at each
 * turn. The grant is a fact of the session, changed only by a message on the {@link Channel#HOST} channel; the same call from the
 * {@link Channel#BRAIN} channel is refused and changes nothing.</p>
 */
public final class PrincipalSession {

    private final String id;
    private final String cwd;
    private volatile boolean autonomy;

    /**
     * @param id  the ACP session id
     * @param cwd the working directory the host named when it opened the session
     */
    public PrincipalSession(String id, String cwd) {
        this.id = Objects.requireNonNull(id, "a session with no id is not a session");
        this.cwd = Objects.requireNonNull(cwd, "a session with no working directory");
    }

    /** The ACP session id. */
    public String id() {
        return id;
    }

    /** The working directory the host named when it opened the session. */
    public String cwd() {
        return cwd;
    }

    /** Who this session is to the engine's per-session ledgers. */
    public CallerIdentity identity() {
        return CallerIdentity.supplied(id);
    }

    /** Whether the person has granted the agent autonomy in this session. */
    public boolean autonomy() {
        return autonomy;
    }

    /**
     * Sets or ends the grant. Only the person's channel may: a message from a hired agent is refused and the grant stays as it was.
     *
     * @return whether the call was accepted
     */
    public boolean setAutonomy(Channel from, boolean granted) {
        if (from != Channel.HOST) {
            return false;
        }
        autonomy = granted;
        return true;
    }
}
