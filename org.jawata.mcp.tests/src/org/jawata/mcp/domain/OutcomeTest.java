package org.jawata.mcp.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 18 — the Outcome value type emitted by the orchestration loop (learning seam). */
class OutcomeTest {

    @Test
    @DisplayName("carries its fields and defensively copies the lists")
    void round_trip_and_defensive_copy() {
        List<String> files = new ArrayList<>(List.of("A.java"));
        List<String> notes = new ArrayList<>(List.of("pure"));
        Outcome o = new Outcome("refactoring.apply_plan", "compose_method", "com.x.A#m",
            Outcome.APPLIED, files, "undo-1", notes);

        assertEquals("refactoring.apply_plan", o.operation());
        assertEquals("compose_method", o.kind());
        assertEquals("com.x.A#m", o.target());
        assertEquals(Outcome.APPLIED, o.status());
        assertEquals("undo-1", o.undoChangeId());
        assertEquals(List.of("A.java"), o.filePaths());
        assertEquals(List.of("pure"), o.notes());

        // Mutating the source lists must not leak into the record.
        files.add("B.java");
        notes.add("extra");
        assertEquals(1, o.filePaths().size(), "filePaths defensively copied");
        assertEquals(1, o.notes().size(), "notes defensively copied");
    }

    @Test
    @DisplayName("null lists normalize to empty; undoChangeId may be null")
    void nulls_normalize() {
        Outcome o = new Outcome("refactoring.plan", "inline_singleton", "com.x.S",
            Outcome.ROLLED_BACK, null, null, null);
        assertTrue(o.filePaths().isEmpty());
        assertTrue(o.notes().isEmpty());
        assertNull(o.undoChangeId());
    }

    @Test
    @DisplayName("the copied lists are immutable")
    void copied_lists_immutable() {
        Outcome o = new Outcome("op", "k", "t", Outcome.FLAGGED, List.of("A.java"), null, List.of("n"));
        assertThrows(UnsupportedOperationException.class, () -> o.filePaths().add("X.java"));
        assertThrows(UnsupportedOperationException.class, () -> o.notes().add("y"));
    }
}
