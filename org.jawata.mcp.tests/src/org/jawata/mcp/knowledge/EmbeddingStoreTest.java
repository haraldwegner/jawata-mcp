package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.learn.ToolExperience;
import org.junit.jupiter.api.Test;

/**
 * Sprint 27 C3 — the v7 lane: embed on write (both lanes), self-heal, and the
 * degrade path.
 *
 * <p>The load-bearing claim under test is not "vectors get stored" but "the
 * store never gets WORSE because of embedding": a row must land, and stay
 * keyword-reachable, even when the embedder is absent, disabled or broken.</p>
 */
class EmbeddingStoreTest {

    private static ExperienceEntry entry(String summary, String details) {
        return ExperienceEntry.of(
            SymbolFact.of("lesson", summary, Confidence.MEDIUM).details(details).build())
            .build();
    }

    private static byte[] embeddingOf(H2ExperienceStore store, String table,
                                      String idColumn, String id) throws Exception {
        try (PreparedStatement ps = store.sharedConnection().prepareStatement(
                "SELECT embedding FROM " + table + " WHERE " + idColumn + " = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    private static String identityOf(H2ExperienceStore store, String table,
                                     String idColumn, String id) throws Exception {
        try (PreparedStatement ps = store.sharedConnection().prepareStatement(
                "SELECT embedder_identity FROM " + table + " WHERE " + idColumn + " = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * v3.4.1 — THE SHIPPED DEFECT, reproduced.
     *
     * <p>v3.4.0 created {@code quality_counter} inside the v7 step. The ladder
     * runs a step only when {@code from < n}, so a store ALREADY at v7 — every
     * store any Sprint-27 build had opened, including the live one — never re-ran
     * v7 and never got the table. Its quality ledger then recorded nothing at all,
     * for precisely the stores with history worth measuring.</p>
     *
     * <p>This test stands a store at v7 WITHOUT the table, exactly as the released
     * build left it, and requires the migration to repair it.</p>
     */
    @Test
    void a_store_already_at_v7_still_gains_the_counter_table() throws Exception {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        try {
            java.sql.Connection c = store.sharedConnection();
            // Recreate the released build's end state: v7, no counter table.
            try (java.sql.Statement s = c.createStatement()) {
                s.execute("DROP TABLE IF EXISTS quality_counter");
                s.execute("DELETE FROM schema_version");
                s.execute("INSERT INTO schema_version VALUES (7)");
            }
            assertEquals(7, SchemaMigrations.detectVersion(c), "precondition: at v7");

            QualityLedger before = new QualityLedger(store);
            before.fired(QualityLedger.SURFACE_PRIMER);
            assertTrue(before.counters().keySet().iterator().next().startsWith("(unavailable"),
                "precondition: this is the v3.4.0 symptom — the ledger cannot record");

            Map<String, Object> report = SchemaMigrations.migrate(c, null);
            assertEquals(true, report.get("migrated"), "a v7 store has somewhere to go");
            assertEquals(SchemaMigrations.LATEST, report.get("to"));

            QualityLedger after = new QualityLedger(store);
            after.fired(QualityLedger.SURFACE_PRIMER);
            assertEquals(1L, after.counters().get("fired.primer"),
                "an EXISTING store must gain the table — additive DDL bolted into an "
                + "already-released migration step reaches new databases only");
        } finally {
            store.close();
        }
    }

    @Test
    void the_v7_columns_exist_on_both_lanes_and_migration_is_idempotent() throws Exception {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        try {
            // Migrating an already-current store must be a no-op, not an error:
            // every resident start runs this path.
            Map<String, Object> again = SchemaMigrations.migrate(store.sharedConnection(), null);
            assertEquals(false, again.get("migrated"), "a current store re-migrates to nothing");
            assertEquals(SchemaMigrations.LATEST, again.get("to"));
            // v3.4.1: this used to read assertEquals(7, LATEST) — it pinned the
            // schema NUMBER while claiming to check the semantic-recall lane, so
            // adding a rung broke it for no behavioural reason. The lane is the
            // COLUMNS; assert those and let the ladder grow.
            assertTrue(SchemaMigrations.LATEST >= 7,
                "the semantic-recall lane arrived at v7 and never goes away");

            for (String table : List.of("experience_entry", "tool_experience")) {
                try (Statement s = store.sharedConnection().createStatement();
                        ResultSet rs = s.executeQuery(
                            "SELECT embedding, embedder_identity FROM " + table + " LIMIT 0")) {
                    assertNotNull(rs.getMetaData(), table + " must carry the v7 columns");
                    assertEquals(2, rs.getMetaData().getColumnCount());
                }
            }
        } finally {
            store.close();
        }
    }

    @Test
    void a_written_entry_carries_a_vector_and_the_identity_that_made_it() throws Exception {
        EmbeddingService svc = EmbeddingService.shared();
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        try {
            String id = store.put(entry("broker confirm before cleanup",
                "never free the slot until the broker confirms the cancel"));
            byte[] blob = embeddingOf(store, "experience_entry", "id", id);
            if (!svc.available()) {
                assertNull(blob, "with no embedder the row lands WITHOUT a vector");
                return;                        // degrade path asserted elsewhere
            }
            assertNotNull(blob, "an entry written with an embedder present must carry a vector");
            float[] v = EmbeddingService.fromBytes(blob);
            assertEquals(384, v.length);
            assertEquals(svc.identityKey(), identityOf(store, "experience_entry", "id", id),
                "the vector records WHICH embedder produced it");
        } finally {
            store.close();
        }
    }

    @Test
    void a_captured_tool_experience_carries_a_vector_too() throws Exception {
        EmbeddingService svc = EmbeddingService.shared();
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        try {
            ToolExperienceStore lane = new ToolExperienceStore(store);
            lane.append(new ToolExperience("s1", "extract com.foo.Bar#baz method",
                "extract", ToolExperience.OUTCOME_REVERTED, "{}"));
            assertEquals(1, lane.count());
            try (Statement s = store.sharedConnection().createStatement();
                    ResultSet rs = s.executeQuery(
                        "SELECT embedding, embedder_identity FROM tool_experience")) {
                assertTrue(rs.next());
                if (svc.available()) {
                    assertNotNull(rs.getBytes(1), "the capture lane embeds on write");
                    assertEquals(svc.identityKey(), rs.getString(2));
                } else {
                    assertNull(rs.getBytes(1));
                }
            }
        } finally {
            store.close();
        }
    }

    @Test
    void a_superseded_identity_is_re_embedded_rather_than_compared() throws Exception {
        EmbeddingService svc = EmbeddingService.shared();
        if (!svc.available()) {
            return;
        }
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        try {
            store.put(entry("first lesson", "details one"));
            store.put(entry("second lesson", "details two"));
            EmbeddingIndex index = new EmbeddingIndex(store, svc);
            assertEquals(2, index.embeddedCount("experience_entry"));

            // Simulate a model/pipeline change: the vectors are now from a
            // FOREIGN space. They must become invisible to search and be
            // rebuilt - never silently compared against current-identity cues.
            try (Statement s = store.sharedConnection().createStatement()) {
                s.executeUpdate("UPDATE experience_entry SET embedder_identity = 'old/384/v0'");
            }
            assertEquals(0, index.embeddedCount("experience_entry"),
                "vectors of a superseded identity do not count as embedded");
            assertTrue(index.nearestEntries("first lesson").isEmpty(),
                "and they are not searchable, because comparing across spaces is meaningless");

            int healed = index.backfill(100);
            assertEquals(2, healed, "the identity flip re-embeds every affected row");
            assertEquals(2, index.embeddedCount("experience_entry"));
            assertFalse(index.nearestEntries("first lesson").isEmpty(),
                "and search works again afterwards");
        } finally {
            store.close();
        }
    }

    @Test
    void backfill_embeds_only_the_rows_that_need_it() throws Exception {
        EmbeddingService svc = EmbeddingService.shared();
        if (!svc.available()) {
            return;
        }
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        try {
            String keep = store.put(entry("already embedded", "has a vector"));
            String stale = store.put(entry("needs a vector", "was written without one"));
            try (Statement s = store.sharedConnection().createStatement()) {
                s.executeUpdate("UPDATE experience_entry SET embedding = NULL,"
                    + " embedder_identity = NULL WHERE id = '" + stale + "'");
            }
            EmbeddingIndex index = new EmbeddingIndex(store, svc);
            byte[] before = embeddingOf(store, "experience_entry", "id", keep);

            assertEquals(1, index.backfill(100), "exactly the one missing row is embedded");
            assertNotNull(embeddingOf(store, "experience_entry", "id", stale));
            assertArrayEqualsBytes(before, embeddingOf(store, "experience_entry", "id", keep));
            assertEquals(0, index.backfill(100), "a second pass has nothing left to do");
        } finally {
            store.close();
        }
    }

    @Test
    void meaning_nomination_ranks_a_paraphrase_above_unrelated_entries() throws Exception {
        EmbeddingService svc = EmbeddingService.shared();
        if (!svc.available()) {
            return;
        }
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        try {
            String target = store.put(entry(
                "never remove the algo from the map until the broker confirms",
                "premature removal routes a late cancel callback to the wrong algo"));
            store.put(entry("the webview renders blank on linux",
                "the DMABUF compositor path fails silently on some drivers"));
            store.put(entry("opening range breakout window",
                "the first fifteen minutes of the session define the range"));

            // A cue sharing NO distinctive words with the target - the whole
            // point of the sprint; keyword recall cannot reach this.
            List<EmbeddingIndex.Hit> hits = new EmbeddingIndex(store, svc)
                .nearestEntries("we deleted the order record too early and a late "
                    + "confirmation went to the wrong place");
            assertFalse(hits.isEmpty(), "meaning nomination must return candidates");
            assertEquals(target, hits.get(0).id(),
                "the paraphrase must rank the semantically right entry first");
        } finally {
            store.close();
        }
    }

    /**
     * The degrade contract: with embedding switched off, writing still works,
     * the row still lands, and it stays reachable the v3.3.1 way. This is the
     * single most important test in the stage — a store that loses knowledge
     * when the embedder is unavailable would be a regression, not a feature.
     */
    @Test
    void with_embedding_disabled_rows_still_land_and_stay_keyword_reachable() throws Exception {
        String previous = System.getProperty(EmbeddingService.DISABLE_PROPERTY);
        System.setProperty(EmbeddingService.DISABLE_PROPERTY, "true");
        EmbeddingService.resetForTests();
        try {
            EmbeddingService off = EmbeddingService.shared();
            assertFalse(off.available(), "the disable switch must actually disable");
            assertNotNull(off.unavailableReason(), "and it must say WHY, not just be empty");

            H2ExperienceStore store = H2ExperienceStore.openMemory();
            try {
                String id = store.put(entry("database file already locked at startup",
                    "the lock survived a crash and the next start refused"));
                assertNull(embeddingOf(store, "experience_entry", "id", id),
                    "no embedder, no vector - honestly null rather than a zero vector");

                // The row is fully usable through the pre-Sprint-27 path.
                assertTrue(store.get(id).isPresent(), "the row landed");
                ExperienceRetrieval recall = new ExperienceRetrieval(store, () -> null);
                Map<String, Object> answer = recall.recall(new RecallQuery(
                    null, null, null, "database file already locked", null));
                assertEquals(ExperienceRetrieval.RESULT_MATCH, answer.get("result"),
                    "keyword recall still finds it with embedding off");

                assertTrue(new EmbeddingIndex(store, off).nearestEntries("anything").isEmpty(),
                    "and semantic nomination yields nothing rather than pretending");
            } finally {
                store.close();
            }
        } finally {
            if (previous == null) {
                System.clearProperty(EmbeddingService.DISABLE_PROPERTY);
            } else {
                System.setProperty(EmbeddingService.DISABLE_PROPERTY, previous);
            }
            EmbeddingService.resetForTests();
        }
    }

    @Test
    void the_vector_codec_round_trips_and_refuses_ragged_input() {
        float[] v = {0.5f, -0.25f, 1e-8f, 3.14159f};
        assertArrayEqualsFloats(v, EmbeddingService.fromBytes(EmbeddingService.toBytes(v)));
        assertNull(EmbeddingService.toBytes(null));
        assertNull(EmbeddingService.fromBytes(null));
        assertNull(EmbeddingService.fromBytes(new byte[] {1, 2, 3}), "not a whole float count");
        assertEquals(0.0, EmbeddingService.cosine(new float[] {1, 0}, null), 0.0,
            "a missing vector scores 0, never an accidental match");
        assertEquals(0.0, EmbeddingService.cosine(new float[] {1, 0}, new float[] {1, 0, 0}), 0.0,
            "mismatched dimensions score 0 rather than comparing a prefix");
    }

    @Test
    void the_embeddable_text_rule_is_summary_then_details() {
        assertEquals("a b", EmbeddingService.textOf("a", "b"));
        assertEquals("a", EmbeddingService.textOf("a", null));
        assertEquals("b", EmbeddingService.textOf(null, "b"));
        assertEquals("a", EmbeddingService.textOf("  a  ", "   "));
    }

    private static void assertArrayEqualsBytes(byte[] a, byte[] b) {
        assertEquals(a == null, b == null);
        if (a != null) {
            assertEquals(a.length, b.length, "vector length changed");
            for (int i = 0; i < a.length; i++) {
                assertEquals(a[i], b[i], "an already-embedded row must not be rewritten");
            }
        }
    }

    private static void assertArrayEqualsFloats(float[] a, float[] b) {
        assertNotNull(b);
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], b[i], 0f);
        }
    }
}
