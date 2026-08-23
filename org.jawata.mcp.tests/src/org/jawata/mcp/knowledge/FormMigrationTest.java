package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28c D4 — the migration, tested for what it must NOT do.
 *
 * <p>This store holds the only copy of what its owner learned, so the
 * load-bearing assertions here are negative: the dry run writes nothing, a row
 * an author already formed is never rewritten, and no row is ever disposed of.
 * A test suite that only proves rows get migrated would pass on a migration
 * that silently rewrote or dropped half the corpus.</p>
 */
class FormMigrationTest {

    private static ExperienceEntry legacy(String type, String summary, String symptom) {
        SymbolFact.Builder fact = SymbolFact.of(type, summary, Confidence.MEDIUM)
            .symbol("com.example.Thing");
        ExperienceEntry.Builder entry = ExperienceEntry.of(fact.build())
            .status(ExperienceEntry.ACCEPTED);
        if (symptom != null) {
            entry.symptoms(List.of(symptom));
        }
        return entry.build();
    }

    @Test
    void a_failure_mode_with_a_symptom_is_migrated_and_its_outcome_is_derived(
            @TempDir Path dir) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            String id = store.put(legacy("failure_mode",
                "The resident opened the shared store and ran the rung on it.",
                "the window froze for the whole scan"));

            FormMigration.Report report = new FormMigration(store).apply();

            assertEquals(1, report.migrated(), "the row should have been migrated: " + report);
            StoredEntry after = store.byIds(List.of(id)).get(0);
            assertEquals(1, after.facets().form());
            assertEquals("when the window froze for the whole scan", after.facets().situation());
            assertEquals("failed_avoid", after.facets().verdict(),
                "a failure_mode is by definition something to avoid");
            assertEquals("migrated", after.facets().provenanceKind(),
                "a reader must be able to tell a derived situation from an authored one");
        }
    }

    /**
     * The dry run's whole purpose. If this goes red the confirm gate is
     * decorative, and the human reading the report has already been overtaken
     * by the thing they were reading it to decide on.
     */
    @Test
    void a_dry_run_reports_the_disposition_and_writes_nothing(@TempDir Path dir)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            String id = store.put(legacy("lesson",
                "Commit first, then arm controls.",
                "a checkout took back changes that were never committed"));

            FormMigration.Report report = new FormMigration(store).plan();

            assertFalse(report.applied(), "plan() must not report itself as applied");
            assertEquals(1, report.migrated(), "the report still says what WOULD happen");
            StoredEntry after = store.byIds(List.of(id)).get(0);
            assertNull(after.facets().form(), "the dry run must not have written the form");
            assertNull(after.facets().situation(), "nor the situation");
            assertNull(after.facets().verdict(), "nor the outcome");
        }
    }

    /**
     * The refusal that keeps the corpus honest. A {@code domain_fact} never
     * turned out any way at all; deriving an outcome for one would put fiction
     * into the column retrieval ranks on — which is exactly the move this
     * sprint already refused once when it invented {@code unproven}.
     */
    @Test
    void a_fact_is_kept_rather_than_given_an_outcome_it_never_had(@TempDir Path dir)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            String id = store.put(legacy("domain_fact",
                "The launcher owns -data and -clean; unknown flags pass through.",
                "an unknown flag was accepted in silence"));

            FormMigration.Report report = new FormMigration(store).apply();

            assertEquals(0, report.migrated());
            assertEquals(1, report.legacyKept());
            assertTrue(report.keptReasons().keySet().stream()
                    .anyMatch(r -> r.contains("never turned out")),
                "the reason must say why, not merely that it was kept: " + report.keptReasons());
            assertNull(store.byIds(List.of(id)).get(0).facets().verdict(),
                "no outcome may be invented for a fact");
        }
    }

    @Test
    void a_row_with_no_cue_at_all_is_kept_with_the_reason_stated(@TempDir Path dir)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            store.put(legacy("lesson", "Something true but unanchored.", null));

            FormMigration.Report report = new FormMigration(store).apply();

            assertEquals(0, report.migrated());
            assertEquals(1, report.legacyKept());
            assertTrue(report.keptReasons().keySet().stream()
                    .anyMatch(r -> r.contains("no symptom and no operation")),
                report.keptReasons().toString());
        }
    }

    /**
     * An author's own declaration outranks a mechanical rule, permanently. The
     * derived situation for this row would differ from the one its author
     * wrote, so a migration that overwrote it would replace a stated experience
     * with a guessed one — and the author would never know.
     */
    @Test
    void a_row_its_author_already_formed_is_left_exactly_as_it_was(@TempDir Path dir)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            String id = store.put(ExperienceEntry.of(
                    SymbolFact.of("failure_mode", "A hand-rolled gate found three of eight.",
                        Confidence.HIGH).symbol("com.example.Thing").build())
                .status(ExperienceEntry.ACCEPTED)
                .symptoms(List.of("the wiring table looked complete"))
                .situation("when satisfying a clause a committed script already gates")
                .verdict("failed_avoid")
                .provenanceKind("recorded")
                .form(1)
                .build());

            FormMigration.Report report = new FormMigration(store).apply();

            assertEquals(0, report.migrated());
            StoredEntry after = store.byIds(List.of(id)).get(0);
            assertEquals("when satisfying a clause a committed script already gates",
                after.facets().situation(), "the author's own words must survive");
            assertEquals("recorded", after.facets().provenanceKind(),
                "and it must still read as authored, not as migrated");
        }
    }

    /**
     * Every id in, every id out, exactly once — and the counts reconcile with
     * the list rather than being reported beside it. A migration whose report
     * cannot be checked against itself has to be trusted instead.
     */
    @Test
    void every_entry_is_dispositioned_exactly_once_and_the_counts_reconcile(
            @TempDir Path dir) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            store.put(legacy("failure_mode", "One.", "the first symptom"));
            store.put(legacy("lesson", "Two.", "the second symptom"));
            store.put(legacy("domain_fact", "Three.", "the third symptom"));
            store.put(legacy("lesson", "Four.", null));

            FormMigration.Report report = new FormMigration(store).apply();

            assertEquals(4, report.sourceEntries());
            assertEquals(4, report.dispositions().size());
            assertEquals(4, report.dispositions().stream()
                .map(FormMigration.Disposition::id).distinct().count(),
                "an id appearing twice means one row was dispositioned twice");
            assertEquals(report.sourceEntries(), report.migrated() + report.legacyKept(),
                "every row is migrated or kept — there is no third outcome");
            assertEquals(4, store.count(), "nothing may be deleted by a migration");
        }
    }

    @Test
    void running_the_migration_twice_changes_nothing_the_second_time(@TempDir Path dir)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            String id = store.put(legacy("lesson", "Once is enough.",
                "the same anchor was re-reported on every pass"));

            new FormMigration(store).apply();
            StoredEntry afterFirst = store.byIds(List.of(id)).get(0);
            FormMigration.Report second = new FormMigration(store).apply();

            assertEquals(0, second.migrated(), "the second run must be a no-op");
            assertEquals(1, second.legacyKept());
            StoredEntry afterSecond = store.byIds(List.of(id)).get(0);
            assertEquals(afterFirst.facets().situation(), afterSecond.facets().situation());
            assertNotNull(afterSecond.facets().situation());
        }
    }

    /**
     * The store's own guard, tested at the store — because the migration never
     * reaches it.
     *
     * <p>{@link FormMigration} skips a formed row before calling
     * {@code setForm}, so every test that goes through the migration leaves the
     * {@code form IS NULL} clause unexercised. That was found by arming the
     * clause as a control and watching the whole suite stay green: a second
     * line of defence nothing tests is a claim, and the day someone removes the
     * Java-side check it becomes the only one.</p>
     */
    @Test
    void the_store_itself_refuses_to_overwrite_a_form_that_is_already_set(
            @TempDir Path dir) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            String id = store.put(ExperienceEntry.of(
                    SymbolFact.of("lesson", "Authored, not derived.", Confidence.HIGH)
                        .symbol("com.example.Thing").build())
                .status(ExperienceEntry.ACCEPTED)
                .situation("when the author said so themselves")
                .verdict("worked")
                .form(1)
                .build());

            boolean wrote = store.setForm(id, "when a rule guessed instead", "failed_avoid");

            assertFalse(wrote, "setForm must report that it did not write");
            assertEquals("when the author said so themselves",
                store.byIds(List.of(id)).get(0).facets().situation(),
                "and must actually not have written");
        }
    }

    /**
     * The report groups on {@code provenance_kind}, which is what gives that
     * column a reader rather than merely a writer.
     */
    @Test
    void the_report_groups_by_provenance_including_the_rows_that_have_none(
            @TempDir Path dir) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            store.put(legacy("lesson", "Unstamped.", "a symptom"));

            Map<String, Integer> kinds = new FormMigration(store).plan().provenanceKinds();

            assertEquals(1, kinds.get("(unset)"),
                "a legacy row's absent provenance must be reported as absent, not omitted: "
                    + kinds);
        }
    }
}
