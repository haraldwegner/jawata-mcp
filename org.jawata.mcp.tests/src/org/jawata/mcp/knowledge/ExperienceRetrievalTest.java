package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 21 Stage 2 — the two-phase, fit-gated, terminal retrieval contract. Runs with a
 * {@code null} JDT service (pointer resolution reports "no project loaded", the algorithm
 * is unaffected).
 */
class ExperienceRetrievalTest {

    private H2ExperienceStore store;
    private ExperienceRetrieval retrieval;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
        retrieval = new ExperienceRetrieval(store, () -> null);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private String putSymbol(String type, String summary, String symbolFqn, String... symptoms) {
        ExperienceEntry.Builder b = ExperienceEntry.of(
            SymbolFact.of(type, summary, Confidence.HIGH).symbol(symbolFqn).build());
        for (String s : symptoms) {
            b.addSymptom(s);
        }
        return store.put(b.build());
    }

    private String putPackage(String type, String summary, String pkg) {
        return store.put(ExperienceEntry.of(
            SymbolFact.of(type, summary, Confidence.MEDIUM).scope(List.of(pkg), List.of()).build())
            .scopeKind("package").build());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> entries(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("entries");
    }

    /**
     * Sprint 28c: an `always`-scoped entry belongs to the ALWAYS-ON layer — the
     * primer pushes it once at session start, and the per-call path leaves it
     * alone. Asserted as BOTH halves in one test, because either alone permits
     * the wrong answer: routing it to the primer while also matching every cue
     * would double it, and dropping it from the cue path without adding it to
     * the primer would lose it entirely.
     */
    @Test
    void an_always_scoped_entry_reaches_the_primer_and_not_the_per_call_answer() {
        String standing = store.put(ExperienceEntry.of(
                SymbolFact.of("lesson", "run the compile gate before calling a change done",
                    Confidence.HIGH).symbol("com.example.orders.RetryLoop").build())
            .status(ExperienceEntry.ACCEPTED)
            .situation("whenever a change is about to be called done")
            .situationScope("always")
            .verdict("worked")
            .form(1)
            .build());
        String conditional = putSymbol("lesson",
            "re-read the queue head before re-arming the retry",
            "com.example.orders.RetryLoop");

        List<Map<String, Object>> hits = entries(retrieval.recall(
            new RecallQuery("com.example.orders.RetryLoop", null, null, null, null)));
        assertTrue(ids(hits).contains(conditional), "the cue's own answer is returned");
        assertFalse(ids(hits).contains(standing),
            "and the standing rule is NOT repeated per call — it would match every cue"
                + " by construction and spend the answer's budget: " + hits);

        assertTrue(ids(entries(retrieval.primer(20, 0)))
                .contains(standing),
            "the primer is where it belongs, pushed once for the session");
    }

    /**
     * The absence guard on that rule: when the standing entry is the ONLY thing
     * that fits, it is returned. Answering "nothing" while holding knowledge
     * would be manufacturing an absence — the failure this whole file exists to
     * prevent.
     */
    @Test
    void an_always_scoped_entry_is_still_answered_when_it_is_all_there_is() {
        String only = store.put(ExperienceEntry.of(
                SymbolFact.of("lesson", "run the compile gate before calling a change done",
                    Confidence.HIGH).symbol("com.example.orders.RetryLoop").build())
            .status(ExperienceEntry.ACCEPTED)
            .situation("whenever a change is about to be called done")
            .situationScope("always")
            .verdict("worked")
            .form(1)
            .build());

        List<Map<String, Object>> hits = entries(retrieval.recall(
            new RecallQuery("com.example.orders.RetryLoop", null, null, null, null)));

        assertTrue(ids(hits).contains(only),
            "held knowledge is never withheld to keep a routing rule tidy: " + hits);
    }

    /**
     * Sprint 28c: a form-1 entry's line states WHEN it applies and HOW it turned
     * out — on the line the deployed hooks already pass through, not a new
     * surface. Without the situation a reader can only judge the entry by
     * resemblance, which is the failure this sprint is about.
     */
    @Test
    void a_form_one_line_carries_its_situation_and_its_outcome() {
        store.put(ExperienceEntry.of(
                SymbolFact.of("lesson", "re-read the queue head before re-arming the retry",
                    Confidence.HIGH).symbol("com.example.orders.RetryLoop").build())
            .situation("when a consumer reconnects mid-batch")
            .verdict("failed_avoid")
            .form(1)
            .build());

        String text = ExperienceRetrieval.renderText(retrieval.recall(
            new RecallQuery("com.example.orders.RetryLoop", null, null, null, null)));

        assertTrue(text.contains("when a consumer reconnects mid-batch"),
            "the condition is on the line: " + text);
        assertTrue(text.contains("failed_avoid"),
            "and so is the outcome — a practice that worked and one that cost a day"
                + " must not read alike: " + text);
        assertFalse(text.contains("when when"),
            "and the line's own 'when' does not double the author's: " + text);
    }

    /**
     * A legacy entry's line is UNCHANGED. The form is additive: the hooks parse
     * these lines, and a marker appearing on rows that carry no form would make
     * every pre-28c entry look classified.
     */
    @Test
    void a_legacy_line_gains_nothing() {
        putSymbol("lesson", "the retry loop re-reads the queue head",
            "com.example.orders.RetryLoop");

        String text = ExperienceRetrieval.renderText(retrieval.recall(
            new RecallQuery("com.example.orders.RetryLoop", null, null, null, null)));

        assertFalse(text.contains(" · when "),
            "no situation marker on a row that has no situation: " + text);
        assertFalse(text.contains("evidence gone"),
            "and no evidence note on a row nobody has judged: " + text);
    }

    /**
     * THE LINE CONTRACT: one entry is one line. A stored situation containing a
     * newline would otherwise split an entry in two and hand the second half to
     * the reader as though it were an entry of its own — which is how a
     * sanitized carrier stops being sanitized.
     */
    @Test
    void a_multiline_situation_cannot_split_the_line() {
        store.put(ExperienceEntry.of(
                SymbolFact.of("lesson", "re-read the queue head before re-arming the retry",
                    Confidence.HIGH).symbol("com.example.orders.RetryLoop").build())
            .situation("when a consumer reconnects\nmid-batch")
            .verdict("worked")
            .form(1)
            .build());

        String text = ExperienceRetrieval.renderText(retrieval.recall(
            new RecallQuery("com.example.orders.RetryLoop", null, null, null, null)));

        assertEquals(1, text.split("\n").length,
            "one entry, one line, whatever the stored text contains: " + text);
    }

    /**
     * Sprint 28c, from the v3.13.0 dogfood: a recall anchored to a one-hour-old
     * symbol came back led by two package-anchored rows about a sprint that had
     * closed weeks earlier. The wider the anchor, the more often its entry
     * surfaces — so the broadest rows in a store are also its loudest.
     *
     * <p>Pinned rather than changed: the ordering contract already leads with
     * specificity, so a symbol-exact entry outranks a package one today. The
     * finding asked for the rule to be held, and an unpinned rule is one
     * refactor away from being an accident. Reverse the specificity comparator
     * and this goes red.</p>
     *
     * <p>Note what is NOT claimed: the package row is still RETURNED. It is
     * legitimately anchored to a package the symbol lives in, and dropping it
     * would be inventing an absence. The defect the dogfood found is that such
     * rows go stale and crowd — which is content, not order, and is Stage 9's
     * disposition work.</p>
     */
    @Test
    void a_package_anchored_entry_never_displaces_a_symbol_exact_one() {
        String broad = putPackage("domain_fact",
            "the ordering service keeps its own clock", "com.example.orders");
        String exact = putSymbol("lesson",
            "re-read the queue head before re-arming the retry",
            "com.example.orders.RetryLoop");

        List<Map<String, Object>> hits = entries(retrieval.recall(
            new RecallQuery("com.example.orders.RetryLoop", null, null, null, null)));

        assertFalse(hits.isEmpty(), "the anchored cue finds something");
        assertEquals(exact, hits.get(0).get("id"),
            "the symbol-exact entry leads; a package row must not take the top slot"
                + " just for being anchored more widely");
        assertTrue(ids(hits).contains(broad),
            "and the package row is still RETURNED — it is genuinely anchored to a"
                + " package this symbol lives in, and dropping it would invent an absence");
    }

    /** Sprint 21c: a file-backed family member (parent or section) sharing a source_ref. */
    private String putFamily(String sourceRef, String summary, boolean section, String... symptoms) {
        ExperienceEntry.Builder b = ExperienceEntry.of(
            SymbolFact.of("lesson", summary, Confidence.MEDIUM).build());
        if (section) {
            b.scopeKind("section");
        }
        for (String s : symptoms) {
            b.addSymptom(s);
        }
        return store.putWithSource(b.build(), sourceRef, "hash");
    }

    private static java.util.Set<Object> ids(List<Map<String, Object>> entries) {
        java.util.Set<Object> ids = new java.util.HashSet<>();
        for (Map<String, Object> e : entries) {
            ids.add(e.get("id"));
        }
        return ids;
    }

    @Test
    void parent_is_dropped_when_its_own_section_fits() {
        // Sprint 21c (item B): the section IS the fact — recall answers with it, not
        // the file bundle; injection pays only the fact's tokens.
        String parent = putFamily("memory:/m/webkit.md", "webkit notes bundle", false,
            "tauri webview renders blank on aarch64");
        String section = putFamily("memory:/m/webkit.md", "Tauri blank webview fix", true,
            "tauri webview renders blank on aarch64");

        Map<String, Object> r = retrieval.recall(new RecallQuery(null, null, null, "blank webview", null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"));
        assertTrue(ids(entries(r)).contains(section), "the fact answers");
        assertFalse(ids(entries(r)).contains(parent), "the bundle is dropped from the fit set");
    }

    @Test
    void parent_still_answers_when_only_it_fits() {
        String parent = putFamily("memory:/m/f.md", "frontmatter cue lives here", false,
            "orphan preamble cue");
        putFamily("memory:/m/f.md", "Some unrelated section", true, "different topic entirely");

        Map<String, Object> r = retrieval.recall(new RecallQuery(null, null, null, "orphan preamble cue", null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"));
        assertTrue(ids(entries(r)).contains(parent), "no fitting section → the parent answers");
    }

    @Test
    void sibling_sections_stay_an_ordered_fit_set() {
        // The hook's ambiguity signal (Sprint 21c item D): 2+ fitting siblings are
        // returned ordered — single-fact-or-nothing is enforced at the hook boundary.
        String s1 = putFamily("memory:/m/a.md", "Lock retry on open", true, "lock file recently modified");
        String s2 = putFamily("memory:/m/b.md", "Lock race at swap", true, "lock file recently modified");

        Map<String, Object> r = retrieval.recall(new RecallQuery(null, null, null, "lock file recently modified", null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"));
        assertEquals(2, entries(r).size(), "both siblings visible — ambiguity stays honest");
        assertTrue(ids(entries(r)).containsAll(java.util.Set.of(s1, s2)));
    }

    @Test
    void recorded_entries_are_never_family_dropped() {
        ExperienceEntry.Builder rb = ExperienceEntry.of(
            SymbolFact.of("lesson", "recorded lesson", Confidence.MEDIUM).build());
        rb.addSymptom("shared cue words");
        String recorded = store.put(rb.build());
        String section = putFamily("memory:/m/x.md", "Sectioned fact", true, "shared cue words");

        Map<String, Object> r = retrieval.recall(new RecallQuery(null, null, null, "shared cue words", null));
        assertEquals(2, entries(r).size(), "a recorded entry has no family — never dropped");
        assertTrue(ids(entries(r)).containsAll(java.util.Set.of(recorded, section)));
    }

    @Test
    void empty_cue_is_absence() {
        Map<String, Object> r = retrieval.recall(new RecallQuery(null, null, null, null, null));
        assertEquals(ExperienceRetrieval.RESULT_ABSENCE, r.get("result"));
        assertEquals("no cue provided", r.get("reason"));
    }

    @Test
    void fuzzy_symptom_alias_hits_not_exact() {
        putSymbol("failure_mode", "OSGi resolve NPE running Maven tests", "com.example.alpol.Gateway",
            "OSGi NPE", "null service at startup");
        // Cue is a paraphrase with different case/spacing — alias normalization bridges it.
        Map<String, Object> r = retrieval.recall(new RecallQuery(null, null, null, "  osgi   NPE ", null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"));
        assertEquals(1, entries(r).size());
        assertEquals("failure_mode", entries(r).get(0).get("type"));
    }

    @Test
    void symptom_tokens_match_summary_non_adjacently() {
        // v2.2.3 dogfood find: md-loaded entries carry no symptom rows, and the old
        // contiguous-substring match missed summaries where the cue words are not
        // adjacent — "blank webview" found nothing despite this entry.
        putSymbol("reference",
            "WebKitGTK DMABUF compositor fails silently; window chrome renders, "
                + "webview content area stays blank (GTK background colour)",
            null);
        Map<String, Object> r = retrieval.recall(new RecallQuery(null, null, null, "blank webview", null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"));
        assertEquals(1, entries(r).size());
    }

    @Test
    void symptom_requires_all_tokens_not_any() {
        putSymbol("reference", "the webview initializes fine on wayland", null);
        // Only one of the two cue tokens appears — a loose ANY-match would return this;
        // the fit gate must not.
        // v3.4.1: asked of the KEYWORD gate, which is what "all tokens, not any"
        // is a rule of. Under the full union the entry comes back as a labeled
        // analogy ("in a similar situation..."), because a blank webview and a
        // webview that initializes fine ARE related — the gate still refuses it
        // as an answer, which is the property this test protects.
        ExperienceRetrieval keyword = ExperienceRetrieval.keywordOnly(store, () -> null);
        Map<String, Object> r = keyword.recall(new RecallQuery(null, null, null, "blank webview", null));
        assertEquals(ExperienceRetrieval.RESULT_ABSENCE, r.get("result"));
    }

    @Test
    void scope_mismatch_returns_absence() {
        putSymbol("lesson", "guard the workbench lifecycle", "com.a.Foo");
        // A symbol in a different tree does not fit — terminal absence, not a loose match.
        Map<String, Object> r = retrieval.recall(new RecallQuery("com.b.Bar", null, null, null, null));
        assertEquals(ExperienceRetrieval.RESULT_ABSENCE, r.get("result"));
        assertTrue(entries(r).isEmpty());
    }

    @Test
    void package_scoped_entry_fits_a_symbol_inside_it() {
        putPackage("domain_fact", "billing DTOs keep no-arg constructors", "com.example.billing");
        Map<String, Object> r = retrieval.recall(
            new RecallQuery("com.example.billing.InvoiceDto", null, null, null, null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"));
        assertEquals(1, entries(r).size());
    }

    @Test
    void disambiguation_prefers_more_specific_scope() {
        putPackage("domain_fact", "package-level note", "com.example.billing");
        putSymbol("lesson", "symbol-level note", "com.example.billing.InvoiceDto");
        Map<String, Object> r = retrieval.recall(
            new RecallQuery("com.example.billing.InvoiceDto", null, null, null, null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"));
        // Both fit; the symbol-scoped node is more specific → it is the head.
        assertEquals("symbol-level note", entries(r).get(0).get("summary"));
    }

    @Test
    void rejected_entries_are_excluded() {
        String id = putSymbol("lesson", "obsolete note", "com.a.Foo");
        store.setStatus(id, ExperienceEntry.REJECTED);
        // Deliberately the FULL production configuration, not keyword-only.
        // v3.4.0 filtered rejected and superseded entries out of the keyword
        // query and nowhere else; the moment meaning nomination was actually
        // wired in (v3.4.1), refused knowledge started coming back as "in a
        // similar situation...". A rejected entry is one a human looked at and
        // said no to. It may not return by ANY path.
        Map<String, Object> r = retrieval.recall(new RecallQuery("com.a.Foo", null, null, null, null));
        assertEquals(ExperienceRetrieval.RESULT_ABSENCE, r.get("result"),
            "a rejected entry must not resurface as an analogy either");
    }

    @Test
    void operation_cue_fits_when_entry_is_operation_scoped() {
        store.put(ExperienceEntry.of(
            SymbolFact.of("failure_mode", "run_tests OSGi NPE on plain Maven", Confidence.HIGH)
                .symbol("com.example.alpol.Gateway").build())
            .operation("run_tests").build());
        Map<String, Object> hit = retrieval.recall(new RecallQuery(null, null, "run_tests", null, null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, hit.get("result"));
        assertFalse(entries(hit).isEmpty());
        // A different operation does not fit → absence.
        Map<String, Object> miss = retrieval.recall(new RecallQuery(null, null, "rename_symbol", null, null));
        assertEquals(ExperienceRetrieval.RESULT_ABSENCE, miss.get("result"));
    }

    @Test
    void the_marker_an_agent_just_wrote_survives_the_cap() throws Exception {
        // mcp#34 — the store's most basic self-check is record-then-recall, and
        // it failed whenever a cue's fit set exceeded MAX_TERMINAL: Grok on
        // Windows recorded a marker, recalled its own operation cue, and got
        // count=5 capped_from=9 — five OLDER entries, without the one it had
        // just written seconds earlier.
        //
        // The cap itself is right (a cue must not return a pile). The ordering
        // was not: the fit set is sorted specificity › affinity › confidence ›
        // MEANING › recency, and meaning is a continuous cosine score, so it
        // almost never ties — which makes recency unreachable whenever the
        // embedder is on, for every cue kind.
        for (int i = 0; i < 8; i++) {
            store.put(ExperienceEntry.of(
                SymbolFact.of("lesson", "older dogfood note number " + i, Confidence.HIGH)
                    .build())
                .operation("dogfood-probe").build());
            Thread.sleep(2);   // distinct createdAt, so "newest" is well defined
        }
        String justWritten = store.put(ExperienceEntry.of(
            SymbolFact.of("lesson", "the marker this agent wrote last", Confidence.HIGH).build())
            .operation("dogfood-probe").build());

        Map<String, Object> r = retrieval.recall(
            new RecallQuery(null, null, "dogfood-probe", null, null));

        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"));
        assertEquals(5, entries(r).size(), "the cap still holds");
        assertEquals(9, r.get("capped_from"), "and it still declares what it capped");
        assertTrue(ids(entries(r)).contains(justWritten),
            "the newest fitting entry must be served — a record-then-recall round trip that "
                + "cannot see its own write makes the store untrustworthy for exactly the "
                + "purpose an agent uses it for: " + entries(r));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pointer_resolution_flags_no_project_when_jdt_absent() {
        putSymbol("lesson", "guard lifecycle", "com.example.WorkflowCoordinator");
        Map<String, Object> r = retrieval.recall(
            new RecallQuery("com.example.WorkflowCoordinator", null, null, null, null));
        Map<String, Object> pointer = (Map<String, Object>) entries(r).get(0).get("resolved_pointer");
        assertEquals(false, pointer.get("resolved"));
        assertEquals("no project loaded", pointer.get("note"));
    }
}
