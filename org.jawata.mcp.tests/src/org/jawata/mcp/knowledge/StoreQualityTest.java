package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28c Stage 15 — the quality scan produces NO THIRD NUMBER.
 *
 * <p>The corpus already has one diagnosis: {@code migrate_form}'s dry run.
 * {@link StoreQuality} runs that same plan and re-projects it, so the two
 * cannot disagree — and this test pins the reconciliation on a store where the
 * counts are known, so if someone later gives StoreQuality its own classifier
 * the drift goes red here rather than surfacing as two arguing reports.</p>
 *
 * <p>What the scan ADDS is the {@code source_ref} per finding — the one fact
 * the dispositions lack: an ingested entry is durably fixed in its FILE (a
 * store write is erased by the next reseed), a directly-recorded one only in
 * the store. The caller must be able to tell which repair is durable.</p>
 */
class StoreQualityTest {

    private ObjectMapper mapper;
    private H2ExperienceStore store;
    private ExperienceTool tool;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    /**
     * A FORMLESS row, written through the store API — deliberately not through
     * {@code record}, which now refuses a formless experience (the first
     * version of this fixture proved that by failing). Formless rows exist in
     * reality only as LEGACY: written before the gate stood. The store API is
     * how they got in, so it is how the fixture puts them in.
     */
    private void legacyRow(String type, String summary) {
        store.put(SymbolFact.of(type, summary, Confidence.HIGH).build());
    }

    @Test
    void the_scan_reconciles_with_the_migration_dry_run_by_construction() {
        // One formless failure_mode (a finding: experience-typed, nothing to
        // derive from) and one domain_fact (kept, but correctly out of scope —
        // still listed, because "no situation" on a fact is a wanted absence
        // only when the READER can see the type beside it).
        legacyRow("failure_mode", "the ledger loses a fill when the amend races the cancel");
        legacyRow("domain_fact", "the settlement window closes an hour before the venue");

        FormMigration.Report dryRun = new FormMigration(store).plan();
        StoreQuality.Report scan = StoreQuality.scan(store, 50);

        assertEquals(dryRun.sourceEntries(), scan.entries(),
            "same corpus, same total — a third number would be a second classifier");
        assertEquals(dryRun.migrated(), scan.mechanicallyMigratable());

        int keptExcludingHealthy = dryRun.keptReasons().entrySet().stream()
            .filter(e -> !FormMigration.REASON_ALREADY_FORM_1.equals(e.getKey())
                && !FormMigration.REASON_FACT_WITH_SITUATION.equals(e.getKey()))
            .mapToInt(Map.Entry::getValue).sum();
        assertEquals(keptExcludingHealthy, scan.findingsTotal(),
            "the findings ARE the dry run's legacy_kept rows (minus the healthy"
                + " classes, by the SHARED constants), re-projected — never a"
                + " separate count");
        for (String reason : scan.defects().keySet()) {
            assertTrue(dryRun.keptReasons().containsKey(reason),
                () -> "a defect class the dry run does not know is a third taxonomy: "
                    + reason);
        }
    }

    @Test
    void a_directly_recorded_finding_carries_a_null_source_ref_as_a_fact(){
        legacyRow("failure_mode", "the ledger loses a fill when the amend races the cancel");
        StoreQuality.Report scan = StoreQuality.scan(store, 10);
        assertEquals(1, scan.findingsTotal());
        StoreQuality.Finding f = scan.findings().get(0);
        assertNull(f.sourceRef(),
            "no file exists behind a direct record — null says the STORE is where"
                + " the durable fix lives, and set_form is that fix");
        assertNotNull(f.summary(), "the finding carries what the entry says now");
        assertEquals("failure_mode", f.type());
    }

    @Test
    void an_ingested_finding_carries_the_file_it_came_from(@TempDir Path dir) throws Exception {
        // Through the production ingest, so source_ref is written the way the
        // crawler writes it — a hand-set ref would prove only the fixture.
        Files.writeString(dir.resolve("note.md"),
            "---\nname: n\ndescription: a plain note about the settlement window\n"
                + "type: domain_fact\n---\nthe body of the note");
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "load");
        a.put("path", dir.toString());
        assertTrue(tool.execute(a).isSuccess());

        StoreQuality.Report scan = StoreQuality.scan(store, 10);
        assertTrue(scan.findingsTotal() >= 1);
        assertTrue(scan.findings().stream()
                .anyMatch(f -> f.sourceRef() != null && f.sourceRef().contains("note.md")),
            () -> "an ingested finding must name its file — that file is where the"
                + " durable fix goes: " + scan.findings());
    }

    /**
     * THE PATTERN MISCLASSIFICATION, pinned. A fact that already declares its
     * situation is HEALTHY — not a finding. Before this rule, all 187 catalogue
     * patterns (perfect situations, every one) were listed among the reference
     * "defects" because the classification stopped at the type before looking
     * at the situation, burying the ~36 real reference problems under 187
     * healthy rows.
     */
    @Test
    void a_fact_that_declares_its_situation_is_not_a_finding() {
        store.put(ExperienceEntry.of(
                SymbolFact.of("reference",
                    "The Willow pattern separates the abstraction from its implementation.",
                    Confidence.MEDIUM).build())
            .status(ExperienceEntry.CANDIDATE)
            .situation("when two objects must vary independently of each other")
            .build());
        legacyRow("reference", "a second reference with no situation at all");

        StoreQuality.Report scan = StoreQuality.scan(store, 10);
        assertEquals(1, scan.findingsTotal(),
            "only the situationless reference is repair work");
        assertEquals("a second reference with no situation at all",
            scan.findings().get(0).summary(),
            "and it is THAT one — the situated fact is healthy, not a finding");

        FormMigration.Report dryRun = new FormMigration(store).plan();
        assertEquals(1,
            dryRun.keptReasons().getOrDefault(
                FormMigration.REASON_FACT_WITH_SITUATION, 0).intValue(),
            "the migration classifies it by the shared healthy reason, so the two"
                + " surfaces stay one vocabulary");
    }

    /**
     * THE WHOLE EXERCISE, AS A GATE. Two days of story authoring existed so
     * that situations would LIVE IN THE STORE — and none of the 89 did: the
     * files were loaded on 2026-08-25 by the then-installed v3.12.1 engine,
     * whose loader predates the situation field, and every newer engine skipped
     * them as source-hash-unchanged. The cutover's own verification counted
     * ROWS against FILES and passed — a count proves nothing about a field.
     *
     * <p>This is that missing check: a story file declaring a situation, loaded
     * through the production ingest, must produce a row that CARRIES it. Runs
     * against the exact frontmatter shape of the real story files.</p>
     */
    @Test
    void a_loaded_storys_situation_lands_in_the_row(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("s02-shaped.md"),
            "---\n"
            + "name: a-brokers-snapshot-lags\n"
            + "description: \"On the paper account a just-cancelled order keeps"
            + " appearing in the open-orders list after the websocket reported it"
            + " terminal.\"\n"
            + "type: domain_fact\n"
            + "situation: a reconcile comparing the broker's open-orders list"
            + " against our own state is reporting orders as orphaned at the broker\n"
            + "reviewed: 2026-08-25\n"
            + "---\n"
            + "the body of the story\n");
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "load");
        a.put("path", dir.toString());
        assertTrue(tool.execute(a).isSuccess());

        StoredEntry row = store.all().stream()
            .filter(e -> String.valueOf(e.sourceRef()).contains("s02-shaped.md"))
            .findFirst().orElseThrow();
        assertEquals("a reconcile comparing the broker's open-orders list against"
                + " our own state is reporting orders as orphaned at the broker",
            row.facets().situation(),
            "the situation the author wrote must BE IN THE ROW — the store copy is"
                + " what retrieval ranks on, and 89 rows shipped without it while"
                + " every count-based check stayed green");
        assertEquals(Integer.valueOf(1), row.facets().form(),
            "and the form stamp says so");
    }

    /** The cap is declared; the counts always cover everything. */
    @Test
    void a_capped_list_says_so_and_the_counts_stay_true() {
        for (int i = 0; i < 5; i++) {
            legacyRow("failure_mode", "the walrus ledger drops packet number " + i
                + " when the reconnect storm arrives");
        }
        StoreQuality.Report scan = StoreQuality.scan(store, 2);
        assertEquals(5, scan.findingsTotal(), "the TRUE total, not the page size");
        assertEquals(2, scan.findings().size());
        assertTrue(scan.findingsTruncated(),
            "a capped list read as complete is this store's oldest lie");
    }
}
