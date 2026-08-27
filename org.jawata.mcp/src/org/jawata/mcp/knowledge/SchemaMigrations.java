package org.jawata.mcp.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sprint 21a (item B): versioned schema + ordered, additive-first migrations for the
 * experience store — replacing the v2.0.0 implicit {@code CREATE TABLE IF NOT EXISTS}
 * contract, which creates a fresh store correctly but can never evolve an existing one.
 *
 * <p>Version detection: a {@code schema_version} table wins; a pre-versioning v2.0.0
 * store (has {@code experience_entry}, no version table) is version 1; an empty database
 * is version 0. A store whose version is <em>newer</em> than {@link #LATEST} is refused
 * read-write (fail clearly, don't corrupt). A real migration (version ≥ 1) writes an H2
 * online {@code BACKUP TO} zip beside the store file first, so a failed step is
 * recoverable.</p>
 *
 * <p>Migration steps are cumulative: v1 = the v2.0.0 base schema; v2 = the Sprint-21a
 * provenance + language facets ({@code workspace_id}, {@code project_id},
 * {@code language} — backfilled {@code 'java'}, see item I); v3 = the Sprint-21b
 * {@code source_hash} (skip-unchanged loads — an unmodified memory file causes no
 * write, so repeated loads stop growing the MVStore file); v4 = the Sprint-22b jawata
 * rebrand — code anchors REWRITTEN ({@code org.goja.*} → {@code org.jawata.*} package
 * prefix AND {@code Goja*}/{@code IGoja*} → {@code Jawata*}/{@code IJawata*} class
 * segments) across {@code symbol_fqn}, {@code package_name} and FQN link targets, for
 * ALL entries — record-sourced ones exist in no file, so nothing else would ever
 * re-anchor them; superseding instead of rewriting would kill their findability.</p>
 */
final class SchemaMigrations {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrations.class);

    /** Current schema version — bump together with a new {@code migrateToVn} step. */
    static final int LATEST = 15;

    private SchemaMigrations() {
    }

    /**
     * Bring the connected database to {@link #LATEST}. Returns a report
     * ({@code from}/{@code to}/{@code migrated}/{@code backup}). {@code storeDir} is the
     * directory holding the store file ({@code null} for in-memory — no backup possible).
     *
     * @throws IllegalStateException when the store is from a newer resident.
     */
    static Map<String, Object> migrate(Connection conn, Path storeDir) throws SQLException {
        int from = detectVersion(conn);
        if (from > LATEST) {
            throw new IllegalStateException("experience store schema is v" + from
                + ", newer than this resident supports (v" + LATEST
                + ") — refusing read-write open; upgrade jawata or use the newer resident");
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("from", from);
        report.put("to", LATEST);
        if (from == LATEST) {
            report.put("migrated", false);
            return report;
        }
        if (from >= 1 && storeDir != null) {
            Path backup = storeDir.resolve("experience-pre-migration-v" + from + ".zip");
            try (Statement s = conn.createStatement()) {
                s.execute("BACKUP TO '" + backup.toAbsolutePath().toString().replace("'", "''") + "'");
            }
            report.put("backup", backup.toString());
            log.info("Experience store backup before migration v{} -> v{}: {}", from, LATEST, backup);
        }
        if (from < 1) {
            migrateToV1(conn);
        }
        if (from < 2) {
            migrateToV2(conn);
        }
        if (from < 3) {
            migrateToV3(conn);
        }
        if (from < 4) {
            migrateToV4(conn);
        }
        if (from < 5) {
            migrateToV5(conn);
        }
        if (from < 6) {
            migrateToV6(conn);
        }
        if (from < 7) {
            migrateToV7(conn);
        }
        if (from < 8) {
            migrateToV8(conn);
        }
        if (from < 9) {
            migrateToV9(conn);
        }
        if (from < 10) {
            migrateToV10(conn);
        }
        if (from < 11) {
            migrateToV11(conn);
        }
        if (from < 12) {
            migrateToV12(conn);
        }
        if (from < 13) {
            migrateToV13(conn);
        }
        if (from < 14) {
            migrateToV14(conn);
        }
        if (from < 15) {
            migrateToV15(conn);
        }
        writeVersion(conn, LATEST);
        report.put("migrated", true);
        log.info("Experience store schema migrated v{} -> v{}", from, LATEST);
        return report;
    }

    /** 0 = fresh; 1 = pre-versioning v2.0.0 schema; else the {@code schema_version} row. */
    static int detectVersion(Connection conn) throws SQLException {
        if (tableExists(conn, "SCHEMA_VERSION")) {
            try (Statement s = conn.createStatement();
                    ResultSet rs = s.executeQuery("SELECT version FROM schema_version")) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
            return 0;      // version table exists but empty — treat as fresh
        }
        return tableExists(conn, "EXPERIENCE_ENTRY") ? 1 : 0;
    }

    private static boolean tableExists(Connection conn, String upperName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?")) {
            ps.setString(1, upperName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /** v1 — the v2.0.0 base schema (verbatim; was {@code H2ExperienceStore.initSchema}). */
    private static void migrateToV1(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
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
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_source ON experience_entry(source_ref)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_type ON experience_entry(type)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_symbol ON experience_entry(symbol_fqn)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_status ON experience_entry(status)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_operation ON experience_entry(operation)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_scope_kind ON experience_entry(scope_kind)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_ext_system ON experience_entry(external_system)");
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
        }
    }

    /**
     * v2 — Sprint 21a facets: provenance ({@code workspace_id}, {@code project_id} — item H
     * merge-ability) + {@code language} (item I multi-language guard; existing rows are
     * Java-era, backfilled {@code 'java'}).
     */
    private static void migrateToV2(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE experience_entry ADD COLUMN IF NOT EXISTS workspace_id VARCHAR(256)");
            s.execute("ALTER TABLE experience_entry ADD COLUMN IF NOT EXISTS project_id VARCHAR(1024)");
            s.execute("ALTER TABLE experience_entry ADD COLUMN IF NOT EXISTS language VARCHAR(32)");
            s.execute("UPDATE experience_entry SET language = 'java' WHERE language IS NULL");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_language ON experience_entry(language)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_workspace ON experience_entry(workspace_id)");
        }
    }

    /** v3 (Sprint 21b): content hash of the ingested memory file — lets {@code load}
     *  skip unchanged sources without any write (NULL = unknown, next load rewrites once). */
    private static void migrateToV3(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE experience_entry ADD COLUMN IF NOT EXISTS source_hash VARCHAR(64)");
        }
    }

    /**
     * v4 (Sprint 22b, the jawata rebrand): REWRITE the old product's code anchors to the
     * renamed coordinates — never supersede-without-replacement (that preserves content
     * but kills findability, and record-sourced entries have no file to re-anchor from).
     * Both halves per anchor: the package prefix {@code org.goja.} → {@code org.jawata.}
     * and the class segments {@code .IGoja} → {@code .IJawata} / {@code .Goja} →
     * {@code .Jawata}. Applies to {@code symbol_fqn}, {@code package_name} and
     * old-package {@code experience_link.target} FQNs. Foreign anchors (any other
     * codebase) never match the {@code org.goja.} guards and are untouched.
     * The migration itself must contain the OLD names — it is the code that rewrites
     * them (grep-contract exception class 3).
     */
    private static void migrateToV4(Connection conn) throws SQLException {
        String rewrite = "REPLACE(REPLACE(REPLACE(%s, 'org.goja.', 'org.jawata.'),"
            + " '.IGoja', '.IJawata'), '.Goja', '.Jawata')";
        try (Statement s = conn.createStatement()) {
            s.execute("UPDATE experience_entry SET symbol_fqn = " + rewrite.formatted("symbol_fqn")
                + " WHERE symbol_fqn LIKE 'org.goja.%'");
            s.execute("UPDATE experience_entry SET package_name = "
                + "REPLACE(package_name, 'org.goja', 'org.jawata')"
                + " WHERE package_name = 'org.goja' OR package_name LIKE 'org.goja.%'");
            s.execute("UPDATE experience_link SET target = " + rewrite.formatted("target")
                + " WHERE target LIKE 'org.goja.%'");
        }
    }

    /**
     * v5 (Sprint 26): the injector's learning layer — {@code learner_event}, the
     * continuous label stream (one row per immediate signal: a tool error, an
     * undo, a mechanical touch, a gate call, a compile-failure after a touched
     * file), session-scoped; and {@code learner_state}, each learner's
     * serialized model + rolling record. Sibling tables on the same store file
     * so learner data inherits the store's per-workspace provenance, backup and
     * privacy boundary (local only — nothing crosses).
     */
    private static void migrateToV5(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS learner_event ("
                + "id IDENTITY PRIMARY KEY, "
                + "ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "session_id VARCHAR(64), "
                + "kind VARCHAR(40) NOT NULL, "
                + "tool VARCHAR(80), "
                + "detail_json CLOB, "
                + "workspace_id VARCHAR(80), "
                + "project_id VARCHAR(80))");
            s.execute("CREATE INDEX IF NOT EXISTS idx_learner_event_kind"
                + " ON learner_event(kind)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_learner_event_session"
                + " ON learner_event(session_id)");
            s.execute("CREATE TABLE IF NOT EXISTS learner_state ("
                + "learner VARCHAR(60) PRIMARY KEY, "
                + "state_json CLOB, "
                + "updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    /**
     * v6 (Sprint 26a, D2): the experience loop's capture lane —
     * {@code tool_experience}, one row per SELECTIVE outcome-bearing event (a
     * mutate's compile result, or a tool error; a jawata-fallback is captured
     * studio-side as a {@code failure_mode} entry, not here). {@code situation} is
     * the keyword-rich key baseline retrieval matches on (Sprint 27 adds an
     * embedding column beside it). A sibling table on the same store file, so it
     * inherits the store's per-workspace provenance, backup and privacy boundary
     * (local only — nothing crosses).
     */
    private static void migrateToV6(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS tool_experience ("
                + "id IDENTITY PRIMARY KEY, "
                + "ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "session_id VARCHAR(64), "
                + "situation VARCHAR(1024), "
                + "tool VARCHAR(80) NOT NULL, "
                + "outcome VARCHAR(20) NOT NULL, "
                + "detail_json CLOB, "
                + "workspace_id VARCHAR(80), "
                + "project_id VARCHAR(80))");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tool_experience_tool"
                + " ON tool_experience(tool)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tool_experience_outcome"
                + " ON tool_experience(outcome)");
        }
    }

    /**
     * v7 (Sprint 27, D2): the semantic-recall lane — an embedding vector and the
     * identity that produced it, on BOTH the knowledge entries and the
     * {@code tool_experience} rows.
     *
     * <p>Purely additive: existing rows get NULLs, which is the honest state
     * ("not embedded yet") and is what the backfill looks for. Nothing already
     * stored is rewritten, so a v6 store keeps working exactly as before if the
     * embedder never runs.</p>
     *
     * <p>{@code embedder_identity} is stored beside every vector rather than
     * once for the database, because a store outlives model changes: vectors
     * from different identities must be distinguishable per row so the stale
     * ones can be found and re-embedded, and so a comparison across identities
     * can be REFUSED rather than silently producing a meaningless score.</p>
     */
    private static void migrateToV7(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE experience_entry ADD COLUMN IF NOT EXISTS embedding BLOB");
            s.execute("ALTER TABLE experience_entry ADD COLUMN IF NOT EXISTS embedder_identity VARCHAR(128)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_embedder ON experience_entry(embedder_identity)");
            s.execute("ALTER TABLE tool_experience ADD COLUMN IF NOT EXISTS embedding BLOB");
            s.execute("ALTER TABLE tool_experience ADD COLUMN IF NOT EXISTS embedder_identity VARCHAR(128)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tool_experience_embedder"
                + " ON tool_experience(embedder_identity)");
        }
    }

    /**
     * v3.4.1 — the quality ledger's counter table.
     *
     * <p>It was originally added INSIDE {@link #migrateToV7}, on the reasoning
     * that the ledger needed no schema epoch of its own. That reasoning was
     * wrong in a way only an existing store reveals: the ladder runs a rung
     * only when {@code from < n}, so a store ALREADY at v7 — which is every
     * store any Sprint-27 build had opened — never re-ran v7 and therefore
     * never got the table. The ledger then recorded nothing, for exactly the
     * stores that had history worth measuring.</p>
     *
     * <p>The rule this cost us: <b>additive DDL added to an already-released
     * migration reaches new databases only.</b> Changing an existing rung
     * changes what a FRESH install gets; reaching installed bases needs a new
     * rung. Always.</p>
     *
     * <p>One narrow table: a counter name and its count. Deliberately NOT an
     * event log — 27's boundary is read-only measurement, and a per-event table
     * would invite exactly the analysis Sprint 33 is meant to decide on
     * evidence rather than inherit as machinery.</p>
     */
    private static void migrateToV8(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS quality_counter ("
                + "name VARCHAR(160) PRIMARY KEY, count BIGINT NOT NULL DEFAULT 0)");
        }
    }

    /**
     * v9 — Sprint 27a D10, the retroactive admission clean (Harald's rulings:
     * "Clean for sure!", then option A after the pre-execution measurement —
     * the 11→10 calibration cost accepted; dossier-27a).
     *
     * <p>Symptom rows whose shape the derived classifier
     * ({@link AdmissionPolicy}, mirroring the committed
     * {@code embed-goldens/derive_admission.py}) marks MISPLACED — paths,
     * flags, headings, code symbols, bare ids, tag slugs, the Sprint-21c
     * keyword harvest's residue — are removed from {@code experience_symptom}.
     * Nothing is deleted from the store's knowledge: 98.3% of these items
     * occur verbatim in the entry's summary/details already (the harvest was
     * duplication by construction); the genuinely absent rest is appended to
     * the entry's details under an {@code [artifacts]} marker. Prose and
     * single plain words stay. Summaries are NOT rewritten (that is
     * authorship). Mechanism choice recorded in dossier-27a: a migration rung
     * reaches every store exactly once with no invocation to remember, and
     * the framework's pre-migration {@code BACKUP TO} zip is the ruled
     * pre-clean snapshot.</p>
     */
    private static void migrateToV9(Connection conn) throws SQLException {
        // The symptom TABLE holds alias-NORMALIZED rows (lower/trim/collapse),
        // which destroys the camelCase evidence the CODE shape needs; the
        // ORIGINAL wording lives in body_json. So: classify the ORIGINALS
        // (exact parity with the measured clean, derive_admission.py), map
        // each misplaced original to its normalized table row for deletion,
        // and rewrite body_json's own symptom list — otherwise a
        // backup-restore would reintroduce the junk the clean removed.
        com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();
        record Entry(String id, String summary, String bodyJson) {
        }
        java.util.List<Entry> entries = new java.util.ArrayList<>();
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery(
                    "SELECT id, summary, body_json FROM experience_entry")) {
            while (rs.next()) {
                entries.add(new Entry(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        }
        int misplacedItems = 0;
        int entriesTouched = 0;
        int dropped = 0;
        int moved = 0;
        int rowsDeleted = 0;
        for (Entry e : entries) {
            com.fasterxml.jackson.databind.node.ObjectNode body;
            try {
                body = e.bodyJson() == null || e.bodyJson().isBlank()
                    ? json.createObjectNode()
                    : (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(e.bodyJson());
            } catch (java.io.IOException | ClassCastException ex) {
                log.warn("admission clean: entry {} body_json unreadable — symptoms kept", e.id());
                continue;
            }
            if (!(body.get("symptoms") instanceof com.fasterxml.jackson.databind.node.ArrayNode originals)
                    || originals.isEmpty()) {
                continue;
            }
            java.util.List<String> kept = new java.util.ArrayList<>();
            java.util.List<String> misplaced = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode n : originals) {
                String item = n.asText();
                if (AdmissionPolicy.misplaced(AdmissionPolicy.classify(item))) {
                    misplaced.add(item);
                } else {
                    kept.add(item);
                }
            }
            if (misplaced.isEmpty()) {
                continue;
            }
            entriesTouched++;
            misplacedItems += misplaced.size();
            String details = body.path("details").asText("");
            String hay = ((e.summary() == null ? "" : e.summary()) + " " + details).toLowerCase();
            java.util.List<String> absent = new java.util.ArrayList<>();
            for (String item : misplaced) {
                // EDGE backticks only (C4b audit F2): the body keeps its inner
                // backticks, so removing them from the key manufactures false
                // absents — measured 555 vs the derivation's 176 on identical
                // data. This mirrors derive_admission.py's rule exactly; the
                // accepted 11→10 cost was measured on that shape.
                String key = item.strip();
                while (key.startsWith("`")) {
                    key = key.substring(1);
                }
                while (key.endsWith("`")) {
                    key = key.substring(0, key.length() - 1);
                }
                key = key.strip().toLowerCase();
                if (!key.isEmpty() && !hay.contains(key)) {
                    absent.add(item);
                }
            }
            com.fasterxml.jackson.databind.node.ArrayNode keptArr = body.putArray("symptoms");
            kept.forEach(keptArr::add);
            if (!absent.isEmpty()) {
                body.put("details", (details + "\n[artifacts] "
                    + String.join("; ", absent)).strip());
                moved += absent.size();
            }
            dropped += misplaced.size() - absent.size();
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE experience_entry SET body_json = ? WHERE id = ?")) {
                ps.setString(1, body.toString());
                ps.setString(2, e.id());
                ps.executeUpdate();
            }
            // Collision guard (C4b audit F7b): a kept item and a misplaced one
            // can NORMALIZE to the same row ("FooBar" and "foobar" share
            // "foobar") — deleting it would take the kept item's row with it.
            java.util.Set<String> keptNormalized = new java.util.HashSet<>();
            for (String item : kept) {
                keptNormalized.add(H2ExperienceStore.normalize(item));
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM experience_symptom WHERE entry_id = ? AND symptom = ?")) {
                for (String item : misplaced) {
                    String norm = H2ExperienceStore.normalize(item);
                    if (keptNormalized.contains(norm)) {
                        continue;
                    }
                    ps.setString(1, e.id());
                    ps.setString(2, norm);
                    ps.addBatch();
                }
                for (int n : ps.executeBatch()) {
                    rowsDeleted += Math.max(0, n);
                }
            }
        }
        log.info("admission clean (v9): {} misplaced symptom item(s) on {} entr(ies) — "
            + "{} already present in the body (row removed), {} moved into details "
            + "under [artifacts]; {} normalized table row(s) deleted; prose and plain "
            + "words kept; summaries untouched",
            misplacedItems, entriesTouched, dropped, moved, rowsDeleted);
    }

    /**
     * v10 — Sprint 28c, the experience form.
     *
     * <p>An entry becomes an EXPERIENCE rather than a note: <i>situation</i> (the
     * condition under which it applies), <i>principle</i> (today's {@code summary}),
     * <i>outcome</i> ({@code verdict}: worked / failed_avoid / unproven) and
     * <i>provenance</i> as a FIELD rather than as content.</p>
     *
     * <p><b>The point of this rung is that none of it is a code address.</b> An
     * experience is identified by its own id and described by its own situation, so
     * losing a symbol, a package or an operation cannot make it unreachable — which
     * is the sprint's acceptance sentence: "Experience is experience without any
     * code!" {@code evidence_dead} exists for exactly that case: when optional code
     * provenance stops resolving, the entry is FLAGGED for a human, never retired.</p>
     *
     * <p><b>Additive and entirely nullable, deliberately.</b> A v9 store keeps
     * answering mid-rollout, an old export still imports (its absent columns read as
     * legacy), and {@code form} — null for legacy, 1 for the 28c shape — lets
     * retrieval and curation tell the two corpora apart without guessing. Nothing
     * here is rewritten by this rung; the form transform is a separate,
     * confirm-gated verb, because deriving a situation from stored prose is
     * authorship-adjacent and owes the user a reviewable report first.</p>
     *
     * <p>Heeding v8's lesson (see {@link #migrateToV8}): this is a NEW rung, not an
     * edit to a released one, so it reaches installed stores and not only fresh
     * ones.</p>
     *
     * <p>{@code situation} is VARCHAR(4096), not the 1024 first drafted, and the
     * size is measured rather than chosen: the corpus analysis measured the
     * applicability text of all 187 pattern READMEs at median 339 characters but max
     * 3,314 ({@code abstract-document}), with four modules over 1024. A column that
     * silently truncated a condition would produce entries that match the wrong
     * situations — worse than one that is a little wide.</p>
     *
     * <p><b>DIVERGENCE FROM THE ABANDONED BRANCH, stated so a table-set comparison
     * is not a surprise.</b> An earlier, unreleased v10 on the abandoned branch
     * {@code 784a43d} also created {@code experience_snippet}, {@code
     * experience_embodiment} and {@code advice_event}, and three further columns
     * ({@code verdict_version}, {@code capability}, {@code situation_scope}). None of
     * that is created here:
     * frozen snippets, embodiment links and the advice journal are out of this
     * sprint's scope, {@code verdict_version} existed only to bind a verdict to a
     * snippet version, and {@code capability} only to carry a catalogue label this
     * sprint does not assign. Shipping schema for excluded work is how dead columns
     * are born. A store that was opened by one of those unreleased builds already
     * reports version 10 and therefore SKIPS this rung entirely: it keeps the extra
     * tables as inert leftovers, and the code here never reads them.</p>
     */
    private static void migrateToV10(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE experience_entry ADD COLUMN IF NOT EXISTS situation VARCHAR(4096)");
            s.execute("ALTER TABLE experience_entry ADD COLUMN IF NOT EXISTS verdict VARCHAR(16)");
            s.execute("ALTER TABLE experience_entry ADD COLUMN IF NOT EXISTS provenance_kind VARCHAR(32)");
            s.execute("ALTER TABLE experience_entry ADD COLUMN IF NOT EXISTS form INT");
            s.execute("ALTER TABLE experience_entry ADD COLUMN IF NOT EXISTS evidence_dead BOOLEAN");
        }
    }

    /**
     * v11 — Sprint 28c D13, one vector per FIELD instead of one per entry.
     *
     * <p>Retrieval learns to weigh <i>where</i> a question matched. Until now an
     * entry was reduced to a single vector over situation + summary + details
     * concatenated, so a question that paraphrased an entry's SITUATION almost
     * verbatim scored no differently from one that brushed its body — the three
     * fields were averaged into each other before anything could tell them
     * apart. These three columns hold the same entry embedded per field, and
     * {@link RelevanceMerge} weighs them.</p>
     *
     * <p><b>Three columns, not a per-field row in a side table, and not an
     * identity suffix on one shared column.</b> Separate columns make the three
     * embedding spaces impossible to mix by construction rather than by
     * convention: no code path can write a summary vector where a situation
     * vector is read, because they are different columns. The design addendum
     * sketched a suffixed shared lane; that would have re-created, inside a
     * single column, exactly the mixing {@code EmbedderIdentity} exists to
     * forbid. The artifact is amended to match rather than left to disagree with
     * the code.</p>
     *
     * <p><b>The rung alone fills nothing, which is why the identity is bumped
     * with it.</b> An upgraded v10 row already carries a current-identity vector
     * in {@code embedding}, and the backfill selects on identity — so it would
     * pass over that row forever and the three lanes would stay null on every
     * existing row while only new rows got them.
     * {@code EmbedderIdentity.CURRENT_VERSION} goes to 3 in the same change,
     * which is the mechanism that already exists for "our pipeline now produces
     * different vectors for the same row", and {@code EmbeddingServiceTest} pins
     * the pair so the bump cannot be forgotten.</p>
     *
     * <p>Additive and nullable, like v10: a lane is legitimately absent when the
     * entry has no such field (a legacy row declares no situation) or when the
     * embedder was unavailable at write time. {@link RelevanceMerge} scores an
     * absent lane as zero and does not renormalise, so the absence costs the row
     * rank rather than being papered over.</p>
     */
    private static void migrateToV11(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE experience_entry "
                + "ADD COLUMN IF NOT EXISTS embedding_situation VARBINARY(8192)");
            s.execute("ALTER TABLE experience_entry "
                + "ADD COLUMN IF NOT EXISTS embedding_summary VARBINARY(8192)");
            s.execute("ALTER TABLE experience_entry "
                + "ADD COLUMN IF NOT EXISTS embedding_details VARBINARY(8192)");
        }
    }

    /**
     * v12 — Sprint 28c D14, what was shown, what was chosen, and what was asked
     * for and not found.
     *
     * <p>Two tables and one column, and the split between them is the design
     * rather than tidiness.</p>
     *
     * <p><b>{@code usage_entry} is a separate table so that ranking cannot read
     * it.</b> D14's rule is that usage decides DELETION and never order — an
     * entry shown a hundred times and chosen never is a deletion candidate, not
     * a demoted one. Held as columns on {@code experience_entry} the counters
     * would sit inside {@code ALL_COLUMNS}, one careless {@code ORDER BY} away
     * from becoming a popularity ranking, and nothing would fail when that
     * happened. In their own table the mistake has to be typed as a join,
     * deliberately. Same reasoning as v11's separate vector columns: make the
     * wrong thing impossible to reach rather than merely discouraged.</p>
     *
     * <p><b>It cascades, because a count against a deleted entry is not a
     * number about anything.</b> A reseed replaces every row and mints new ids;
     * without the cascade each rebuild would leave a full set of orphaned
     * counters behind, invisible and permanent.</p>
     *
     * <p><b>{@code usage_query} deliberately does NOT cascade, and deliberately
     * is not touched by a wipe.</b> It is the demand record — the question asked
     * and whether anything was chosen — and a question that got no answer is the
     * one piece of evidence here that must OUTLIVE the corpus it failed against.
     * Repeatedly-unanswered situations are the writing backlog, and a rebuild
     * that erased them would erase precisely the instruction for what to write
     * next. So it carries no foreign key: it is about the asking, not about any
     * entry.</p>
     *
     * <p>The demand row is keyed by the nomination's own query id rather than by
     * a generated number, because the row is OPENED when candidates are shown
     * and CLOSED when the caller decides — two calls, minutes apart, that have
     * nothing in common but that id. A nomination nobody ever decides simply
     * stays open with {@code chosen} false, which is the truthful reading:
     * demand that was never converted.</p>
     *
     * <p><b>{@code origin_client} is deliberately NOT here.</b> D14 wants it, and
     * it is a property of the entry, so this rung is where it looks like it
     * belongs — but nothing writes it yet, and a column only round-trips if the
     * insert, the export projection, {@code importEntries} and {@code importFrom}
     * all carry it. This sprint has already shipped that gap three times. The
     * column arrives in the change that FILLS it, with its four carriage sites
     * and one test, rather than sitting here as half a contract waiting to be
     * silently dropped by the first backup somebody takes.
     * <b>That change is {@link #migrateToV13}</b> — Harald ruled BUILD on
     * 2026-08-26, and the condition this paragraph set is the one it meets.</p>
     */
    private static void migrateToV12(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS usage_entry ("
                + "entry_id VARCHAR(64) PRIMARY KEY, "
                + "shown BIGINT NOT NULL DEFAULT 0, "
                + "chosen BIGINT NOT NULL DEFAULT 0, "
                + "last_shown TIMESTAMP, "
                + "last_chosen TIMESTAMP, "
                + "CONSTRAINT fk_usage_entry FOREIGN KEY (entry_id) "
                + "REFERENCES experience_entry(id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS usage_query ("
                + "query_id VARCHAR(64) PRIMARY KEY, "
                + "asked_at TIMESTAMP NOT NULL, "
                + "cue_kind VARCHAR(32), "
                + "question VARCHAR(4096) NOT NULL, "
                + "shown_count INT NOT NULL, "
                + "chosen BOOLEAN NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_usage_query_unanswered "
                + "ON usage_query(chosen, asked_at)");
        }
    }

    /**
     * v13 — {@code origin_client}: which client recorded this entry.
     *
     * <p>D14 wants it and {@link #migrateToV12}'s note refused to carry it
     * there, on the rule that a column only round-trips if every site spelling
     * the row shape carries it — a gap this sprint shipped three times. So the
     * column arrives HERE together with all of them: {@code ALL_COLUMNS},
     * {@code mapRow}/{@code facetsOf}, {@code exportEntries},
     * {@code importEntries} and {@code importFrom} (gated on the orphan's own
     * version), plus its writer (the EventTap stamper) and its reader
     * ({@code stats.by_origin_client}). {@code insert} deliberately does NOT
     * carry it: the stamp is applied post-insert by the tap, because the tool
     * that inserts never sees the session — an INSERT-side value could only
     * ever be a guess.</p>
     *
     * <p>The value is {@code ClientDirectory}'s CLOSED vocabulary
     * ({@code claude_code}, {@code cursor}, …, {@code unknown}) — never the raw
     * client string, so nothing a client puts in its own name can leak into the
     * store. NULL and {@code 'unknown'} are different facts and neither side
     * may collapse them: NULL means <b>nothing ever stamped this row</b>
     * (pre-v13, or a surface with no session); {@code 'unknown'} means the
     * stamper ran and the session's client was not identified.</p>
     */
    private static void migrateToV13(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE experience_entry"
                + " ADD COLUMN IF NOT EXISTS origin_client VARCHAR(64)");
        }
    }

    /**
     * v14 — {@code experience_tombstone}: sources deliberately removed from THIS store.
     *
     * <p>Measured cause (2026-08-27): the studio's deploy-time auto-seed crawls the
     * default memory roots with {@code sourceUnchanged} as its only brake — and that
     * check asks the STORE. After a deliberate wipe/reseed the store is empty, so
     * every previously-removed source reads as "new" and the whole legacy corpus
     * re-enters on the next deploy (413 rows re-imported on 2026-08-26, one more on
     * 2026-08-27). Curation could not stick, on any machine, for any user.</p>
     *
     * <p>A tombstone is keyed on {@code source_ref} ALONE — no content hash — because
     * it records a decision about the SOURCE ("this file is not part of this store"),
     * not about one version of its content. A hash-keyed tombstone would silently
     * expire the moment the file was edited, re-polluting gradually. Revival is a
     * deliberate act with the same weight as the removal: a reseed of a root that
     * CONTAINS the file ingests it and clears its tombstone.</p>
     *
     * <p>Tombstones are DERIVED, machine-local state — regenerated by each store's own
     * reseed — so export/import deliberately does not carry them; the export carries
     * knowledge, and a second machine's reseed produces its own tombstones from the
     * same substrate.</p>
     */
    private static void migrateToV14(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS experience_tombstone ("
                + "source_ref VARCHAR(1024) PRIMARY KEY, "
                + "reason VARCHAR(1024), "
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    /**
     * v15 — {@code cause}: the DIAGNOSIS, first-class.
     *
     * <p>The knowledge triad is situation → complication → solution, and the
     * complication (the cause) had no column: it lived inside summary/details
     * prose, unqueryable. Ruled first-class on 2026-08-27 ("this should not be
     * buried somewhere"). The distinction it carries: a SYMPTOM is the
     * observable (a fast heartbeat), the CAUSE is the diagnosed problem behind
     * it (running / a heart attack / a virus) — one symptom, many causes, and
     * the solution binds to the cause, never to the symptom. A symptom-recall
     * returning several entries is therefore a DIFFERENTIAL, and this column
     * is what discriminates between them.</p>
     *
     * <p>Arrives with its full delivery per the v13 rule: builder + insert +
     * {@code ALL_COLUMNS} + {@code facetsOf} + export + import + orphan import
     * (version-gated) + the record verb and the md ingest as writers + the
     * recall render as reader. Population of legacy rows is EDITORIAL work —
     * mechanically splitting a cause out of summary prose would invent
     * sentences, which the form migration already refused once.</p>
     */
    private static void migrateToV15(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE experience_entry"
                + " ADD COLUMN IF NOT EXISTS cause VARCHAR(2048)");
        }
    }

    private static void writeVersion(Connection conn, int version) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_version (version INT NOT NULL)");
            s.execute("DELETE FROM schema_version");
            s.execute("INSERT INTO schema_version VALUES (" + version + ")");
        }
    }
}
