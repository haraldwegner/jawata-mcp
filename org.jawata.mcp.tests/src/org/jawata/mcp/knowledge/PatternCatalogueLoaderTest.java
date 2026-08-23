package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28c D5 — the catalogue seeds itself, once, without touching anything
 * that is not its own.
 *
 * <p>The gate that matters is the SECOND start. A loader that re-seeds is not
 * merely wasteful: it duplicates 187 rows per boot, and duplicates are what
 * spend the budget an answer has.</p>
 */
class PatternCatalogueLoaderTest {

    /**
     * The bounded sample, printed for a human to read.
     *
     * <p>Clause 5b exists because this loader is a second writer, and the
     * extractor's own sample proved the extractor, not this. What is checked
     * here is the SHAPE the loader gives a row: a situation that is a
     * condition, not a heading; the catalogue's provenance; candidate status.</p>
     */
    @Test
    void the_sample_writes_one_pattern_and_it_has_the_shape_we_want(@TempDir Path dir)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            PatternCatalogueLoader.Result r = new PatternCatalogueLoader().load(store, 1);

            assertEquals(1, r.inSnapshot(), "the sample must consider exactly one pattern");
            assertEquals(1, r.seeded());
            assertEquals(1, store.count(), "and write exactly one row");

            StoredEntry only = store.all().get(0);
            System.out.println("\n=== CATALOGUE SAMPLE (clause 5b, read this) ===");
            System.out.println("summary   : " + only.summary());
            System.out.println("situation : " + only.facets().situation());
            System.out.println("provenance: " + only.facets().provenanceKind());
            System.out.println("status    : " + only.status());
            System.out.println("operation : " + only.operation());
            System.out.println("sourceRef : " + only.sourceRef());

            assertEquals(PatternCatalogueLoader.PROVENANCE, only.facets().provenanceKind());
            assertEquals(ExperienceEntry.CANDIDATE, only.status(),
                "somebody else's pattern is a candidate, never the user's earned knowledge");
            assertNotNull(only.facets().situation(), "a pattern without a situation is a heading");
            assertFalse(only.facets().situation().startsWith("#"),
                "a heading is not knowledge, whatever it is labelled");
            assertTrue(only.sourceRef().startsWith(PatternCatalogueLoader.SOURCE_PREFIX),
                "sourceRef must mark the row as ours: " + only.sourceRef());
            assertFalse(only.sourceRef().contains("22a34127"),
                "the pinned commit must NOT be in the identity key — an entry keyed by "
                    + "commit is unmatchable from the next snapshot, so every update would "
                    + "look like a brand-new pattern");
        }
    }

    /**
     * Seeding twice equals seeding once. The row count is the assertion a
     * duplicate cannot survive.
     */
    @Test
    void a_second_start_writes_nothing(@TempDir Path dir) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            PatternCatalogueLoader loader = new PatternCatalogueLoader();

            PatternCatalogueLoader.Result first = loader.load(store);
            long afterFirst = store.count();
            PatternCatalogueLoader.Result second = loader.load(store);

            assertTrue(first.seeded() > 100,
                "the snapshot should carry the whole catalogue, got " + first.seeded());
            assertEquals(first.inSnapshot(), first.seeded(),
                "a fresh store must take every pattern");
            assertEquals(0, second.seeded(), "THE SECOND START RE-SEEDED THE CATALOGUE");
            assertEquals(first.inSnapshot(), second.unchanged(),
                "and must recognise every one of them as already current");
            assertEquals(afterFirst, store.count(), "the row count must not move");
            assertTrue(second.quiet(), "a quiet run is what the start-up line keys on");
        }
    }

    /**
     * The user's own rows are not the loader's business. Compared by export
     * rather than by count, because a count survives a row being rewritten.
     */
    @Test
    void a_foreign_store_keeps_every_user_row_byte_identical(@TempDir Path dir)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            store.put(ExperienceEntry.of(
                    SymbolFact.of("failure_mode", "The user's own hard-won lesson.",
                        Confidence.HIGH).symbol("com.example.Mine").build())
                .status(ExperienceEntry.ACCEPTED)
                .situation("when the user recorded something themselves")
                .verdict("failed_avoid")
                .provenanceKind("recorded")
                .form(1)
                .build());
            List<Map<String, Object>> before = store.exportEntries(null, null);

            new PatternCatalogueLoader().load(store);

            List<Map<String, Object>> after = store.exportEntries(null, null);
            Map<String, Object> mineBefore = onlyRecorded(before);
            Map<String, Object> mineAfter = onlyRecorded(after);
            assertEquals(mineBefore, mineAfter, "A USER ROW WAS MODIFIED BY THE SEEDER");
            assertTrue(after.size() > before.size(), "and the catalogue rows were added");
        }
    }

    private static Map<String, Object> onlyRecorded(List<Map<String, Object>> rows) {
        return rows.stream()
            .filter(r -> "recorded".equals(r.get("provenance_kind")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the user's row vanished entirely"));
    }

    /**
     * An upstream edit is visible as a changed hash, which is the whole reason
     * identity is content-based rather than marker-based. Stage 5 decides what
     * to DO with a changed pattern; this pins that it is noticed at all.
     */
    @Test
    void a_changed_pattern_is_noticed_on_the_next_start(@TempDir Path dir) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode pattern = json.createObjectNode();
        pattern.put("slug", "some-pattern");
        pattern.put("type", "lesson");
        pattern.put("situation", "when two objects must vary independently");
        pattern.put("principle", "Separate the abstraction from its implementation.");
        com.fasterxml.jackson.databind.node.ObjectNode snap = json.createObjectNode();
        snap.put("pinned_commit", "deadbeef");
        snap.putArray("patterns").add(pattern);

        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            assertEquals(1, new PatternCatalogueLoader(snap).load(store).seeded());
            assertEquals(0, new PatternCatalogueLoader(snap).load(store).seeded());

            pattern.put("principle", "Separate the abstraction from its implementation, "
                + "so the two can vary without touching each other.");
            PatternCatalogueLoader.Result after =
                new PatternCatalogueLoader(snap).load(store);

            assertEquals(1, after.seeded(),
                "AN UPSTREAM EDIT WENT UNNOTICED — the catalogue can never be updated");
            assertEquals(0, after.unchanged());
        }
    }

    /**
     * The hash covers the whole pattern, not just its first line. A body
     * rewritten under an unchanged summary must still count as changed.
     */
    @Test
    void the_hash_covers_the_body_not_only_the_summary() {
        com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode a = json.createObjectNode();
        a.put("slug", "x");
        a.put("principle", "Same first line.");
        a.put("details", "The original body.");
        com.fasterxml.jackson.databind.node.ObjectNode b = a.deepCopy();
        b.put("details", "A completely rewritten body.");

        assertFalse(PatternCatalogueLoader.hashOf(a).equals(PatternCatalogueLoader.hashOf(b)),
            "a rewritten body under an unchanged summary would ship forever");
    }
}
