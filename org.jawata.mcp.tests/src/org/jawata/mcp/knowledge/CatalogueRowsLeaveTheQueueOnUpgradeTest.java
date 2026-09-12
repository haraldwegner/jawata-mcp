package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28f E5 — the catalogue rows an EXISTING store already holds leave the review queue.
 *
 * <p><b>The defect this exists for, and the test that was missing when it shipped.</b> E5
 * changed one literal so a seeded pattern is written {@code accepted}. That reaches a store
 * which has never been seeded, and only that one: {@link CatalogueSeeder} skips a row whose
 * content hash is unchanged, and the hash is taken over the MANIFEST ROW'S JSON. Status is
 * not in that JSON — it is decided in Java, downstream of the hash. So no row's hash moved,
 * every one took the skip branch, and the store the deliverable was MEASURED on would have
 * kept all 189 in the queue.</p>
 *
 * <p><b>{@code CatalogueSeedsAcceptedTest} could not catch it and was not wrong to miss it.</b>
 * It seeds a FRESH temp store, where the skip branch never fires. That is the shape this
 * sprint keeps recording: a fixture written by the author of the rule lands in the state the
 * rule handles. The case below is the one that reaches the other state — a store that already
 * holds the rows — and it is the state every real installation is in.</p>
 *
 * <p><b>It drives the real migration dispatch, not the SQL.</b> A rung can be written and left
 * out of the {@code if (from &lt; n)} chain, which is a defect the statement alone cannot show;
 * so the version is wound back and {@code migrate} is called, exactly as a boot would.</p>
 */
class CatalogueRowsLeaveTheQueueOnUpgradeTest {

    /** A candidate row, written the way a pre-E5 seed wrote one. */
    private static String candidate(H2ExperienceStore store, String summary,
                                    String provenance, String sourceRef) {
        ExperienceEntry e = ExperienceEntry.of(
                SymbolFact.of(CatalogueManifest.CATALOGUE_TYPE, summary, Confidence.MEDIUM)
                    .build())
            .status(ExperienceEntry.CANDIDATE)
            .situation("when a pre-E5 store is opened by a build that carries v17")
            .provenanceKind(provenance)
            .build();
        return store.putWithSource(e, sourceRef);
    }

    private static String statusOf(H2ExperienceStore store, String id) throws Exception {
        try (var ps = store.sharedConnection()
                .prepareStatement("SELECT status FROM experience_entry WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), () -> "the row vanished: " + id);
                return rs.getString(1);
            }
        }
    }

    @Test
    void an_upgrade_frees_the_borrowed_rows_and_leaves_the_users_own_alone(@TempDir Path dir)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            CatalogueOrigin origin = CatalogueSources.all().get(0);

            String borrowed = candidate(store,
                "a borrowed pattern a pre-E5 seed stamped candidate",
                CatalogueManifest.PROVENANCE,
                origin.prefix() + "pre-e5-pattern/README.md");

            // THE CONTROL, and it is the assertion that keeps the migration honest: a row a
            // human genuinely left awaiting a ruling is THEIRS. A statement that promoted
            // every candidate would satisfy the claim below and quietly overwrite a decision
            // somebody had not made yet.
            String mine = candidate(store,
                "something of my own that really is waiting for a human",
                "recorded", null);

            // PROOF OF LIFE. Without it both assertions after the migration are equally true
            // of a store where neither row was ever a candidate.
            assertEquals(ExperienceEntry.CANDIDATE, statusOf(store, borrowed),
                "the borrowed row starts in the queue, or the migration has nothing to do");
            assertEquals(ExperienceEntry.CANDIDATE, statusOf(store, mine),
                "and so does mine, or the control below proves nothing");

            // Wind the version back and run the real thing — a rung that exists but is
            // missing from the dispatch chain is exactly the defect a direct call would hide.
            Connection c = store.sharedConnection();
            try (Statement s = c.createStatement()) {
                s.execute("UPDATE schema_version SET version = 16");
            }
            assertEquals(16, SchemaMigrations.detectVersion(c),
                "the wind-back must take, or migrate() has nothing to upgrade from");

            SchemaMigrations.migrate(c, dir, false);

            assertEquals(SchemaMigrations.LATEST, SchemaMigrations.detectVersion(c),
                "the upgrade must complete, or the assertions below are about a half-run");
            assertEquals(ExperienceEntry.ACCEPTED, statusOf(store, borrowed),
                "A BORROWED PATTERN IS NOT AWAITING ANYONE'S RULING. Changing the seeder's"
                    + " literal reaches a fresh store only — the seeder skips a row whose"
                    + " manifest hash is unchanged, and status is not in that hash. Without"
                    + " this rung every already-seeded store keeps its whole catalogue in"
                    + " the queue, which is the state the deliverable was measured in.");
            assertEquals(ExperienceEntry.CANDIDATE, statusOf(store, mine),
                "THE CONTROL: a candidate that is not borrowed is a human's open decision"
                    + " and the migration has no business closing it.");
        }
    }
}
