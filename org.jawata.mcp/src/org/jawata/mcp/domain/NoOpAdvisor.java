package org.jawata.mcp.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Sprint 18 — the default {@link Advisor}: there is no stored knowledge yet, so
 * {@link #adviseBefore} returns nothing and {@link #record} only logs the
 * {@link Outcome} (a trace, not a store). This is what lets Sprint 18 ship the
 * orchestration loop store-aware without the store existing; Sprint 21 replaces
 * it with the H2-backed knowledge store that fills the seam.
 */
public final class NoOpAdvisor implements Advisor {

    private static final Logger log = LoggerFactory.getLogger(NoOpAdvisor.class);

    @Override
    public List<String> adviseBefore(String kind, String target) {
        return List.of();
    }

    @Override
    public void record(Outcome outcome) {
        log.debug("Outcome (no store yet; Sprint 21 will persist): {}", outcome);
    }
}
