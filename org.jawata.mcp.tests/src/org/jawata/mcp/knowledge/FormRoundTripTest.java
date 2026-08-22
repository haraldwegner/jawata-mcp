package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Sprint 28c S4 — the write path and the round trip, widened together.
 *
 * <p>The insert list, {@code ALL_COLUMNS} and {@code importEntries}' bind list
 * are ONE contract in three places. Widen any of them alone and a column is
 * dropped on every export/import cycle — silently, because the obvious identity
 * test passes on legacy rows, whose new columns are null on both sides, and
 * fails only once a row exists that actually carries a value. So this test
 * insists on a row that carries every facet.</p>
 *
 * <p>The reverse direction matters as much: an export written before these
 * columns existed must still import, and must read as legacy rather than as
 * something with a form of 0. "Nobody classified this row" and "this row is
 * classified as legacy" are different claims, and a round trip must not turn
 * the first into the second.</p>
 */
class FormRoundTripTest {

    private static ExperienceEntry formOne() {
        return ExperienceEntry.of(
                SymbolFact.of("lesson",
                    "Amending a partially filled order replaces the remaining quantity.",
                    Confidence.HIGH).symbol("com.example.Orders").build())
            .status(ExperienceEntry.ACCEPTED)
            .situation("when amending an order that is already partially filled")
            .situationScope("conditional")
            .verdict("failed_avoid")
            .provenanceKind("recorded")
            .form(1)
            .build();
    }

    private static Map<String, Object> rowFor(List<Map<String, Object>> rows, String summary) {
        return rows.stream()
            .filter(r -> String.valueOf(r.get("summary")).startsWith(summary))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no exported row starting '" + summary + "' in " + rows));
    }

    @Test
    void every_facet_survives_export_and_import(@TempDir Path source, @TempDir Path target)
            throws Exception {
        List<Map<String, Object>> exported;
        try (H2ExperienceStore store = H2ExperienceStore.open(source)) {
            String id = store.put(formOne());
            // Driven through the production writer, not a builder door. A newly
            // recorded entry can never already have dead evidence, so this is
            // the ONLY way the column is ever set true in the live product —
            // and asserting a true that a real writer produced is what makes
            // the round trip evidence rather than decoration.
            assertTrue(store.markEvidenceDead(id),
                "precondition: the production writer reports it marked the entry");
            store.put(ExperienceEntry.candidate(
                SymbolFact.of("domain_fact", "The store is one file on disk.",
                    Confidence.MEDIUM).symbol("com.example.Store").build()));
            exported = store.exportEntries(null, null);
        }
        assertEquals(2, exported.size(), "both entries export");

        Map<String, Object> lesson = rowFor(exported, "Amending");
        assertEquals("when amending an order that is already partially filled",
            lesson.get("situation"), "the situation must be exported at all");
        assertEquals("conditional", lesson.get("situation_scope"));
        assertEquals("failed_avoid", lesson.get("verdict"));
        assertEquals("recorded", lesson.get("provenance_kind"));
        assertEquals(1, lesson.get("form"));
        assertEquals(true, lesson.get("evidence_dead"),
            "set through markEvidenceDead — production's only writer — and it survived");

        // The fact carries none of them, and its ABSENCE is the point: an
        // exported row must not gain a form of 0 or an evidence_dead of false
        // just by passing through.
        Map<String, Object> fact = rowFor(exported, "The store is one file");
        for (String facet : new String[] {"situation", "situation_scope", "verdict",
                "provenance_kind", "form", "evidence_dead"}) {
            assertFalse(fact.containsKey(facet),
                "an unclassified row must export WITHOUT " + facet
                    + " — an absent facet and a defaulted one are different claims: " + fact);
        }

        // Into a fresh store, through the real import path.
        try (H2ExperienceStore restored = H2ExperienceStore.open(target)) {
            Map<String, Object> report = restored.importEntries(exported);
            assertEquals(2, report.get("imported"), "both rows import: " + report);

            List<Map<String, Object>> after = restored.exportEntries(null, null);
            Map<String, Object> lessonAgain = rowFor(after, "Amending");
            assertEquals(lesson, lessonAgain,
                "the round trip must be lossless for a row that actually carries facets — "
                    + "this is what a legacy-only fixture cannot detect");

            Map<String, Object> factAgain = rowFor(after, "The store is one file");
            assertEquals(fact, factAgain, "and lossless for one that carries none");
        }
    }

    @Test
    void an_export_written_before_these_columns_existed_still_imports_as_legacy(
            @TempDir Path target) throws Exception {
        // Exactly the shape a pre-28c export has: no facet keys at all.
        Map<String, Object> old = Map.of(
            "id", "legacy-export-1",
            "type", "lesson",
            "status", "accepted",
            "confidence", "high",
            "summary", "A lesson recorded before the knowledge spine existed.",
            "language", "java");

        try (H2ExperienceStore store = H2ExperienceStore.open(target)) {
            Map<String, Object> report = store.importEntries(List.of(old));
            assertEquals(1, report.get("imported"), "an old export must still import: " + report);

            Map<String, Object> row = rowFor(store.exportEntries(null, null), "A lesson recorded");
            assertNotNull(row.get("summary"), "the entry itself survived");
            assertNull(row.get("form"),
                "a row from before the columns existed is UNCLASSIFIED, not classified-as-legacy");
            assertNull(row.get("verdict"));
            assertNull(row.get("situation"));
        }
    }
}
