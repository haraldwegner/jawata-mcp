package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 21 Stage 4 — initial_load ingest + refresh/wipe maintenance. */
class ExperienceMaintenanceTest {

    private H2ExperienceStore store;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private ExperienceMaintenance maint(ExperienceMaintenance.PointerResolver r) {
        return new ExperienceMaintenance(store, r);
    }

    private void writeMemory(Path dir, String file, String frontmatter, String body) throws IOException {
        Files.writeString(dir.resolve(file), "---\n" + frontmatter + "\n---\n" + body);
    }

    @Test
    void load_ingests_memory_files_with_frontmatter_and_links(@TempDir Path dir) throws IOException {
        writeMemory(dir, "a.md",
            "name: guard-lifecycle\ndescription: guard the workbench lifecycle\nmetadata:\n  type: feedback",
            "Body mentions [[recall-gap]] and [[another-note]].");
        writeMemory(dir, "b.md",
            "name: billing-dto\ndescription: billing DTOs keep no-arg ctors\ntype: domain_fact",
            "Legacy XML depends on them.");

        Map<String, Object> report = maint(fqn -> null).load(dir);
        assertEquals(2, report.get("loaded"));
        assertEquals(2L, store.count());

        List<StoredEntry> feedback = store.query(new RecallQuery(null, null, null, "guard the workbench", null));
        assertFalse(feedback.isEmpty(), "loaded entry is queryable by its summary");
        assertEquals(ExperienceEntry.ACCEPTED, feedback.get(0).status());
        assertEquals("feedback", feedback.get(0).type());

        // [[wikilinks]] became related edges in the stored document.
        Map<String, Object> doc = store.get(feedback.get(0).id()).orElseThrow();
        assertTrue(doc.get("links") instanceof List<?>);
        assertEquals(2, ((List<?>) doc.get("links")).size());
    }

    @Test
    void load_is_idempotent_per_source(@TempDir Path dir) throws IOException {
        writeMemory(dir, "a.md", "name: n\ndescription: this note carries a body worth keeping\ntype: reference", "body");
        maint(fqn -> null).load(dir);
        maint(fqn -> null).load(dir);   // re-load same source
        assertEquals(1L, store.count(), "re-load replaces, not duplicates");
    }

    /**
     * Sprint 27a D10, REWRITTEN at 28d S10.0 against DECLARED cues.
     *
     * <p>The load channel obeys the admission routing: a cue with a misplaced shape
     * (a path, a flag, a heading) never lands as a symptom row, and the suppression is
     * REPORTED — a silent drop is this project's recorded deepest bug class. The file
     * NAME slug still arrives prosified, and a section heading still becomes summary
     * TEXT rather than a heading-shaped summary.</p>
     *
     * <p><b>Why this survived the harvester's deletion when three siblings did not.</b>
     * Those three were about WHERE cues come from, which is the thing that changed.
     * This is about what happens to a cue once it exists, which is unchanged — and it
     * matters MORE now, not less: a person can declare a path or a flag as a cue just
     * as easily as a scraper could produce one, and the routing is the only thing
     * standing between that and the vouching lane.</p>
     */
    @Test
    void load_routes_misplaced_cues_and_reports_the_suppression(@TempDir Path dir)
            throws IOException {
        writeMemory(dir, "renders-blank-on-aarch64.md",
            "name: renders-blank-on-aarch64\ndescription: the view renders blank on aarch64\n"
                + "type: reference\n"
                // Two misplaced shapes and one legitimate cue, all DECLARED. The author
                // wrote every one of them; admission still refuses two.
                + "symptoms: --no-verify, src/main/Renderer.java, native buffer",
            "The scale factor arrives after the buffer is sized.\n"
            + "\n## Root cause:\n\nThe native buffer is sized before the scale factor arrives.\n");

        Map<String, Object> report = maint(fqn -> null).load(dir);

        assertTrue(report.containsKey("keywords_suppressed"),
            "the route/skip report is PRESENT: " + report);
        assertTrue((int) report.get("keywords_suppressed") >= 2,
            "the misplaced harvested shapes (a bold --flag and a quoted path) were suppressed: " + report);

        java.util.List<String> allSymptoms = new java.util.ArrayList<>();
        for (StoredEntry e : store.all()) {
            Map<String, Object> doc = store.get(e.id()).orElseThrow();
            if (doc.get("symptoms") instanceof java.util.List<?> list) {
                for (Object s : list) {
                    allSymptoms.add(String.valueOf(s));
                }
            }
        }
        for (String s : allSymptoms) {
            assertFalse(AdmissionPolicy.misplaced(AdmissionPolicy.classify(s)),
                "no misplaced shape lands as a symptom via load: '" + s + "'");
        }
        assertTrue(allSymptoms.contains("renders blank on aarch64"),
            "the name slug arrives PROSIFIED, its cue phrasing intact: " + allSymptoms);
        assertTrue(allSymptoms.contains("native buffer"),
            "prose-shaped harvest (the bold phrase) still lands: " + allSymptoms);

        boolean sectionSummaryClean = store.all().stream()
            .map(StoredEntry::summary)
            .anyMatch("Root cause"::equals);
        assertTrue(sectionSummaryClean,
            "the section summary is the heading TEXT without the ':' shape");
    }

    @Test
    void load_flags_stale_symbol_on_ingest(@TempDir Path dir) throws IOException {
        writeMemory(dir, "s.md",
            "name: n\ndescription: this note carries a body worth keeping\ntype: reference\nsymbol: com.gone.Removed", "body");
        Map<String, Object> report = maint(fqn -> Boolean.FALSE).load(dir);
        assertEquals(1, ((List<?>) report.get("stale")).size(), "unresolvable pointer flagged at ingest");
    }

    @Test
    void load_strips_surrounding_quotes_from_symbol(@TempDir Path dir) throws IOException {
        // P0-b: a YAML-quoted frontmatter symbol must ingest identically to its
        // bare form — reach the JDT resolver unquoted and be recallable by the
        // unquoted FQN. Before the fix the quotes were kept, so recall-by-symbol
        // (which reads the raw symbol_fqn column) never matched.
        writeMemory(dir, "q.md",
            "name: n\ndescription: this note carries a body worth keeping\ntype: reference\nsymbol: \"com.example.HelloWorld#greet\"", "body");
        AtomicReference<String> seen = new AtomicReference<>();
        maint(fqn -> { seen.set(fqn); return Boolean.TRUE; }).load(dir);

        assertEquals("com.example.HelloWorld#greet", seen.get(),
            "the quoted frontmatter symbol must reach the JDT resolver UNQUOTED");
        assertFalse(
            store.query(new RecallQuery("com.example.HelloWorld#greet", null, null, null, null)).isEmpty(),
            "the entry must be recallable by its unquoted FQN anchor");
    }

    @Test
    void refresh_flags_stale_pointer_as_superseded() {
        String id = store.put(ExperienceEntry.of(
            SymbolFact.of("lesson", "note", Confidence.HIGH).symbol("com.gone.Removed").build()).build());

        Map<String, Object> report = maint(fqn -> Boolean.FALSE).refresh();
        assertEquals(1, report.get("checked"));
        assertEquals(1, ((List<?>) report.get("staled")).size());

        // The stale entry is superseded and no longer surfaces in recall.
        StoredEntry after = store.all().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
        assertEquals(ExperienceEntry.SUPERSEDED, after.status());
        assertTrue(store.query(new RecallQuery("com.gone.Removed", null, null, null, null)).isEmpty());
    }

    /**
     * Sprint 28c — THE PAIR to the test above, and the sprint's premise in one
     * assertion: a form-1 entry with a dead anchor is NOT superseded.
     *
     * <p>The two carry different claims. An anchor says WHERE the knowledge was
     * learned; a situation says WHEN it applies. A lesson about amending a
     * partially filled order keeps applying after the class that taught it is
     * renamed, moved or deleted — and superseding it on the anchor retires the
     * knowledge because the evidence moved, which is exactly the design flaw
     * this sprint exists to fix.</p>
     *
     * <p>The dead pointer is still a fact, so it is recorded as one and a human
     * decides. A resolver may mark; it may never retire.</p>
     */
    @Test
    void refresh_never_supersedes_a_form_one_entry_and_marks_its_evidence_dead() {
        String id = store.put(ExperienceEntry.of(
                SymbolFact.of("lesson", "re-read the queue head before re-arming the retry",
                    Confidence.HIGH).symbol("com.gone.Removed").build())
            .situation("when a consumer reconnects mid-batch")
            .verdict("worked")
            .form(1)
            .build());

        Map<String, Object> report = maint(fqn -> Boolean.FALSE).refresh();

        assertEquals(1, report.get("checked"), "the anchor WAS judged");
        assertTrue(((List<?>) report.get("staled")).isEmpty(),
            "and judged dead — but a form-1 entry is never superseded for it: " + report);

        StoredEntry after = store.all().stream()
            .filter(e -> e.id().equals(id)).findFirst().orElseThrow();
        assertEquals(ExperienceEntry.CANDIDATE, after.status(),
            "the status is untouched — retiring knowledge is a human's decision");
        assertTrue(after.facets().hasDeadEvidence(),
            "and the dead pointer is RECORDED, so the human has something to act on");
        assertEquals(1, ((List<?>) report.get("evidence_dead")).size(),
            "the report names it rather than passing over it in silence: " + report);
    }

    /**
     * A broken workspace does NOT stamp "the evidence is gone" on a whole corpus of
     * experiences.
     *
     * <p>The mass-stale breaker holds status changes when many anchors are judged and
     * not one resolves, because that pattern means the workspace is unloaded, not that
     * the code vanished. Marking evidence dead was originally exempt from it, on the
     * reasoning that a mark changes no status and so can do no harm. It can: the mark
     * is rendered to the agent, it survives export and import, and nothing clears
     * it — there is no inverse of markEvidenceDead. A minute of an unloaded project
     * would have written it, permanently, on every anchored experience in the store.</p>
     *
     * <p>Worse, on a corpus where EVERY entry is form-1 — which is exactly what the
     * form migration produces — the breaker could not trip at all: an unresolvable
     * form-1 anchor lands in neither planned list, so the condition that requires a
     * planned change to be pending was never satisfied. The population most exposed
     * to this was the one population the breaker could not protect.</p>
     */
    @Test
    void a_suspect_workspace_does_not_mark_a_whole_form_one_corpus_evidence_dead() {
        // Three is MASS_STALE_MIN_CHECKED: enough judged anchors for "not one
        // resolved" to mean the workspace rather than the code.
        for (int i = 1; i <= 3; i++) {
            store.put(ExperienceEntry.of(
                    SymbolFact.of("lesson", "re-read the queue head before re-arming " + i,
                        Confidence.HIGH).symbol("com.gone.Removed" + i).build())
                .situation("when a consumer reconnects mid-batch")
                .verdict("worked")
                .form(1)
                .build());
        }

        Map<String, Object> report = maint(fqn -> Boolean.FALSE).refresh();

        assertEquals(3, report.get("checked"), "all three anchors were judged");
        assertEquals(0, report.get("resolved"), "and not one of them resolved");
        assertNull(report.get("evidence_dead"),
            "so NOTHING is marked: the breaker holds the marks exactly as it holds a "
                + "supersede, because a mark withheld is recoverable on the next healthy "
                + "refresh and a mark wrongly written is not. Report: " + report);

        for (StoredEntry e : store.all()) {
            assertFalse(e.facets().hasDeadEvidence(),
                "no entry carries a mark a broken workspace produced: " + e.id());
        }

        // And the report SAYS what it withheld. `held` counted only the two status
        // lists, so once the marks joined them the breaker reported "held: 0" while
        // holding three — a machine-readable field, read by curation, silently wrong.
        // Nothing asserted it, which is why moving the marks under the breaker could
        // change its meaning without a single test noticing.
        assertEquals(Boolean.TRUE, report.get("workspace_suspect"),
            "the breaker tripped, so it owes an account of what it held. Report: " + report);
        assertEquals(3, report.get("held"),
            "all three withheld marks are counted — a held count that omits a whole "
                + "category understates the work a healthy re-run would do. Report: " + report);
    }

    /**
     * <p>The held count promises exactly the work a healthy re-run would do — no more.
     * An entry that already carries the mark still lands in the withheld list, because
     * the list is built before anything looks at what is already marked; but marking it
     * again is a no-op, so counting it would overstate what the breaker is costing. The
     * count therefore mirrors the marking loop's own condition rather than the list
     * length.</p>
     *
     * <p>Both directions of this number have now been wrong: it first omitted the whole
     * mark category and under-reported, and the fix for that over-reported. A count in a
     * machine-readable field is worth only what its assertion says it is.</p>
     */
    @Test
    void the_held_count_promises_only_the_work_a_healthy_rerun_would_do() {
        for (int i = 1; i <= 3; i++) {
            store.put(ExperienceEntry.of(
                    SymbolFact.of("lesson", "drain the retry queue before closing " + i,
                        Confidence.HIGH).symbol("com.gone.Vanished" + i).build())
                .situation("when a consumer reconnects mid-batch")
                .verdict("worked")
                .form(1)
                .build());
        }
        // One of them was already marked by an earlier refresh on a healthy workspace.
        StoredEntry alreadyMarked = store.all().iterator().next();
        assertTrue(store.markEvidenceDead(alreadyMarked.id()),
            "the fixture's precondition: this entry carries the mark before the refresh runs");

        Map<String, Object> report = maint(fqn -> Boolean.FALSE).refresh();

        assertEquals(Boolean.TRUE, report.get("workspace_suspect"),
            "three judged, none resolved — the breaker trips. Report: " + report);
        assertEquals(2, report.get("held"),
            "only the two UNMARKED entries are work a healthy re-run would do; re-marking "
                + "the third is a no-op, so counting it would promise work that does not "
                + "exist. Report: " + report);
    }

    /**
     * <p>A suspect workspace whose marks are ALL already made holds nothing, and says so:
     * `workspace_suspect` with `held: 0`. That pair became reachable only when the count
     * learned to mirror the marking loop, and it is honest — a healthy re-run really
     * would do no work here.</p>
     *
     * <p>It is asserted because the breaker deliberately still trips. The suspect
     * diagnostic is gated on the lists being non-empty, not on there being pending work,
     * so an operator is still told the workspace looks unloaded — the useful half — while
     * the number promises nothing. Leaving the pair unasserted would repeat the omission
     * that already let this count be wrong in both directions.</p>
     */
    @Test
    void a_suspect_workspace_with_nothing_left_to_mark_holds_nothing_and_says_so() {
        for (int i = 1; i <= 3; i++) {
            store.put(ExperienceEntry.of(
                    SymbolFact.of("lesson", "flush before rotating the segment " + i,
                        Confidence.HIGH).symbol("com.gone.Erased" + i).build())
                .situation("when a consumer reconnects mid-batch")
                .verdict("worked")
                .form(1)
                .build());
        }
        for (StoredEntry e : store.all()) {
            assertTrue(store.markEvidenceDead(e.id()),
                "precondition: every entry already carries the mark before the refresh runs");
        }

        Map<String, Object> report = maint(fqn -> Boolean.FALSE).refresh();

        assertEquals(Boolean.TRUE, report.get("workspace_suspect"),
            "the workspace still looks unloaded and the operator is still told so — the "
                + "diagnostic does not depend on there being pending work. Report: " + report);
        assertEquals(0, report.get("held"),
            "and the count promises nothing, because a healthy re-run would do nothing: "
                + "every mark is already made. Report: " + report);
        assertNull(report.get("evidence_dead"),
            "nothing was marked in this run either. Report: " + report);
    }

    /** Marking is idempotent: a second refresh re-reports nothing and rewrites nothing. */
    @Test
    void a_second_refresh_does_not_re_mark_the_same_dead_evidence() {
        store.put(ExperienceEntry.of(
                SymbolFact.of("lesson", "re-read the queue head before re-arming the retry",
                    Confidence.HIGH).symbol("com.gone.Removed").build())
            .situation("when a consumer reconnects mid-batch")
            .verdict("worked")
            .form(1)
            .build());
        ExperienceMaintenance m = maint(fqn -> Boolean.FALSE);

        assertEquals(1, ((List<?>) m.refresh().get("evidence_dead")).size());
        assertFalse(m.refresh().containsKey("evidence_dead"),
            "the second pass writes nothing — an UPDATE per entry per refresh is how"
                + " the store file grew on every click");
    }

    @Test
    void refresh_judges_only_active_entries() {
        // Sprint 21b: re-superseding an already superseded entry wrote an UPDATE per
        // refresh — with refresh automatic after every load, the store grew per click.
        store.put(ExperienceEntry.of(
            SymbolFact.of("lesson", "note", Confidence.HIGH).symbol("com.gone.Removed").build()).build());
        ExperienceMaintenance m = maint(fqn -> Boolean.FALSE);
        assertEquals(1, ((List<?>) m.refresh().get("staled")).size(), "first pass supersedes");

        Map<String, Object> second = m.refresh();
        assertEquals(0, second.get("checked"), "superseded entries are not re-judged");
        assertEquals(0, ((List<?>) second.get("staled")).size(), "and never re-written");
    }

    @Test
    void refresh_with_no_project_skips_without_flagging() {
        store.put(ExperienceEntry.of(
            SymbolFact.of("lesson", "note", Confidence.HIGH).symbol("com.a.Foo").build()).build());
        Map<String, Object> report = maint(fqn -> null).refresh();
        assertEquals(1, report.get("checked"));
        assertEquals(1, report.get("skipped"));
        assertEquals(0, ((List<?>) report.get("staled")).size());
    }

    // --- Sprint 21a (item C): default roots + link-graph traversal ------------------------

    @Test
    void load_follows_the_link_graph_from_an_index_file(@TempDir Path dir) throws IOException {
        // MEMORY.md-style index: a wikilink + a relative markdown link into a subdir; the
        // linked files are reachable ONLY via links (the index is the single root).
        Files.createDirectory(dir.resolve("sub"));
        Files.writeString(dir.resolve("MEMORY.md"),
            "- [Fact A](fact-a.md) — hook\n- see also [[fact-b]]\n");
        writeMemory(dir, "fact-a.md", "name: fact-a\ndescription: fact a states the first thing\ntype: reference",
            "a links onward to [b2](sub/fact-c.md)");
        writeMemory(dir, "fact-b.md", "name: fact-b\ndescription: fact b states the second thing\ntype: reference", "leaf");
        Files.writeString(dir.resolve("sub").resolve("fact-c.md"),
            "---\nname: fact-c\ndescription: fact c states the third thing\ntype: reference\n---\nleaf");

        Map<String, Object> report = maint(fqn -> null)
            .loadSources(List.of(dir.resolve("MEMORY.md")), false, 5, 200, 2_000_000L);
        // v2.2.3: the index itself is a link hub, not knowledge — 3 linked facts, no junk row.
        assertEquals(3, report.get("loaded"), "wikilink + mdlink + transitive mdlink; index skipped");
        assertEquals(3L, store.count());
        assertTrue(((List<?>) report.get("skipped")).isEmpty());
    }

    @Test
    void load_link_cycle_terminates(@TempDir Path dir) throws IOException {
        writeMemory(dir, "a.md", "name: a\ndescription: note a points at note b\ntype: reference", "see [[b]]");
        writeMemory(dir, "b.md", "name: b\ndescription: note b points back at note a\ntype: reference", "see [[a]]");
        Map<String, Object> report = maint(fqn -> null)
            .loadSources(List.of(dir.resolve("a.md")), false, 5, 200, 2_000_000L);
        assertEquals(2, report.get("loaded"), "a↔b crawled once each");
    }

    @Test
    void load_caps_are_honest_not_silent(@TempDir Path dir) throws IOException {
        writeMemory(dir, "a.md", "name: a\ndescription: note a points at note b\ntype: reference", "x");
        writeMemory(dir, "b.md", "name: b\ndescription: note b points back at note a\ntype: reference", "x");
        writeMemory(dir, "c.md", "name: c\ndescription: note c stands on its own\ntype: reference", "x");
        Map<String, Object> report = maint(fqn -> null)
            .loadSources(List.of(dir), false, 5, 2, 2_000_000L);
        assertEquals(2, report.get("loaded"), "max-files cap");
        assertFalse(((List<?>) report.get("skipped")).isEmpty(), "the drop is reported, not silent");
    }

    @Test
    void load_recursive_walks_subdirectories(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("nested"));
        Files.writeString(dir.resolve("nested").resolve("deep.md"),
            "---\nname: deep\ndescription: the deep note sits below the depth cap\ntype: reference\n---\nbody");
        assertEquals(0, maint(fqn -> null).load(dir, false).get("loaded"),
            "top-level listing does not see nested files");
        assertEquals(1, maint(fqn -> null).load(dir, true).get("loaded"),
            "recursive mode walks subdirectories");
    }

    @Test
    void load_skips_unchanged_files_entirely(@TempDir Path dir) throws IOException {
        // Sprint 21b (Harald): every load rewrote every entry — logical count stable but
        // the H2 file grew each click. An unchanged source must cause NO write at all.
        writeMemory(dir, "a.md", "name: a\ndescription: fact a states the first thing\ntype: reference", "body a");
        writeMemory(dir, "b.md", "name: b\ndescription: fact b states the second thing\ntype: reference", "body b");
        ExperienceMaintenance m = maint(fqn -> null);
        assertEquals(2, m.load(dir, true).get("loaded"));

        Map<String, Object> second = m.load(dir, true);
        assertEquals(0, second.get("loaded"), "no rewrite of unchanged sources");
        assertEquals(2, second.get("unchanged"));
        assertEquals(2, second.get("files"), "files = loaded + unchanged");
        assertEquals(2L, store.count());

        writeMemory(dir, "a.md", "name: a\ndescription: fact a states something different now\ntype: reference", "body a2");
        Map<String, Object> third = m.load(dir, true);
        assertEquals(1, third.get("loaded"), "changed source is re-ingested");
        assertEquals(1, third.get("unchanged"));
        assertEquals(2L, store.count());
    }

    @Test
    void loader_version_bump_reingests_unchanged_files_once(@TempDir Path dir) throws IOException {
        // v2.2.6 (find #14): skip-unchanged blocked retroactive enrichment — an entry
        // stored under an OLDER loader fingerprint must re-ingest even though the file
        // bytes are identical; within one version idempotency stays byte-strict.
        writeMemory(dir, "a.md", "name: a\ndescription: fact a states the first thing\ntype: reference", "body");
        String content = java.nio.file.Files.readString(dir.resolve("a.md"));
        ExperienceMaintenance m = maint(fqn -> null);
        assertEquals(1, m.load(dir, true).get("loaded"));

        // Simulate a pre-bump store: rewrite the entry with the OLD (content-only) hash.
        String sourceRef = "memory:" + dir.resolve("a.md").toAbsolutePath().normalize();
        store.deleteBySource(sourceRef);
        store.putWithSource(ExperienceEntry.of(
                SymbolFact.of("lesson", "fact a", Confidence.MEDIUM).build())
                .status(ExperienceEntry.ACCEPTED).build(),
            sourceRef, "0000-old-loader-hash");
        assertEquals(1, m.load(dir, true).get("loaded"), "stale fingerprint → re-ingested");

        Map<String, Object> again = m.load(dir, true);
        assertEquals(0, again.get("loaded"), "current fingerprint → byte-strict skip");
        assertEquals(1, again.get("unchanged"));
    }

    @Test
    void load_indexes_the_name_as_a_symptom_cue(@TempDir Path dir) throws IOException {
        // v2.2.5 dogfood find #13: the frontmatter NAME is where cue-dense phrasing lives
        // ("Tauri webview renders blank on aarch64") while the description may use other
        // words ("stays the GTK background colour") — recall by "blank webview" missed
        // the entry because the loader dropped the name entirely.
        writeMemory(dir, "webkit.md",
            "name: Tauri webview renders blank on aarch64\n"
                + "description: DMABUF compositor fails silently; content area stays the GTK background colour\n"
                + "type: reference",
            "fix body");
        assertEquals(1, maint(fqn -> null).load(dir, true).get("loaded"));

        ExperienceRetrieval retrieval = new ExperienceRetrieval(store, () -> null);
        Map<String, Object> r = retrieval.recall(new RecallQuery(null, null, null, "blank webview", null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"),
            "name tokens are recallable symptom cues");
    }

    @Test
    void load_skips_contentless_index_files_but_follows_their_links(@TempDir Path dir) throws IOException {
        // v2.2.3 dogfood find: MEMORY.md-style indexes ingested as junk rows ("MEMORY", "''").
        Files.writeString(dir.resolve("MEMORY.md"), "- [Fact](fact.md) — hook\n");
        writeMemory(dir, "fact.md", "name: f\ndescription: a real fact with a real body\ntype: reference", "body");
        Map<String, Object> report = maint(fqn -> null).loadSources(
            java.util.List.of(dir.resolve("MEMORY.md")), false, 5, 200, 2_000_000L);
        assertEquals(1, report.get("loaded"), "index skipped, linked fact ingested");
        assertEquals(1L, store.count());
    }

    // --- Sprint 28c (D3): the form gate, in its LOADED stance ---------------------------

    /**
     * The half that BINDS on a crawl. A heading is not knowledge whoever wrote
     * it, and this path is where headings get in: the section splitter mints one
     * entry per heading, which is how "Test plan" and "Required follow-up" became
     * rows in this store. The refusal is loud — reported and counted — because a
     * silent drop is the failure this project keeps re-learning.
     */
    @Test
    void a_heading_shaped_memory_file_is_refused_and_the_refusal_is_reported(@TempDir Path dir)
            throws IOException {
        // Typed as a FACT deliberately: the shape check binds on every type, so
        // this must refuse even where the experience form does not apply.
        writeMemory(dir, "h.md", "name: h\ndescription: Critical bugs found:\ntype: domain_fact",
            "body");
        Map<String, Object> report = maint(fqn -> null).load(dir, true);

        assertEquals(0, report.get("loaded"), "a heading is not an entry");
        assertEquals(0L, store.count());
        assertEquals(1, report.get("form_refused"), "the count is in the report");
        List<?> skipped = (List<?>) report.get("skipped");
        assertEquals(1, skipped.size(), "and the file is named in the skip list");
        String reason = String.valueOf(((Map<?, ?>) skipped.get(0)).get("reason"));
        assertTrue(reason.contains("RULE:"), "the skip carries the rule: " + reason);
        assertTrue(reason.contains("REPHRASE:"),
            "and the rephrase that would let the file in: " + reason);
    }

    /**
     * The SAME FILTER as the record verb — the type decides what is owed, not
     * the surface a file arrives through. A file typed {@code lesson} without an
     * outcome is refused here exactly as it is at {@code record}.
     *
     * <p>The obvious worry is that this turns away a corpus of pre-28c memory
     * files, and it was MEASURED rather than argued: across 1,932 markdown files
     * under the configured roots, the count declaring {@code lesson} or
     * {@code failure_mode} is ZERO. The frontmatter vocabulary in real use is
     * feedback / project / domain_fact / reference. The strict filter costs
     * nothing real; a looser one would have been a design change bought with an
     * imagined corpus.</p>
     */
    @Test
    void a_loaded_lesson_without_an_outcome_is_refused_exactly_as_a_recorded_one(
            @TempDir Path dir) throws IOException {
        writeMemory(dir, "l.md",
            "name: l\ndescription: re-read the queue head before re-arming the retry\ntype: lesson",
            "body");
        Map<String, Object> report = maint(fqn -> null).load(dir, true);

        assertEquals(0, report.get("loaded"), "a lesson with no situation is refused");
        assertEquals(1, report.get("form_refused"));
        assertEquals(0L, store.count());
    }

    /**
     * The OUTCOME half, discriminated on its own. The test above supplies neither
     * field, so the gate refuses on `situation` and returns before the verdict
     * branch is ever reached — delete the verdict branch entirely and it stays
     * green. This one gives a valid situation and withholds only the outcome, so
     * it can fail for exactly one reason, and it names the field to prove which.
     */
    @Test
    void a_loaded_lesson_with_a_situation_but_no_outcome_is_refused_on_the_verdict(
            @TempDir Path dir) throws IOException {
        writeMemory(dir, "v.md",
            "name: v\ndescription: re-read the queue head before re-arming the retry\n"
                + "type: lesson\nsituation: when a consumer reconnects mid-batch",
            "body");
        Map<String, Object> report = maint(fqn -> null).load(dir, true);

        assertEquals(0, report.get("loaded"));
        assertEquals(1, report.get("form_refused"));
        String reason = String.valueOf(
            ((Map<?, ?>) ((List<?>) report.get("skipped")).get(0)).get("reason"));
        assertTrue(reason.contains("form — verdict:"),
            "refused on the OUTCOME, not on the situation it did supply: " + reason);
    }

    /**
     * THE PAIR, and the half a one-sided test cannot see: the same file typed as
     * a FACT lands untouched. Without this, "refused" above could mean the ingest
     * is simply broken — and it pins the type-awareness that Harald ruled on
     * (2026-08-21: "you cannot just form everything upfront into lessons").
     */
    @Test
    void the_same_file_typed_as_a_fact_lands_unclassified(@TempDir Path dir) throws IOException {
        writeMemory(dir, "l.md",
            "name: l\ndescription: the retry loop re-reads the queue head before re-arming\n"
                + "type: domain_fact",
            "body");
        Map<String, Object> report = maint(fqn -> null).load(dir, true);

        assertEquals(1, report.get("loaded"), "a fact owes no outcome and is not held to one");
        assertEquals(null, report.get("form_refused"), "and nothing was refused");
        Map<String, Object> landed = store.exportEntries(null, null).get(0);
        assertFalse(landed.containsKey("situation"),
            "stored as what it honestly is — no situation was invented for it");
        assertFalse(landed.containsKey("form"),
            "form is ABSENT (unclassified), never 0 dressed up as a decision");
    }

    /**
     * A refused file must not cut the crawl short. Without this test, deleting
     * the link-following in the refusal branch leaves the whole suite green —
     * and a silent drop is this project's recorded deepest bug class, so the
     * claim "the links are still followed" cannot rest on reading the code.
     */
    @Test
    void a_refused_file_still_yields_its_links(@TempDir Path dir) throws IOException {
        writeMemory(dir, "bad.md",
            "name: bad\ndescription: re-read the queue head before re-arming the retry\ntype: lesson",
            "onward to [[good]]");
        writeMemory(dir, "good.md",
            "name: good\ndescription: the retry loop re-reads the queue head first\n"
                + "type: reference",
            "leaf");

        Map<String, Object> report = maint(fqn -> null)
            .loadSources(List.of(dir.resolve("bad.md")), false, 5, 200, 2_000_000L);

        assertEquals(1, report.get("form_refused"), "the malformed file is refused");
        assertEquals(1, report.get("loaded"),
            "and the file it points at is still reached — one bad note does not"
                + " end the crawl behind it");
    }

    /**
     * The OTHER half of that fix, which the test above cannot reach: at the depth
     * boundary the refused file's links are dropped, and the drop must be
     * REPORTED, exactly as the admitted path reports it.
     *
     * <p>Written because round 2 of the checkpoint audit caught the first version
     * of this pair proving only half of what it claimed — the seed sits at depth
     * 0, so with any sane {@code maxDepth} the boundary branch never executes and
     * deleting it left the whole suite green. {@code maxDepth = 0} is the only
     * value that puts the seed itself on the boundary.</p>
     */
    @Test
    void a_refusal_at_the_depth_boundary_reports_the_links_it_drops(@TempDir Path dir)
            throws IOException {
        writeMemory(dir, "bad.md",
            "name: bad\ndescription: re-read the queue head before re-arming the retry\ntype: lesson",
            "onward to [[good]]");
        writeMemory(dir, "good.md",
            "name: good\ndescription: the retry loop re-reads the queue head first\n"
                + "type: reference",
            "leaf");

        Map<String, Object> report = maint(fqn -> null)
            .loadSources(List.of(dir.resolve("bad.md")), false, 0, 200, 2_000_000L);

        assertEquals(1, report.get("form_refused"));
        assertEquals(0, report.get("loaded"), "at depth 0 the link is not followed");
        List<?> skipped = (List<?>) report.get("skipped");
        assertTrue(
            skipped.stream().map(String::valueOf).anyMatch(s -> s.contains("max-depth (0)")),
            "the dropped link is REPORTED, not swallowed by the refusal that"
                + " accompanies it: " + skipped);
    }

    /**
     * A file that DOES declare its form keeps it — the write path carries the new
     * fields on this path too, not only through the record verb.
     */
    @Test
    void a_loaded_file_that_declares_its_form_keeps_it(@TempDir Path dir) throws IOException {
        writeMemory(dir, "f.md",
            "name: f\ndescription: re-read the queue head before re-arming the retry\n"
                + "type: reference\nsituation: when a consumer reconnects mid-batch\n"
                + "verdict: worked",
            "body");
        assertEquals(1, maint(fqn -> null).load(dir, true).get("loaded"));

        Map<String, Object> landed = store.exportEntries(null, null).get(0);
        assertEquals("when a consumer reconnects mid-batch", landed.get("situation"));
        assertEquals("worked", landed.get("verdict"));
        assertEquals(1, landed.get("form"), "declared form is form-1");
        // The vocabulary is the architecture's, not a synonym of my choosing:
        // ARCHITECTURE-knowledge-layer.md names recorded | ingested | catalog |
        // seat_run, and Stage 9's disposition report groups on exactly these.
        assertEquals("ingested", landed.get("provenance_kind"),
            "and it says where it came from — an ingested row is not a recorded one");
    }

    /**
     * A SECTIONED file's children inherit the file's form. One write must not
     * produce a form-1 parent and form-null children: Stage 6 sorts the two
     * corpora on `form`, so that split would drop half of every ingested file
     * into the legacy lane. Sections carry no frontmatter of their own, so
     * inheritance is the only channel they have.
     */
    @Test
    void the_sections_of_a_form_carrying_file_inherit_its_form(@TempDir Path dir)
            throws IOException {
        writeMemory(dir, "s.md",
            "name: s\ndescription: re-read the queue head before re-arming the retry\n"
                + "type: lesson\nsituation: when a consumer reconnects mid-batch\n"
                + "verdict: worked",
            "preamble text\n\n## Draining the batch\n\nThe head moves under you.\n");
        assertEquals(1, maint(fqn -> null).load(dir, true).get("loaded"));

        List<Map<String, Object>> rows = store.exportEntries(null, null);
        assertEquals(2, rows.size(), "a parent and one section");
        for (Map<String, Object> row : rows) {
            assertEquals(1, row.get("form"),
                "every row from one declaration carries the same form: " + row.get("summary"));
            assertEquals("when a consumer reconnects mid-batch", row.get("situation"));
            assertEquals("ingested", row.get("provenance_kind"),
                "including the section — Stage 9's report groups on this column");
        }
    }

    /**
     * A situation that is a LOCATION is refused on this path too. A wrong
     * condition is worse than a missing one, because it matches confidently — so
     * this check binds even where the form itself does not.
     */
    @Test
    void a_loaded_situation_that_is_a_path_is_refused(@TempDir Path dir) throws IOException {
        writeMemory(dir, "p.md",
            "name: p\ndescription: re-read the queue head before re-arming the retry\n"
                + "type: reference\nsituation: src/main/java/com/example/Retry.java",
            "body");
        Map<String, Object> report = maint(fqn -> null).load(dir, true);

        assertEquals(0, report.get("loaded"));
        assertEquals(1, report.get("form_refused"));
        String reason = String.valueOf(
            ((Map<?, ?>) ((List<?>) report.get("skipped")).get(0)).get("reason"));
        assertTrue(reason.contains("situation"), "the field is named: " + reason);
    }

    // ------------------------------------------------------------------------------
    // DELETED at Sprint 28d S10.0 — three tests, and they were CORRECT tests of a
    // mechanism that no longer exists:
    //
    //   load_harvests_body_structure_as_symptom_cues   headings and **bold** phrases
    //                                                  become cues
    //   harvest_skips_fenced_code_blocks               ...but not inside ``` fences
    //   harvest_indexes_quoted_error_strings_as_cues   "quoted strings" become cues
    //
    // The cue harvester is gone (see ExperienceMaintenance, same sprint): cues are
    // declared in frontmatter, and a file that declares none has none. So all three
    // assert an outcome the loader is now designed NOT to produce.
    //
    // THEY ARE DELETED RATHER THAN ADJUSTED, and the difference matters. Rewriting them
    // to expect the new behaviour would leave three tests whose SUBJECT is a harvester,
    // dressed as tests of something else — and the next person would read them as an
    // argument for bringing it back. Two sibling tests WERE rewritten instead, because
    // the property each pinned still exists and only its input changed: the admission
    // routing and the per-entry cap now apply to declared cues.
    //
    // What replaces them: DeclaredCuesReplaceHarvestingTest, which asserts the
    // opposite direction — that a story's own section headings resolve to NOTHING.
    // ------------------------------------------------------------------------------

    /**
     * Sprint 28d S10.0 — REWRITTEN against DECLARED cues; the property is unchanged.
     *
     * <p>Thirty per entry is a runaway backstop and hitting it is REPORTED, never
     * silent — a silent drop is this project's recorded deepest bug class. What changed
     * is only what the cap counts: it used to bound what a harvester scraped out of a
     * body, and now bounds what a person wrote down. That makes hitting it a stronger
     * signal rather than a weaker one — forty scraped phrases is an accident of prose
     * length, forty declared cues is someone doing something odd on purpose.</p>
     */
    @Test
    void the_per_entry_cue_cap_applies_to_declared_cues_and_is_reported(@TempDir Path dir)
            throws IOException {
        StringBuilder cues = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            cues.append(i == 0 ? "" : ", ").append("unique cue number ").append(i);
        }
        writeMemory(dir, "big.md",
            "name: big\ndescription: the cue cap applies to this entry\ntype: reference\n"
                + "symptoms: " + cues,
            "a body with no cues of its own");

        Map<String, Object> report = maint(fqn -> null).load(dir, true);
        assertEquals(1, report.get("loaded"));
        assertEquals(1, report.get("keyword_capped"), "cap hit is reported, not silent");
        StoredEntry e = store.all().get(0);
        assertEquals(31, e.symptoms().size(), "30 declared cues + the name symptom");
    }

    @Test
    void load_splits_sections_into_atomic_entries(@TempDir Path dir) throws IOException {
        // Sprint 21c (item B): files are bundles — one entry per heading section plus a
        // thin file-level parent, so the fit gate can answer with the FACT.
        writeMemory(dir, "bundle.md",
            "name: bundle\ndescription: two facts wearing one coat\ntype: reference",
            "Preamble before any heading.\n\n"
                + "## First atomic fact\n\nbody one with [[linked-note]].\n\n"
                + "## Second atomic fact\n\nbody two.\n");
        assertEquals(1, maint(fqn -> null).load(dir, true).get("loaded"), "loaded counts FILES");
        assertEquals(3L, store.count(), "parent + 2 sections");

        List<StoredEntry> all = store.all();
        StoredEntry parent = all.stream().filter(e -> !e.isSection()).findFirst().orElseThrow();
        assertEquals("two facts wearing one coat", parent.summary());
        List<StoredEntry> sections = all.stream().filter(StoredEntry::isSection).toList();
        assertEquals(2, sections.size());
        StoredEntry first = sections.stream()
            .filter(e -> "First atomic fact".equals(e.summary())).findFirst().orElseThrow();
        assertTrue(store.get(first.id()).orElseThrow().toString().contains("body one"),
            "section details = the section body");
        String sourceRef = "memory:" + dir.resolve("bundle.md").toAbsolutePath().normalize();
        assertTrue(all.stream().allMatch(e -> sourceRef.equals(e.sourceRef())),
            "whole family shares the file-level source_ref");
    }

    @Test
    void section_family_reingests_as_one_unit(@TempDir Path dir) throws IOException {
        writeMemory(dir, "a.md", "name: a\ndescription: note da carries the first body\ntype: reference",
            "## A one\n\nx\n\n## A two\n\ny\n");
        writeMemory(dir, "b.md", "name: b\ndescription: note db carries the second body\ntype: reference",
            "## B one\n\nz\n");
        ExperienceMaintenance m = maint(fqn -> null);
        assertEquals(2, m.load(dir, true).get("loaded"));
        assertEquals(5L, store.count(), "(parent+2) + (parent+1)");

        writeMemory(dir, "a.md", "name: a\ndescription: note da carries the first body\ntype: reference",
            "## A one\n\nx CHANGED\n\n## A two\n\ny\n");
        Map<String, Object> second = m.load(dir, true);
        assertEquals(1, second.get("loaded"), "only the changed file re-ingests");
        assertEquals(1, second.get("unchanged"));
        assertEquals(5L, store.count(), "family replaced as one unit, nothing duplicated");
    }

    @Test
    void headingless_files_stay_single_entries(@TempDir Path dir) throws IOException {
        writeMemory(dir, "flat.md", "name: flat\ndescription: this file carries no headings at all\ntype: reference",
            "just prose with **a phrase** and nothing else.\n");
        assertEquals(1, maint(fqn -> null).load(dir, true).get("loaded"));
        assertEquals(1L, store.count(), "no headings, no split — exactly today's shape");
        assertFalse(store.all().get(0).isSection());
    }

    @Test
    void fenced_heading_lines_do_not_split(@TempDir Path dir) throws IOException {
        writeMemory(dir, "f.md", "name: f\ndescription: a code fence sits inside this body\ntype: reference",
            "prose\n```md\n# not a heading\n```\nmore prose\n");
        assertEquals(1, maint(fqn -> null).load(dir, true).get("loaded"));
        assertEquals(1L, store.count(), "a heading inside a fence is code, not a boundary");
    }

    @Test
    void dedup_never_merges_file_backed_entries(@TempDir Path dir) throws IOException {
        // Sprint 21c plan finding: generic headings ("Context", "DoD") repeat ACROSS
        // files — the clean-up chain must not eat sections. Files are the source of
        // truth and families are idempotent; duplicates are fixed in the files.
        writeMemory(dir, "one.md", "name: one\ndescription: the first note holds its own body\ntype: reference",
            "## Context\n\nfact one.\n");
        writeMemory(dir, "two.md", "name: two\ndescription: the second note holds its own body\ntype: reference",
            "## Context\n\nfact two.\n");
        ExperienceMaintenance m = maint(fqn -> null);
        assertEquals(2, m.load(dir, true).get("loaded"));

        m.dedup(true);
        long active = store.all().stream()
            .filter(e -> !ExperienceEntry.SUPERSEDED.equals(e.status())).count();
        assertEquals(4L, active, "same-heading sections across files are NOT duplicates");
    }

    /**
     * Sprint 28d S10.0 — REWRITTEN, and it was silently VACUOUS before the rewrite.
     *
     * <p>The old version put a 600-character {@code **bold**} phrase in the BODY and
     * asserted the file loaded. Once the harvester was deleted that phrase stopped
     * being a cue at all, so the test passed by loading a file with no over-long value
     * anywhere near the column — green, and testing nothing. It would have stayed that
     * way indefinitely: nothing about a passing test announces that its subject left.</p>
     *
     * <p>The property is real and still applies: {@code experience_symptom.symptom} is
     * {@code VARCHAR(512)}, and one over-long value fails the INSERT — so the whole file
     * fails to load because of a single phrase. The clip used to live in the harvester's
     * {@code addKeyword} and left with it; it now lives where declared cues enter.</p>
     */
    @Test
    void an_oversize_declared_cue_is_clipped_and_the_file_still_loads(@TempDir Path dir)
            throws IOException {
        writeMemory(dir, "l.md",
            "name: l\ndescription: an oversize declared cue sits in this frontmatter\n"
                + "type: reference\nsymptoms: " + "x".repeat(600),
            "body");

        assertEquals(1, maint(fqn -> null).load(dir, true).get("loaded"),
            "an over-long cue is clipped to the column, so the INSERT survives — before"
                + " the clip moved here, one long phrase failed the whole file");

        // AND IT ARRIVED, clipped rather than dropped. Asserting only that the load
        // survived would also pass if the cue were discarded entirely, which is a
        // different behaviour wearing the same green.
        StoredEntry e = store.all().get(0);
        assertTrue(e.symptoms().stream().anyMatch(s -> s.length() == 512),
            "the cue is present at exactly the column width: " + e.symptoms());
    }

    @Test
    void load_derives_summary_from_first_content_line_when_no_frontmatter(@TempDir Path dir) throws IOException {
        // CLAUDE.md-style files: no frontmatter, but the body IS the knowledge.
        Files.writeString(dir.resolve("CLAUDE.md"),
            "# Always use jawata before shell text tools\n\nIt is compiler-accurate.\n");
        assertEquals(1, maint(fqn -> null).load(dir, false).get("loaded"));
        // Sprint 21c: the heading also splits into a section — parent AND section carry
        // the derived summary; the filename is a junk summary in neither.
        assertTrue(store.all().stream().anyMatch(e -> "Always use jawata before shell text tools".equals(e.summary())),
            "summary derived from the first heading, not the filename");
        assertTrue(store.all().stream().noneMatch(e -> "CLAUDE".equals(e.summary())));
    }

    @Test
    void derived_summary_skips_html_comment_markers(@TempDir Path dir) throws IOException {
        // Loader v3: CLAUDE.md files often START with managed-section markers — a summary
        // of "<!-- collaboration-spec:start -->" is a junk row.
        Files.writeString(dir.resolve("CLAUDE.md"),
            "<!-- collaboration-spec:start -->\n\n# Parallel work goes in one message\n\nRules body.\n");
        assertEquals(1, maint(fqn -> null).load(dir, false).get("loaded"));
        assertTrue(store.all().stream().anyMatch(e -> "Parallel work goes in one message".equals(e.summary())),
            "HTML comments are not content");
        assertTrue(store.all().stream()
                .noneMatch(e -> String.valueOf(e.summary()).startsWith("<!--")),
            "no junk summaries from managed-section markers");
    }

    @Test
    void load_ingests_mdc_files_from_directories(@TempDir Path dir) throws IOException {
        // Sprint 21b (item C2): Cursor project rules are .mdc — directory crawls accept them.
        Files.writeString(dir.resolve("rule.mdc"),
            "---\ndescription: prefer composition over inheritance here\n---\nCursor rule body");
        writeMemory(dir, "plain.md", "name: p\ndescription: this note carries a plain body\ntype: reference", "x");
        assertEquals(2, maint(fqn -> null).load(dir, true).get("loaded"),
            ".mdc crawled alongside .md");
    }

    @Test
    void default_caps_are_runaway_backstops_not_tuning_values(@TempDir Path dir) throws IOException {
        // Sprint 21b (item C): "I want everything you can find" — a memory tree LARGER than
        // the old tuning caps (depth 5 / 200 files) must ingest COMPLETELY with the defaults.
        for (int i = 0; i < 210; i++) {
            writeMemory(dir, "n" + i + ".md", "name: n" + i + "\ndescription: note " + i + " carries its own body\ntype: reference", "x");
        }
        Path deep = dir;
        for (int i = 0; i < 7; i++) {
            deep = deep.resolve("d" + i);
        }
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("deepest.md"),
            "---\nname: deepest\ndescription: this note sits seven levels down\ntype: reference\n---\nbody");

        Map<String, Object> report = maint(fqn -> null).load(dir, true);
        assertEquals(211, report.get("loaded"), "210 flat + 1 at depth 7 — nothing dropped");
        assertTrue(((List<?>) report.get("skipped")).isEmpty(), "no backstop fired");
    }

    @Test
    void load_without_path_uses_default_roots(@TempDir Path dir) throws IOException {
        writeMemory(dir, "seed.md", "name: s\ndescription: this note was seeded before the run\ntype: domain_fact", "x");
        ExperienceMaintenance withRoots =
            new ExperienceMaintenance(store, fqn -> null, () -> List.of(dir));
        assertTrue(withRoots.hasDefaultRoots());
        assertEquals(1, withRoots.load(null, false).get("loaded"));

        ExperienceMaintenance noRoots = maint(fqn -> null);
        assertFalse(noRoots.hasDefaultRoots());
        assertTrue(noRoots.load(null, false).containsKey("error"),
            "no path + no roots = explicit error");
    }

    // --- Sprint 21a (item I): non-Java anchors are opaque to JDT maintenance --------------

    @Test
    void refresh_never_supersedes_a_non_java_anchor() {
        String rustId = store.put(ExperienceEntry.of(
                SymbolFact.of("lesson", "peel stops at closing quote", Confidence.HIGH)
                    .symbol("manager_service::build_recall_script").build())
            .language("rust").status(ExperienceEntry.ACCEPTED).build());
        String javaId = store.put(ExperienceEntry.of(
                SymbolFact.of("lesson", "gone type", Confidence.HIGH)
                    .symbol("com.gone.Removed").build())
            .status(ExperienceEntry.ACCEPTED).build());

        // A resolver that resolves NOTHING — the destructive case for foreign anchors.
        Map<String, Object> report = maint(fqn -> Boolean.FALSE).refresh();
        assertEquals(1, report.get("checked"), "only the Java anchor is judged");
        assertEquals(1, report.get("non_java"));
        assertEquals(1, ((List<?>) report.get("staled")).size());

        StoredEntry rust = store.all().stream().filter(e -> e.id().equals(rustId)).findFirst().orElseThrow();
        assertEquals(ExperienceEntry.ACCEPTED, rust.status(), "Rust anchor survives refresh untouched");
        assertEquals("rust", rust.language());
        StoredEntry java = store.all().stream().filter(e -> e.id().equals(javaId)).findFirst().orElseThrow();
        assertEquals(ExperienceEntry.SUPERSEDED, java.status(), "genuinely stale Java pointer still superseded");
    }

    @Test
    void load_does_not_flag_non_java_symbols_stale(@TempDir Path dir) throws IOException {
        writeMemory(dir, "rust.md",
            "name: n\ndescription: this note carries a body worth keeping\ntype: reference\nsymbol: gateway::forward\nlanguage: rust", "body");
        Map<String, Object> report = maint(fqn -> Boolean.FALSE).load(dir);
        assertEquals(1, report.get("loaded"));
        assertEquals(0, ((List<?>) report.get("stale")).size(),
            "a Rust anchor is not judged by the JDT resolver at ingest");
        assertEquals("rust", store.all().get(0).language(), "frontmatter language persisted");
    }

    @Test
    void wipe_clears_everything() {
        store.put(SymbolFact.of("lesson", "a", Confidence.LOW).symbol("com.a.Foo").build());
        store.put(SymbolFact.of("lesson", "b", Confidence.LOW).symbol("com.b.Bar").build());
        Map<String, Object> report = maint(fqn -> null).wipe();
        assertEquals(2L, report.get("removed"));
        assertEquals(0L, store.count());
    }

    /**
     * jawata-mcp#7: the CLAUDE.md ingest defects, four in one fixture — an
     * untyped memory file with managed-block markers, inline code spans, two
     * heading sections, and a byte-identical sibling.
     */
    @Test
    void load_ingest_is_clean_reaches_primer_and_dedupes_identical_files(@TempDir Path dir)
            throws IOException {
        String md = "<!-- jawata-studio:claude:start -->\n"
            + "# Prefer jawata over shell text tools\n\n"
            + "Use `grep` only as a fallback. Prefer `claude` tools and `sed` sparingly.\n\n"
            + "## Run independent operations in parallel\n\n"
            + "Run independent operations in parallel when they do not depend on each other.\n"
            + "<!-- jawata-studio:claude:end -->\n";
        Files.writeString(dir.resolve("CLAUDE.md"), md);
        Files.writeString(dir.resolve("CLAUDE-copy.md"), md);   // byte-identical sibling

        Map<String, Object> report = maint(fqn -> null).load(dir);

        // Symptom 3: the identical sibling is ingested ONCE, and the skip is reported.
        assertEquals(1, report.get("loaded"), "the byte-identical copy is not a second ingest");
        assertEquals(1, report.get("duplicate_content"), "the copy is reported as duplicate content");

        List<StoredEntry> all = store.all();
        for (StoredEntry e : all) {
            String summary = e.summary() == null ? "" : e.summary();
            Object details = e.body() == null ? null : e.body().get("details");
            String detailStr = details == null ? "" : details.toString();
            List<String> symptoms = e.symptoms() == null ? List.of() : e.symptoms();

            // Symptom 4: no managed-block marker survives into any field.
            assertFalse(summary.contains("jawata-studio") || detailStr.contains("jawata-studio")
                    || symptoms.stream().anyMatch(s -> s.contains("jawata-studio")),
                "a marker leaked into an entry: " + e.summary() + " / " + symptoms);

            // Symptom 2: no inline code span became a recall cue.
            for (String cue : List.of("grep", "claude", "sed")) {
                assertFalse(symptoms.stream().anyMatch(s -> s.equalsIgnoreCase(cue)),
                    "a code span poisoned the symptom index: " + cue + " in " + symptoms);
            }
        }

        // Symptom 1: the sections reach the always-on primer (scope_kind=section).
        ExperienceRetrieval retrieval = new ExperienceRetrieval(store, () -> null);
        Map<String, Object> primer = retrieval.primer(20, ExperienceRetrieval.RETRIEVAL_BUDGET_MILLIS);
        assertEquals(ExperienceRetrieval.RESULT_PRIMER, primer.get("result"),
            "loaded sections must reach the primer, not starve it");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) primer.get("entries");
        List<String> summaries = entries.stream().map(m -> String.valueOf(m.get("summary"))).toList();
        assertTrue(summaries.contains("Prefer jawata over shell text tools")
                && summaries.contains("Run independent operations in parallel"),
            "both section headings are domain nodes in the primer: " + summaries);
    }
}
