package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sprint 28d Stage 6 / S3a — THE RETIRED-PREFIX MIGRATION.
 *
 * <p><b>The defect this exists to prevent.</b> Every ownership question in the
 * catalogue lane is keyed on a prefix: {@code owning()}, {@code isCatalogue()},
 * the seeder's own {@code liveByRef} (built with {@code startsWith(prefix)}) and
 * the reseed lane rule. So a prefix RENAME is the one change a prefix-keyed
 * lifecycle cannot perform on itself — the instant the spelling changes, every
 * row under the old one falls out of all four simultaneously. They are not
 * superseded and not swept; they are INVISIBLE, still live, still answering with
 * an address nothing backs.</p>
 *
 * <p><b>Why this is a test and not a one-off store operation.</b> The plan
 * originally called for retiring the two live {@code sample:jawata-samples/} rows
 * by hand. Measured 2026-08-28: this store holds NONE — it reports 187 catalogue
 * rows, and the running server predates {@code SampleSource} by six hours, so it
 * has never seeded one. A manual retire here would have fixed nothing anywhere
 * else, and the rename orphans rows on every install that ever seeded the old
 * spelling. The migration therefore has to be code that runs at seed time, proven
 * against a constructed store rather than against whatever one machine happens to
 * hold.</p>
 *
 * <p><b>The guard interaction, which is the subtle half.</b> The orphan sweep is
 * deliberately gated on completeness, because a partial input would otherwise
 * retire everything it happens not to carry. The retired-prefix migration must
 * NOT share that gate: a retired prefix has no current input by construction, so
 * every row under it is stale whatever the new input contains. Gating it would
 * make the migration silently skip exactly when a truncated read most needs it.
 * {@link #aRetiredPrefixIsClearedEvenWhenTheSweepIsWithheld} pins that.</p>
 */
class CatalogueSeederMigrationTest {

    private static final String OLD_PREFIX = "sample:jawata-samples/";
    private static final String NEW_PREFIX = "catalogue:jawata-samples/";

    /** A minimal, admissible catalogue-shaped entry. */
    private static ExperienceEntry entry(String summary) {
        String situation = "when a method mixes parsing, arithmetic and formatting in one body";
        return ExperienceEntry.of(
                SymbolFact.of("reference", summary, Confidence.MEDIUM)
                    .details("Specimen prose, unparaphrased.")
                    .build())
            .status(ExperienceEntry.CANDIDATE)
            .situation(situation)
            .cause("the jobs share a scope, so any one of them can only be verified by reading all")
            .form(EntryForm.formOf(situation))
            .provenanceKind("catalog")
            .operation("design:compose-method")
            .build();
    }

    private static CatalogueSeeder.SeedItem item(String prefix, String slug) {
        return new CatalogueSeeder.SeedItem(
            prefix + slug + "/README.md", "hash-" + slug, entry("Principle for " + slug));
    }

    private static List<StoredEntry> under(H2ExperienceStore store, String prefix) {
        return store.all().stream()
            .filter(e -> e.sourceRef() != null && e.sourceRef().startsWith(prefix))
            .toList();
    }

    private static long live(List<StoredEntry> rows) {
        return rows.stream().filter(e -> !ExperienceEntry.SUPERSEDED.equals(e.status())).count();
    }

    @Test
    void rowsUnderTheOldSpellingAreSupersededWhenThePrefixChanges(@TempDir Path dir)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            // A prior install: two rows seeded under the OLD spelling.
            CatalogueSeeder.seed(store, OLD_PREFIX,
                List.of(item(OLD_PREFIX, "compose-method"),
                        item(OLD_PREFIX, "replace-pattern-with-idiom")),
                2, false, "before-the-rename", List.of());
            assertEquals(2, live(under(store, OLD_PREFIX)),
                "PROOF OF LIFE: both old-spelling rows must be live before the rename can"
                    + " mean anything — a migration proven against an empty store proves nothing");

            // The rename, declaring the old spelling retired.
            CatalogueSeeder.seed(store, NEW_PREFIX,
                List.of(item(NEW_PREFIX, "compose-method"),
                        item(NEW_PREFIX, "replace-pattern-with-idiom")),
                2, false, "after-the-rename", List.of(OLD_PREFIX));

            assertEquals(0, live(under(store, OLD_PREFIX)),
                () -> "every row under the retired prefix must stop answering. While one stays"
                    + " live it hands out an address nothing backs, and NOTHING can see it: the"
                    + " rename drops it out of owning(), isCatalogue(), liveByRef and the reseed"
                    + " lane rule at once. Statuses: "
                    + under(store, OLD_PREFIX).stream().map(StoredEntry::status).toList());
            assertEquals(2, live(under(store, NEW_PREFIX)),
                "the new spelling must be live — a migration that retires the old rows and"
                    + " fails to seed the new ones has deleted the lane, not moved it");
        }
    }

    /**
     * THE CONTROL for the test above — added 2026-08-28 after a fresh-context audit
     * found it absent while the checkpoint record claimed it had been delivered.
     *
     * <p>The positive test shows the old rows end SUPERSEDED after a rename that
     * declares the old spelling retired. On its own that does not say WHY. The
     * supersession could equally have come from the orphan sweep, from the new seed
     * touching the same addresses, or from anything else that runs on a seed — and the
     * assertion would look identical.</p>
     *
     * <p>So this runs the same rename with the retired-prefix argument REMOVED, and
     * requires the old rows to stay LIVE — orphaned, answering with an address nothing
     * backs. That is the defect the argument exists to prevent, reproduced on demand.
     * The pair is what makes the positive test evidence rather than a coincidence: if
     * this ever goes green with zero live rows, the supersession has some other cause
     * and the migration is not doing the work it is credited with.</p>
     */
    @Test
    void withoutTheRetiredPrefixArgumentTheOldRowsAreLeftOrphaned(@TempDir Path dir)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            CatalogueSeeder.seed(store, OLD_PREFIX,
                List.of(item(OLD_PREFIX, "compose-method"),
                        item(OLD_PREFIX, "replace-pattern-with-idiom")),
                2, false, "before-the-rename", List.of());
            assertEquals(2, live(under(store, OLD_PREFIX)),
                "PROOF OF LIFE: both old-spelling rows must be live before the rename, or"
                    + " 'they stayed live' below would be true of an empty store");

            // The SAME rename as the positive test, with ONE difference: no retired
            // prefix is declared. Everything else is held constant on purpose — that is
            // what makes the difference in outcome attributable to this argument alone.
            CatalogueSeeder.seed(store, NEW_PREFIX,
                List.of(item(NEW_PREFIX, "compose-method"),
                        item(NEW_PREFIX, "replace-pattern-with-idiom")),
                2, false, "after-the-rename", List.of());

            assertEquals(2, live(under(store, OLD_PREFIX)),
                () -> "WITHOUT the retired-prefix argument the old rows must remain LIVE —"
                    + " orphaned, each handing out an address nothing backs. If they were"
                    + " superseded here, something OTHER than the migration is retiring"
                    + " them and the positive test above credits this argument with work"
                    + " it does not do. Statuses: "
                    + under(store, OLD_PREFIX).stream().map(StoredEntry::status).toList());
            assertEquals(2, live(under(store, NEW_PREFIX)),
                "and the new spelling is seeded either way — so the store now holds BOTH"
                    + " spellings live, which is precisely the duplicate-address state the"
                    + " retired-prefix argument exists to prevent");
        }
    }

    @Test
    void aRetiredPrefixIsClearedEvenWhenTheSweepIsWithheld(@TempDir Path dir) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            CatalogueSeeder.seed(store, OLD_PREFIX,
                List.of(item(OLD_PREFIX, "compose-method"),
                        item(OLD_PREFIX, "replace-pattern-with-idiom")),
                2, false, "before-the-rename", List.of());

            // A TRUNCATED read: declares 2, carries 1. The orphan sweep must withhold
            // itself — but the retired prefix has no current input at all, so its rows
            // are stale regardless of what this input carries.
            CatalogueSeeder.seed(store, NEW_PREFIX,
                List.of(item(NEW_PREFIX, "compose-method")),
                2, false, "truncated-input", List.of(OLD_PREFIX));

            assertEquals(0, live(under(store, OLD_PREFIX)),
                "the retired-prefix migration must NOT share the completeness gate. That gate"
                    + " protects rows the CURRENT input still owns; a retired prefix has no"
                    + " current input by construction. Sharing the gate would skip the migration"
                    + " exactly when a truncated read most needs it");
        }
    }

    @Test
    void theMigrationIsIdempotent(@TempDir Path dir) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            CatalogueSeeder.seed(store, OLD_PREFIX, List.of(item(OLD_PREFIX, "compose-method")),
                1, false, "before-the-rename", List.of());

            CatalogueSeeder.Outcome first = CatalogueSeeder.seed(store, NEW_PREFIX,
                List.of(item(NEW_PREFIX, "compose-method")), 1, false, "after", List.of(OLD_PREFIX));
            CatalogueSeeder.Outcome second = CatalogueSeeder.seed(store, NEW_PREFIX,
                List.of(item(NEW_PREFIX, "compose-method")), 1, false, "after", List.of(OLD_PREFIX));

            assertEquals(1, first.migrated(),
                "the first run migrates the one old row");
            assertEquals(0, second.migrated(),
                "the second run must migrate NOTHING — a migration that keeps finding work on an"
                    + " unchanged store is retiring rows it already retired, and every boot would"
                    + " report a fresh migration that did not happen");
        }
    }
}
