package org.jawata.mcp.knowledge;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 21a Stage 0 — versioned schema + migration runner (item B). A store created by
 * jawata-mcp v2.0.0 (pre-versioning, {@code CREATE TABLE IF NOT EXISTS}, no facets) must
 * migrate forward losslessly; a store from a NEWER resident must be refused; migration
 * backs the file up first; provenance + language facets are stamped on new writes.
 */
class SchemaMigrationsTest {

    private static String url(Path dir) {
        return "jdbc:h2:file:" + dir.resolve("jawata-experience").resolve("experience").toAbsolutePath()
            + ";DB_CLOSE_ON_EXIT=FALSE";
    }

    private static Connection connect(Path dir) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(url(dir));
        return ds.getConnection();
    }

    /** Recreate EXACTLY what jawata-mcp v2.0.0 wrote: the pre-versioning schema + one row. */
    private static void createV1Fixture(Path dir, String entryId) throws Exception {
        try (Connection c = connect(dir); Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS experience_entry (
                    id              VARCHAR(64) PRIMARY KEY,
                    type            VARCHAR(64),
                    scope_kind      VARCHAR(32),
                    symbol_fqn      VARCHAR(1024),
                    package_name    VARCHAR(512),
                    operation       VARCHAR(128),
                    status          VARCHAR(32) DEFAULT 'candidate',
                    confidence      VARCHAR(16),
                    fault_owner     VARCHAR(16),
                    external_system VARCHAR(256),
                    summary         VARCHAR(4096),
                    source_ref      VARCHAR(512),
                    body_json       CLOB,
                    created_at      TIMESTAMP,
                    updated_at      TIMESTAMP
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS experience_symptom (
                    entry_id VARCHAR(64),
                    symptom  VARCHAR(512),
                    PRIMARY KEY (entry_id, symptom)
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS experience_link (
                    entry_id VARCHAR(64),
                    rel      VARCHAR(32),
                    target   VARCHAR(1024),
                    PRIMARY KEY (entry_id, rel, target)
                )""");
            s.execute("INSERT INTO experience_entry (id, type, symbol_fqn, status, confidence, summary,"
                + " body_json, created_at, updated_at) VALUES ('" + entryId + "', 'lesson',"
                + " 'com.example.Legacy', 'accepted', 'high', 'a v2.0.0-era lesson',"
                + " '{\"type\":\"lesson\",\"summary\":\"a v2.0.0-era lesson\",\"status\":\"accepted\"}',"
                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
    }

    private static String scalar(Path dir, String sql) throws Exception {
        try (Connection c = connect(dir); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void v5_creates_the_learner_tables(@TempDir Path dir) throws Exception {
        // Sprint 26: the injector's learning layer rides the same store file.
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            LearnerEventStore learner = new LearnerEventStore(store);
            learner.append(new org.jawata.mcp.learn.LearnerEvent(
                "s1", org.jawata.mcp.learn.LearnerEvent.KIND_TOOL_ERROR, "compile_workspace",
                "{\"error\":true}"));
            learner.saveState("edit-switch", "{\"w\":[0.1]}");
            assertEquals(1, learner.totalEvents(), "the event landed");
            assertEquals("{\"w\":[0.1]}", learner.loadState("edit-switch").orElseThrow());
            assertEquals(0, learner.failedWrites(), "no silent drops");
        }
        assertEquals("1", scalar(dir, "SELECT COUNT(*) FROM learner_event"),
            "learner_event persisted on the store file");
        assertEquals("1", scalar(dir, "SELECT COUNT(*) FROM learner_state"),
            "learner_state persisted on the store file");
    }

    @Test
    void fresh_store_lands_at_latest_with_facet_columns(@TempDir Path dir) throws Exception {
        String id;
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            id = store.put(SymbolFact.of("lesson", "fresh store", Confidence.MEDIUM).build());
        }
        assertEquals(String.valueOf(SchemaMigrations.LATEST),
            scalar(dir, "SELECT version FROM schema_version"), "fresh install is at LATEST");
        assertEquals("java",
            scalar(dir, "SELECT language FROM experience_entry WHERE id = '" + id + "'"),
            "new writes default language=java");
        // Sprint 21b (v3): the skip-unchanged hash column exists on a fresh install.
        assertNull(scalar(dir, "SELECT source_hash FROM experience_entry WHERE id = '" + id + "'"),
            "plain put has no source hash (column exists, value null)");
        // Sprint 28c (v10): the knowledge spine is present on a FRESH install too.
        // Asserting only `version == LATEST` would pass with an entirely empty
        // rung, because writeVersion runs unconditionally — and a fresh install
        // is the majority case, so it is the worst one to leave uncovered.
        for (String column : V10_COLUMNS) {
            assertNull(scalar(dir, "SELECT " + column + " FROM experience_entry WHERE id = '" + id + "'"),
                "v10 column " + column + " exists on a fresh store (value null for a plain put)");
        }
        // The inverse assertion, and it is the one that keeps the scope decision
        // honest: a later edit that quietly re-adds excluded schema goes red here
        // instead of shipping three tables nothing reads.
        for (String table : V10_TABLES_NOT_CREATED) {
            assertFalse(tableExists(dir, table),
                table + " is NOT created by the rescue's v10 — the work it belongs to"
                    + " is out of this sprint, and schema for excluded work is dead schema");
        }
    }

    @Test
    void v1_store_migrates_forward_losslessly(@TempDir Path dir) throws Exception {
        createV1Fixture(dir, "legacy-1");

        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            assertEquals(1L, store.count(), "v1 row survives the migration");
            assertTrue(store.get("legacy-1").isPresent(), "v1 body_json still readable");
            assertEquals("a v2.0.0-era lesson", store.get("legacy-1").get().get("summary"));
        }
        assertEquals(String.valueOf(SchemaMigrations.LATEST),
            scalar(dir, "SELECT version FROM schema_version"));
        assertEquals("java",
            scalar(dir, "SELECT language FROM experience_entry WHERE id = 'legacy-1'"),
            "existing rows backfilled to language=java");
        assertNull(scalar(dir, "SELECT workspace_id FROM experience_entry WHERE id = 'legacy-1'"),
            "pre-facet rows have no provenance (columns exist, values null)");
        assertNull(scalar(dir, "SELECT source_hash FROM experience_entry WHERE id = 'legacy-1'"),
            "v3: pre-hash rows have no source hash (column exists, value null)");

        try (Stream<Path> files = Files.list(dir.resolve("jawata-experience"))) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("experience-pre-migration-v1")),
                "a real migration writes a backup first");
        }
    }

    /** True when the store has such a table — asked of INFORMATION_SCHEMA, so an
     *  absent table is an answer rather than an exception. */
    private static boolean tableExists(Path dir, String table) throws Exception {
        return !"0".equals(scalar(dir,
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = '"
            + table.toUpperCase(java.util.Locale.ROOT) + "'"));
    }

    /** The columns v10 adds — named once, used by the tests below. */
    private static final String[] V10_COLUMNS = {
        "situation", "situation_scope", "verdict",
        "provenance_kind", "form", "evidence_dead"
    };

    /**
     * What v10 deliberately does NOT create, asserted so the scope decision is
     * pinned rather than remembered.
     *
     * <p>An unreleased v10 on the abandoned branch {@code 784a43d} created these
     * three tables for frozen snippets, embodiment links and an advice journal.
     * None of that work is in this sprint, and shipping its schema anyway would
     * put three empty tables in every store with nothing that ever reads them.
     * If a later sprint wants them it adds a NEW rung — editing this one would
     * reach fresh installs only, which is the mistake {@code migrateToV8}
     * exists to document.</p>
     */
    private static final String[] V10_TABLES_NOT_CREATED = {
        "experience_snippet", "experience_embodiment", "advice_event"
    };

    /**
     * Rewind a migrated store to v9 by removing exactly what v10 added.
     *
     * <p>Hand-building a v9 schema would mean a second copy of nine rungs' worth
     * of DDL, which drifts from the real ladder and quietly stops testing it.
     * Migrating forward and stepping one rung back exercises the REAL v10 rung
     * against a store the REAL ladder produced.</p>
     */
    private static void rewindToV9(Path dir) throws Exception {
        try (Connection c = connect(dir); Statement s = c.createStatement()) {
            for (String column : V10_COLUMNS) {
                s.execute("ALTER TABLE experience_entry DROP COLUMN IF EXISTS " + column);
            }
            s.execute("UPDATE schema_version SET version = 9");
        }
    }

    @Test
    void a_v9_store_gains_the_knowledge_spine_and_keeps_every_entry(@TempDir Path dir)
            throws Exception {
        createV1Fixture(dir, "legacy-1");
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            assertEquals(1L, store.count(), "precondition: the fixture row is there");
        }
        rewindToV9(dir);
        assertEquals("9", scalar(dir, "SELECT version FROM schema_version"),
            "precondition: the store is genuinely at v9 before the rung runs");

        // Assert the REPORT, not just its consequences. Opening the store
        // discards the map — H2ExperienceStore reads only `migrated` and logs
        // the rest — so a test that migrates by opening can only infer from/to
        // from the zip's filename and the resulting version. The report is the
        // artifact the exit criterion names, and curation reads it, so it is
        // asserted directly.
        try (Connection c = connect(dir)) {
            java.util.Map<String, Object> report =
                SchemaMigrations.migrate(c, dir.resolve("jawata-experience"));
            assertEquals(9, report.get("from"), "the rung ran FROM v9");
            assertEquals(10, report.get("to"), "and TO v10");
            assertEquals(true, report.get("migrated"), "and it actually did something");
        }

        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            assertEquals(1L, store.count(), "the pre-existing entry survives the rung");
            assertTrue(store.get("legacy-1").isPresent(), "and is still readable");
            assertEquals("a v2.0.0-era lesson", store.get("legacy-1").get().get("summary"),
                "v10 is additive — it must not rewrite a single existing value");
        }

        assertEquals(String.valueOf(SchemaMigrations.LATEST),
            scalar(dir, "SELECT version FROM schema_version"), "the store lands at LATEST");

        for (String column : V10_COLUMNS) {
            assertNull(
                scalar(dir, "SELECT " + column + " FROM experience_entry WHERE id = 'legacy-1'"),
                "a legacy row has no " + column + " — the column exists, the value is null. "
                    + "form being null rather than 1 is what lets retrieval tell the legacy and "
                    + "28c corpora apart without guessing.");
        }
        for (String table : V10_TABLES_NOT_CREATED) {
            assertFalse(tableExists(dir, table),
                table + " is NOT created when a v9 store migrates either");
        }

        try (Stream<Path> files = Files.list(dir.resolve("jawata-experience"))) {
            assertTrue(
                files.anyMatch(p -> p.getFileName().toString()
                    .startsWith("experience-pre-migration-v9")),
                "the ladder backs the file up BEFORE running a real migration — that zip is the "
                    + "only thing between a bad rung and every experience in the store");
        }
    }

    @Test
    void the_situation_column_holds_the_widest_condition_the_corpus_actually_has(
            @TempDir Path dir) throws Exception {
        // D2 measured the applicability text of all 187 pattern READMEs: median 339
        // characters, max 3,314 (abstract-document), four modules over 1024. The column
        // is VARCHAR(4096) for that reason and not by preference — a silently truncated
        // condition would match the WRONG situations, which is worse than a wide column.
        createV1Fixture(dir, "legacy-1");
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            assertEquals(1L, store.count());
        }
        String widest = "x".repeat(3314);
        try (Connection c = connect(dir); Statement s = c.createStatement()) {
            s.execute("UPDATE experience_entry SET situation = '" + widest
                + "' WHERE id = 'legacy-1'");
        }
        assertEquals("3314",
            scalar(dir, "SELECT LENGTH(situation) FROM experience_entry WHERE id = 'legacy-1'"),
            "the widest condition the corpus actually contains must round-trip whole");
    }

    @Test
    void from_the_future_store_is_refused(@TempDir Path dir) throws Exception {
        try (Connection c = connect(dir); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE schema_version (version INT NOT NULL)");
            s.execute("INSERT INTO schema_version VALUES (" + (SchemaMigrations.LATEST + 97) + ")");
        }
        IllegalStateException e =
            assertThrows(IllegalStateException.class, () -> H2ExperienceStore.open(dir));
        assertTrue(e.getMessage().contains("newer"), "refusal names the version mismatch: " + e.getMessage());
    }

    @Test
    void reopen_after_migration_is_idempotent(@TempDir Path dir) throws Exception {
        createV1Fixture(dir, "legacy-2");
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            assertEquals(1L, store.count());
        }
        // Second open: already at LATEST — no re-migration, no second backup.
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            assertEquals(1L, store.count());
        }
        try (Stream<Path> files = Files.list(dir.resolve("jawata-experience"))) {
            assertEquals(1L, files.filter(p -> p.getFileName().toString().endsWith(".zip")).count(),
                "exactly one backup — from the one real migration");
        }
    }

    @Test
    void provenance_facets_stamped_on_write(@TempDir Path dir) throws Exception {
        String id;
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            store.setProvenance("jawata", "/home/x/CursorProjects/jawata-mcp");
            id = store.put(SymbolFact.of("lesson", "stamped", Confidence.MEDIUM).build());
        }
        assertEquals("jawata",
            scalar(dir, "SELECT workspace_id FROM experience_entry WHERE id = '" + id + "'"));
        assertEquals("/home/x/CursorProjects/jawata-mcp",
            scalar(dir, "SELECT project_id FROM experience_entry WHERE id = '" + id + "'"));
    }

    /**
     * Sprint 22b Stage 2 — the jawata-rebrand anchor migration (v4). A store carrying
     * entries anchored to the OLD product packages/classes ({@code org.goja.*},
     * {@code Goja*}/{@code IGoja*}) must have those anchors REWRITTEN — both halves,
     * package prefix AND class segment — for ALL entries, record-sourced included
     * (they exist in no file, so nothing else would ever re-anchor them). The proof is
     * FINDABILITY: recall by the NEW symbol name returns the entry. Foreign anchors
     * (other people's code) are untouched. Link targets that are old-package FQNs
     * rewrite too.
     */
    @Test
    void goja_anchors_are_rewritten_to_jawata_and_recallable_by_new_name(@TempDir Path dir) throws Exception {
        createV1Fixture(dir, "legacy-3");
        try (Connection c = connect(dir); Statement s = c.createStatement()) {
            // (a) a record-sourced entry (no source_ref — exists in NO file): the case the
            // migration exists to protect. Anchored to an old-package class + member.
            s.execute("INSERT INTO experience_entry (id, type, symbol_fqn, package_name, status,"
                + " summary, body_json, created_at, updated_at) VALUES ('rec-goja', 'lesson',"
                + " 'org.goja.mcp.GojaApplication#registerTools', 'org.goja.mcp', 'accepted',"
                + " 'a Cursor-recorded note', '{\"type\":\"lesson\",\"summary\":\"a Cursor-recorded note\"}',"
                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // (b) an interface anchor (the .IGoja half of the class-segment rule).
            s.execute("INSERT INTO experience_entry (id, type, symbol_fqn, status, summary,"
                + " body_json, created_at, updated_at) VALUES ('rec-iface', 'api_contract',"
                + " 'org.goja.mcp.domain.IGojaService', 'accepted', 'iface note',"
                + " '{\"type\":\"api_contract\",\"summary\":\"iface note\"}',"
                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // (c) a FOREIGN anchor — someone else's code: must be untouched.
            s.execute("INSERT INTO experience_entry (id, type, symbol_fqn, package_name, status,"
                + " summary, body_json, created_at, updated_at) VALUES ('orb-1', 'lesson',"
                + " 'com.jats2.pipeline.SlotManager#freeSlot', 'com.jats2.pipeline', 'accepted',"
                + " 'foreign lesson', '{\"type\":\"lesson\",\"summary\":\"foreign lesson\"}',"
                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // (d) a link whose target is an old-package FQN.
            s.execute("INSERT INTO experience_link (entry_id, rel, target) VALUES"
                + " ('rec-goja', 'fixed_by', 'org.goja.mcp.knowledge.SchemaMigrations')");
        }

        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            // THE proof — findability by the NEW name through the recall API:
            var hits = store.query(new RecallQuery(
                "org.jawata.mcp.JawataApplication#registerTools", null, null, null, null));
            assertTrue(hits.stream().anyMatch(e -> e.id().equals("rec-goja")),
                "record-sourced note is recalled by its NEW symbol name after migration");
        }
        assertEquals("org.jawata.mcp.JawataApplication#registerTools",
            scalar(dir, "SELECT symbol_fqn FROM experience_entry WHERE id = 'rec-goja'"),
            "package prefix AND class segment rewritten");
        assertEquals("org.jawata.mcp",
            scalar(dir, "SELECT package_name FROM experience_entry WHERE id = 'rec-goja'"),
            "package anchor rewritten");
        assertEquals("org.jawata.mcp.domain.IJawataService",
            scalar(dir, "SELECT symbol_fqn FROM experience_entry WHERE id = 'rec-iface'"),
            "IGoja interface segment rewritten to IJawata");
        assertEquals("com.jats2.pipeline.SlotManager#freeSlot",
            scalar(dir, "SELECT symbol_fqn FROM experience_entry WHERE id = 'orb-1'"),
            "foreign anchors untouched");
        assertEquals("org.jawata.mcp.knowledge.SchemaMigrations",
            scalar(dir, "SELECT target FROM experience_link WHERE entry_id = 'rec-goja' AND rel = 'fixed_by'"),
            "old-package link target rewritten");
    }

    @Test
    void in_memory_store_migrates_without_backup(@TempDir Path dir) throws Exception {
        // dir==null → in-memory: fresh v0 → LATEST, no file, no backup, still functional.
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            String id = store.put(SymbolFact.of("lesson", "mem", Confidence.LOW).build());
            assertTrue(store.get(id).isPresent());
        }
        assertFalse(Files.exists(dir.resolve("jawata-experience")), "no file artifacts for in-memory");
    }

    /**
     * Sprint 27a D10 (Harald: "Clean for sure!", then option A on the measured
     * cost) — v9, the retroactive admission clean. Misplaced-shaped symptom
     * rows are removed; an item already present in the body is simply dropped
     * (nothing lost — it is already where it belongs); a genuinely absent item
     * is MOVED into details under the [artifacts] marker; prose and plain
     * words stay; the entry row itself is untouched; the framework's
     * pre-migration backup zip is the ruled snapshot; a second open migrates
     * nothing (run-once by version).
     */
    @Test
    void v9_admission_clean_routes_misplaced_symptoms_once_behind_a_backup(@TempDir Path dir)
            throws Exception {
        createV1Fixture(dir, "e1");
        try (Connection c = connect(dir); Statement s = c.createStatement()) {
            // Faithful store shape: the ORIGINAL wording lives in body_json;
            // the table carries alias-NORMALIZED rows (lower/trim/collapse) —
            // which is exactly why the clean classifies the originals: a
            // lowercased CamelCase symbol is indistinguishable from a word.
            s.execute("UPDATE experience_entry SET"
                + " body_json = '{\"details\":\"see client-app/docs/feed.md for the trace\","
                + "\"symptoms\":[\"client-app/docs/feed.md\",\"--enable-verbose\",\"Fix:\","
                + "\"the gaps appeared right at the open\",\"deadlock\"]}'"
                + " WHERE id = 'e1'");
            s.execute("INSERT INTO experience_symptom VALUES"
                + " ('e1', 'client-app/docs/feed.md'),"
                + " ('e1', '--enable-verbose'),"
                + " ('e1', 'fix:'),"
                + " ('e1', 'the gaps appeared right at the open'),"
                + " ('e1', 'deadlock')");
        }

        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            assertEquals(1L, store.count(), "the entry row is untouched by the clean");
        }
        assertEquals("2", scalar(dir, "SELECT COUNT(*) FROM experience_symptom WHERE entry_id = 'e1'"),
            "only prose and the plain word remain as symptom rows");
        assertEquals("1", scalar(dir, "SELECT COUNT(*) FROM experience_symptom"
            + " WHERE entry_id = 'e1' AND symptom = 'the gaps appeared right at the open'"),
            "prose kept");
        assertEquals("1", scalar(dir, "SELECT COUNT(*) FROM experience_symptom"
            + " WHERE entry_id = 'e1' AND symptom = 'deadlock'"), "plain word kept");
        String body = scalar(dir, "SELECT body_json FROM experience_entry WHERE id = 'e1'");
        assertTrue(body.contains("[artifacts]"), "absent items moved under the MARKER: " + body);
        assertTrue(body.contains("--enable-verbose") && body.contains("Fix:"),
            "the two absent items are IN details — moved, not deleted: " + body);
        assertFalse(body.substring(body.indexOf("[artifacts]")).contains("feed.md"),
            "the path already present in the body is dropped, not duplicated: " + body);
        assertFalse(body.contains("\"client-app/docs/feed.md\""),
            "body_json's own symptom list is rewritten too — a backup restore"
            + " must not reintroduce the junk: " + body);
        assertTrue(Files.exists(dir.resolve("jawata-experience")
                .resolve("experience-pre-migration-v1.zip")),
            "the pre-clean snapshot (the framework's backup zip) exists");

        // Run-once: a second open finds LATEST, migrates nothing, changes nothing.
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            assertEquals(1L, store.count());
        }
        assertEquals("2", scalar(dir, "SELECT COUNT(*) FROM experience_symptom WHERE entry_id = 'e1'"),
            "second open moves 0 — the clean ran once");
        assertEquals(String.valueOf(SchemaMigrations.LATEST),
            scalar(dir, "SELECT version FROM schema_version"));
    }
}
