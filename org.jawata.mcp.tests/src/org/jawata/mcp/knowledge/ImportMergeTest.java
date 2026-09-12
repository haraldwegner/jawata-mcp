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

    /** A story of any TYPE — the malformed case needs one the form gate actually judges. */
    private static Path typedStory(Path dir, String name, String type, String summary, String body)
            throws Exception {
        Path f = dir.resolve(name + ".md");
        Files.writeString(f, "---\nname: " + name + "\ndescription: " + summary
            + "\ntype: " + type + "\n---\n\n" + body + "\n");
        return f;
    }

    private static ExperienceMaintenance maintenanceOver(H2ExperienceStore store) {
        return new ExperienceMaintenance(store, symbol -> null);
    }

    /**
     * The row's links, read back through the document the store returns.
     *
     * <p>{@link StoredEntry} does not carry them — they are child rows — so this goes
     * through {@code get}, which is the same path a caller reads them on.</p>
     */
    private static List<?> linksOf(H2ExperienceStore store, String id) {
        Map<String, Object> doc = store.get(id)
            .orElseThrow(() -> new AssertionError("no document for row " + id));
        Object links = doc.get("links");
        assertTrue(links instanceof List<?>,
            () -> "the document carries no links list at all; keys: " + doc.keySet());
        return (List<?>) links;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> skippedIn(Map<String, Object> report) {
        Object skipped = report.get("skipped");
        assertTrue(skipped instanceof List<?>,
            () -> "the report carries no skipped list; keys: " + report.keySet());
        return (List<Map<String, Object>>) skipped;
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

    /**
     * MALFORMED → REFUSED BY NAME, and the good file beside it still loads.
     *
     * <p>The stage's deliverable is "stamped → accepted, unstamped → candidate, malformed
     * → refused by name", and this is the third. "Malformed" is not a guess about what a
     * bad file looks like: it is whatever {@code EntryForm.check} refuses, which is the
     * SAME gate the record verb applies — a file typed {@code lesson} owes a situation and
     * an outcome, and one that declares neither is a draft rather than knowledge.</p>
     *
     * <p><b>BY NAME is the half worth asserting</b>, and the loader's own comment says
     * why: the refusal "lands in the report's skipped list … so one malformed note can
     * neither vanish silently nor cut the crawl short". A refusal nobody can attribute to
     * a file is indistinguishable from a file that was never read.</p>
     *
     * <p>The GOOD file is the control. Without it, every assertion here is equally
     * satisfied by a load that refused everything — including by one that threw before
     * reading either file.</p>
     *
     * <p>Not repeated here, because {@code ExperienceMaintenanceTest} already owns it:
     * that a refused file's own links are still followed. This test is about the refusal
     * being attributable and selective.</p>
     */
    @Test
    void a_malformed_file_is_refused_by_name_and_the_good_one_beside_it_still_loads(
            @TempDir Path dir, @TempDir Path roots) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceMaintenance maintenance = maintenanceOver(store);
            // A lesson is an EXPERIENCE: it owes a situation and a verdict. This one
            // declares neither, which is exactly what the record verb refuses.
            Path bad = typedStory(roots, "half-written", "lesson",
                "a lesson nobody said when it applies or how it turned out",
                "Body without the two fields its own type owes.");
            story(roots, "sound", "a well-formed note that must still land", "Body.");

            Map<String, Object> report = maintenance.load(roots, true);

            Map<String, Object> refusal = skippedIn(report).stream()
                .filter(s -> bad.toString().equals(String.valueOf(s.get("source"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "the malformed file is not named in the skipped list: "
                        + skippedIn(report)));
            assertTrue(String.valueOf(refusal.get("reason")).contains("situation"),
                () -> "the refusal must name the field that is missing, so the author can"
                    + " fix it rather than guess: " + refusal);

            assertEquals(0, store.all().stream()
                .filter(e -> "a lesson nobody said when it applies or how it turned out"
                    .equals(e.summary())).count(),
                "and nothing was ingested from it — a refusal that still writes a row is"
                    + " worse than none, because the row then claims the gate passed");

            assertEquals(1, store.all().stream()
                .filter(e -> "a well-formed note that must still land".equals(e.summary()))
                .count(),
                "THE CONTROL: the sound file beside it loaded, so the refusal is selective"
                    + " rather than the whole load failing");
        }
    }

    /**
     * E8's LINKS half — a re-load keeps the row's links rather than doubling them.
     *
     * <p>The id half above says the entry survives; this says its children do too, and
     * the two together are the cell's "merges in place keeping id + links". They fail in
     * OPPOSITE directions, which is why one does not imply the other: the old
     * delete-then-insert lost the id and rebuilt the links, while an in-place update that
     * forgot to clear the child rows first would keep the id and APPEND a second copy of
     * every link on every load. A count is the only thing that sees the second defect,
     * and the row would look correct in every other respect.</p>
     *
     * <p>The third load is the other direction: a link the file NO LONGER declares must
     * GO. {@code updateSourcedRow} replaces the children wholesale rather than merging
     * them, for the reason its own comment gives — there is no key to diff a symptom or a
     * link on, so the file IS the statement of what they are. Without this half, "the
     * links survive" would also be true of an implementation that never removes one.</p>
     */
    @Test
    void a_reload_keeps_the_rows_links_rather_than_doubling_them(
            @TempDir Path dir, @TempDir Path roots) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceMaintenance maintenance = maintenanceOver(store);
            String summary = "the note that points at two neighbours";
            story(roots, "neighbourly", summary,
                "The first telling, citing [[first-neighbour]] and [[second-neighbour]].");
            maintenance.load(roots, true);

            String id = only(store, summary).id();
            assertEquals(2, linksOf(store, id).size(),
                () -> "precondition: both wikilinks became links, " + linksOf(store, id));

            // The prose changes; BOTH citations stay. The description is untouched, which
            // is what keeps the row identifiable.
            story(roots, "neighbourly", summary,
                "The second telling, still citing [[first-neighbour]] and"
                    + " [[second-neighbour]], with the detail that mattered.");
            maintenance.load(roots, true);

            assertEquals(id, only(store, summary).id(), "the id still survives");
            assertEquals(2, linksOf(store, id).size(),
                () -> "THE LINKS ARE REPLACED, NOT APPENDED. An in-place update that did"
                    + " not clear the child rows first would report FOUR here, with the"
                    + " id and the summary and the body all still correct: "
                    + linksOf(store, id));

            // And one citation goes. The file is the statement of what the links are.
            story(roots, "neighbourly", summary,
                "The third telling, which no longer cites [[first-neighbour]] at all.");
            maintenance.load(roots, true);

            assertEquals(id, only(store, summary).id(), "and still the same row");
            assertEquals(1, linksOf(store, id).size(),
                () -> "a link the file dropped must go, or 'the links survive' would be"
                    + " equally true of an implementation that never removes one: "
                    + linksOf(store, id));
        }
    }
}
