package org.jawata.mcp.learn;

import java.util.Objects;
import java.util.UUID;

/**
 * A plan's identity (Sprint 28g S9a M2).
 *
 * <p>A type rather than a {@code String} for the reason this package learned the
 * expensive way elsewhere: a bare string key is satisfied by any string, so a
 * session id, a tool name and a plan id are interchangeable at every call site
 * that takes one. The compiler cannot tell them apart; this can.</p>
 *
 * <p>There is no "no identity" state. {@link #value()} is never null and never
 * blank, which is what makes an unidentified plan unrepresentable instead of
 * something every reader has to check for.</p>
 */
public record PlanId(String value) {

    public PlanId {
        Objects.requireNonNull(value, "a plan id with no value is not an identity");
        if (value.isBlank()) {
            throw new IllegalArgumentException("a blank plan id is not an identity");
        }
    }

    /** A fresh identity, unique for the life of this resident and beyond it. */
    public static PlanId mint() {
        return new PlanId("plan-" + UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value;
    }
}
