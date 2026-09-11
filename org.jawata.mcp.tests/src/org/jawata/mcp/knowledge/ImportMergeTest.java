package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28f D1 — a re-load updates in place and removes nothing.
 *
 * <p><b>The defect this ends.</b> {@code loadSources} called {@code deleteBySource} and
 * then re-inserted, so a load that died between the two took that file's knowledge with
 * it, and every row's id changed on every re-ingest — orphaning anything that had
 * recorded a decision about one. The id is the assertion here, not the row count: a
 * delete-then-insert produces the same COUNT as an upsert and a different identity, so
 * counting cannot tell the two apart and is exactly the check that would have passed
 * over the old behaviour.</p>
 */
class ImportMergeTest {

    private static Path story(Path dir, String name, String summary, String body)
            throws Exception {
        Path f = dir.resolve(name + ".md");
        Files.writeString(f, "---\nname: " + name + "\ndescription: " + summary
            + "\ntype: domain_fact\n---\n\n" + body + "\n");
        return f;
    }

    private static ExperienceMaintenance maintenanceOver(H2ExperienceStore store) {
        return new ExperienceMaintenance(store, symbol -> null);
    }

    private static StoredEntry only(H2ExperienceStore store, String summary) {
        List<StoredEntry> hits = store.all().stream()
            .filter(e -> summary.equals(e.summary())).toList();
        assertEquals(1, hits.size(),
            () -> "expected exactly one row summarised '" + summary + "', got " + hits.size());
        return hits.get(0);
    }

    /**
     * The SAME ROW is rewritten — same id, new content.
     *
     * <p>The id is captured before the second load and compared after it. Under the old
     * delete-then-insert the count would be identical and the id would be new, so this
     * is the only form that discriminates.</p>
     */
    @Test
    void a_reload_rewrites_the_row_in_place(@TempDir Path dir, @TempDir Path roots)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceMaintenance maintenance = maintenanceOver(store);
            story(roots, "gutters", "the roof leaked because nobody swept the gutters",
                "The first telling.");
            maintenance.load(roots, true);

            StoredEntry before = only(store, "the roof leaked because nobody swept the gutters");
            String id = before.id();
            assertNotNull(id);
            long rowsAfterFirstLoad = store.count();

            // The file changes. Its DESCRIPTION is unchanged, which is what keeps the
            // row identifiable; the body is what the reader will see differently.
            story(roots, "gutters", "the roof leaked because nobody swept the gutters",
                "The second telling, with the detail that mattered.");
            maintenance.load(roots, true);

            StoredEntry after = only(store, "the roof leaked because nobody swept the gutters");
            assertEquals(id, after.id(),
                "THE ID MUST SURVIVE. A delete-then-insert yields the same row COUNT and a"
                    + " different identity, so a count assertion here would pass over the"
                    + " very behaviour this replaces");
            assertEquals(rowsAfterFirstLoad, store.count(),
                "and the re-load added no second copy");
        }
    }

    /** A file that VANISHED leaves its row — a load is additive, forgetting is another verb. */
    @Test
    void a_vanished_file_removes_nothing(@TempDir Path dir, @TempDir Path roots)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceMaintenance maintenance = maintenanceOver(store);
            story(roots, "kept", "the row whose file will disappear", "Body.");
            story(roots, "other", "the row whose file stays", "Body.");
            maintenance.load(roots, true);
            long both = store.count();
            assertTrue(both >= 2, () -> "the control: both files loaded, got " + both);

            Files.delete(roots.resolve("kept.md"));
            maintenance.load(roots, true);

            assertEquals(both, store.count(),
                "a load REMOVES NOTHING — the vanished file's row stays, because"
                    + " forgetting is wipe_and_import's job, behind a backup and a confirm");
            assertEquals(1, store.all().stream()
                .filter(e -> "the row whose file will disappear".equals(e.summary())).count(),
                "and it is still exactly one row, not a duplicate");
        }
    }

    /**
     * A DIFFERENT source with the same summary is its own row.
     *
     * <p>The match is on {@code (source_ref, summary)}, and this is the half that says
     * the source is part of the key: matching on summary ALONE would make two files
     * telling the same thing collapse into one row, silently losing one of them.</p>
     */
    @Test
    void two_files_with_the_same_summary_stay_two_rows(@TempDir Path dir, @TempDir Path roots)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceMaintenance maintenance = maintenanceOver(store);
            story(roots, "first", "a summary two files happen to share", "One body.");
            story(roots, "second", "a summary two files happen to share", "Another body.");
            maintenance.load(roots, true);

            List<StoredEntry> hits = store.all().stream()
                .filter(e -> "a summary two files happen to share".equals(e.summary())).toList();
            assertEquals(2, hits.size(),
                () -> "each file keeps its own row: the key is (source, summary), not summary"
                    + " alone — got " + hits.size());
            assertNotEquals(hits.get(0).id(), hits.get(1).id());
        }
    }

    /**
     * Re-loading an UNCHANGED file writes nothing at all.
     *
     * <p>Not a property this stage adds — the skip-unchanged check predates it — but the
     * control that says the two tests above are about a file that really changed, rather
     * than about a load that silently does nothing.</p>
     */
    @Test
    void an_unchanged_file_is_skipped(@TempDir Path dir, @TempDir Path roots) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceMaintenance maintenance = maintenanceOver(store);
            story(roots, "steady", "a story nobody edits", "Body.");
            maintenance.load(roots, true);
            StoredEntry before = only(store, "a story nobody edits");

            Map<String, Object> second = maintenance.load(roots, true);

            assertEquals(before.id(), only(store, "a story nobody edits").id());
            assertTrue(String.valueOf(second).contains("unchanged")
                    || String.valueOf(second.get("loaded")).equals("0"),
                () -> "an unchanged file is skipped rather than rewritten: " + second);
        }
    }
}
