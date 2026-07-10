package org.jawata.mcp.refactoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 18 — the in-memory plan store backing the multi-step refactoring lifecycle. */
class PlanStoreTest {

    private static PlanStep step() {
        return new PlanStep(0, "extract", null, "did a thing", -1);
    }

    @Test
    @DisplayName("create returns a retrievable plan with a fresh id")
    void createAndGet() {
        PlanStore store = new PlanStore();
        Plan a = store.create("inline_singleton", "com.x.S", List.of(step()), List.of());
        Plan b = store.create("compose_method", "com.x.M", List.of(step(), step()), List.of("watch the netting guard"));

        assertNotEquals(a.planId(), b.planId(), "ids are unique");
        assertTrue(store.get(a.planId()).isPresent());
        assertEquals("inline_singleton", store.get(a.planId()).get().kind());
        assertEquals(2, store.get(b.planId()).get().steps().size());
        assertEquals(List.of("watch the netting guard"), store.get(b.planId()).get().advice());
    }

    @Test
    @DisplayName("an unknown id is empty")
    void unknownId_empty() {
        assertFalse(new PlanStore().get("plan-nope").isPresent());
    }

    @Test
    @DisplayName("appliedThrough is mutable and observed through the store (same instance)")
    void appliedThrough_isObserved() {
        PlanStore store = new PlanStore();
        Plan p = store.create("inline_singleton", "com.x.S", List.of(step()), List.of());
        assertEquals(-1, p.appliedThrough());
        p.appliedThrough(0);
        assertEquals(0, store.get(p.planId()).get().appliedThrough());
    }
}
