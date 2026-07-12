package com.example.pathological;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PASSES only under a CLEARED environment: every inheriting launch has PATH,
 * and the canary is planted by the server-side safety test. Proves the
 * runner's env allowlist (spec §13 — no secret inheritance).
 */
public class EnvProbeTest {

    @Test
    void environmentIsCleared() {
        assertNull(System.getenv("PATH"),
            "PATH is present — the runner inherited the server environment");
        assertNull(System.getenv("JAWATA_CANARY"),
            "the canary leaked — the runner inherited the server environment");
    }
}
