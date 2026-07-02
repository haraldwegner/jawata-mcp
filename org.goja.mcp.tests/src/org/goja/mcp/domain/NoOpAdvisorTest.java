package org.goja.mcp.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 18 — the default no-store Advisor: empty advice, record is a safe no-op. */
class NoOpAdvisorTest {

    private final Advisor advisor = new NoOpAdvisor();

    @Test
    @DisplayName("adviseBefore returns no advice when there is no store")
    void advise_before_empty() {
        List<String> advice = advisor.adviseBefore("compose_method", "com.x.A#m");
        assertNotNull(advice);
        assertTrue(advice.isEmpty());
    }

    @Test
    @DisplayName("record accepts an Outcome without throwing")
    void record_does_not_throw() {
        Outcome o = new Outcome("refactoring.apply_plan", "compose_method", "com.x.A#m",
            Outcome.APPLIED, List.of("A.java"), "undo-1", List.of());
        assertDoesNotThrow(() -> advisor.record(o));
    }

    @Test
    @DisplayName("GojaService defaults to a NoOpAdvisor when none is supplied")
    void gojaservice_defaults_to_noop() {
        GojaService svc = new GojaService(() -> null, new DetectorCatalog());
        assertNotNull(svc.advisor());
        assertTrue(svc.advisor().adviseBefore("k", "t").isEmpty(),
            "default advisor gives no advice");
    }
}
