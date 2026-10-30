package org.jawata.mcp.agent;

import java.util.Objects;

import org.jawata.mcp.models.CallerIdentity;

/**
 * One ACP session of the agent (Sprint 28h, D1, seam S3). Its identity is the ACP session id the agent minted, supplied back by the
 * host on every request, so every per-session check of the engine sees this session as its own and never as a share of an anonymous
 * bucket (D30).
 *
 * @param id  the ACP session id
 * @param cwd the working directory the host named when it opened the session
 */
public record PrincipalSession(String id, String cwd) {

    public PrincipalSession {
        Objects.requireNonNull(id, "a session with no id is not a session");
        Objects.requireNonNull(cwd, "a session with no working directory");
    }

    /** Who this session is to the engine's per-session ledgers. */
    public CallerIdentity identity() {
        return CallerIdentity.supplied(id);
    }
}
