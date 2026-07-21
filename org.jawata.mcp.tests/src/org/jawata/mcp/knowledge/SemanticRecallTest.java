package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Sprint 27 C4 — the retrieval ontology, enforced.
 *
 * <p>What is under test is not "semantic recall returns things" but the two
 * rules that make it safe to put in an agent's context: <b>facts stay hard-gated
 * and terminal</b>, and <b>experience comes back as capped, labeled analogy that
 * never reads as fact</b>.</p>
 */
class SemanticRecallTest {

    private H2ExperienceStore store;
    private EmbeddingService svc;
    private ExperienceRetrieval semantic;
    private ExperienceRetrieval keywordOnly;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        svc = EmbeddingService.shared();
        semantic = new ExperienceRetrieval(store, () -> null, new EmbeddingIndex(store, svc));
        keywordOnly = new ExperienceRetrieval(store, () -> null);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private String putExperience(String summary, String details, String anchor) {
        SymbolFact.Builder f = SymbolFact.of("lesson", summary, Confidence.MEDIUM).details(details);
        if (anchor != null) {
            f.symbol(anchor);
        }
        return store.put(ExperienceEntry.of(f.build()).build());
    }

    private String putFact(String summary, String anchor) {
        return store.put(ExperienceEntry.of(
            SymbolFact.of("api_contract", summary, Confidence.HIGH).symbol(anchor).build()).build());
    }

    private static RecallQuery symptom(String s) {
        return new RecallQuery(null, null, null, s, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> analogies(Map<String, Object> r) {
        return (List<Map<String, Object>>) r.getOrDefault("analogies", List.of());
    }

    @Test
    void a_paraphrase_reaches_experience_that_keyword_recall_cannot_find() {
        if (!svc.available()) {
            return;
        }
        String target = putExperience(
            "never remove the algo from the map until the broker confirms",
            "premature removal routes a late cancel callback to the wrong algo on a reused slot",
            null);
        putExperience("the opening range is the first fifteen minutes", "session structure", null);

        RecallQuery cue = symptom(
            "we deleted the order record too early and a late confirmation went to the wrong place");

        // The whole premise of the sprint, stated as a comparison.
        Map<String, Object> before = keywordOnly.recall(cue);
        assertEquals(ExperienceRetrieval.RESULT_ABSENCE, before.get("result"),
            "keyword recall genuinely cannot reach this - that is the Recall Gap");

        Map<String, Object> after = semantic.recall(cue);
        assertEquals(ExperienceRetrieval.RESULT_ANALOGY, after.get("result"),
            "and semantic recall answers it - as analogy, not as fact");
        List<Map<String, Object>> got = analogies(after);
        assertFalse(got.isEmpty());
        assertEquals(target, got.get(0).get("id"), "the right entry, ranked first");
    }

    @Test
    void an_analogy_is_never_dressed_as_a_fact() {
        if (!svc.available()) {
            return;
        }
        putExperience("never free the slot before the broker confirms the cancel",
            "a late confirmation can reach a reused slot", null);
        Map<String, Object> r = semantic.recall(symptom(
            "we released the resource before the exchange acknowledged it"));
        List<Map<String, Object>> got = analogies(r);
        assertFalse(got.isEmpty(), "precondition: an analogy surfaced");

        Map<String, Object> a = got.get(0);
        assertEquals("analogy — judge whether it transfers", a.get("framing"),
            "the framing must say what this is");
        assertNotNull(a.get("basis"), "and WHY it surfaced, in words");
        assertTrue(a.get("basis") instanceof List<?> l && !l.isEmpty(),
            "the basis must be a non-empty list of reasons, not a bare flag");
        assertFalse(a.containsKey("score"),
            "the structured form must not carry a similarity number either");

        // The result must never be presented as a gated match.
        assertEquals(ExperienceRetrieval.RESULT_ANALOGY, r.get("result"));
        assertTrue(((List<?>) r.get("entries")).isEmpty(),
            "an analogy never lands in the gated-fact list");
    }

    @Test
    void no_rendering_anywhere_contains_a_similarity_number() {
        if (!svc.available()) {
            return;
        }
        putExperience("the webview renders blank on linux",
            "the DMABUF compositor path fails silently on some drivers", null);
        // Cue chosen from a MEASURED score, not from intuition: against this
        // entry "the window comes up empty" scores 0.1177 — genuinely below the
        // derived 0.15 floor, so it is correctly nominated by nothing. (A useful
        // demonstration that the floor is not vacuous.) This cue scores 0.2653.
        Map<String, Object> r = semantic.recall(
            symptom("the app starts but nothing paints inside the frame"));
        // The precondition asserts the RESULT, not merely that some text came
        // back — an absence message is also non-empty text, which would let
        // this test pass while proving nothing.
        assertEquals(ExperienceRetrieval.RESULT_ANALOGY, r.get("result"),
            "precondition: an analogy must actually have surfaced");
        String text = ExperienceRetrieval.renderText(r);
        assertFalse(text.isEmpty());

        // A score in the text invites treating similarity as authority. The
        // basis is words; the ranking stays inside the machine.
        assertFalse(text.matches("(?s).*0\\.\\d{2,}.*"),
            "no cosine-looking number may appear in a rendering: " + text);
        assertFalse(text.toLowerCase().contains("score"),
            "nor the word 'score': " + text);
        assertTrue(text.contains("In a similar situation:"),
            "and the advisory framing must survive rendering: " + text);
    }

    @Test
    void experience_whose_code_is_gone_still_returns_with_its_provenance() {
        if (!svc.available()) {
            return;
        }
        // The ruling that forced the ontology: an old refactoring lesson is
        // still good experience even when the class it was learned on is gone.
        putExperience("extract was reverted twice on a long method",
            "the extraction changed behaviour because a field was captured",
            "com.gone.DeletedClass#oldMethod");

        Map<String, Object> r = semantic.recall(symptom(
            "pulling a block out of a big routine broke it"));
        List<Map<String, Object>> got = analogies(r);
        assertFalse(got.isEmpty(),
            "a dead anchor must NOT remove experience - that would delete every"
            + " lesson learned on code that has since changed");
        assertNotNull(got.get(0).get("provenance"), "and its origin is rendered as context");
        assertTrue(String.valueOf(got.get(0).get("provenance")).startsWith("learned on"));
    }

    @Test
    void a_fact_that_fails_its_address_gate_is_not_smuggled_back_as_an_analogy() {
        if (!svc.available()) {
            return;
        }
        // A FACT about code at an address. The cue does not contain that
        // address, so the gate must not admit it - and the meaning nominator
        // must not offer it either, or the hard gate becomes decorative.
        putFact("callers must never pass null to this API",
            "com.example.SomeService#doWork");

        Map<String, Object> r = semantic.recall(symptom(
            "callers must never pass null to this API"));
        for (Map<String, Object> a : analogies(r)) {
            assertFalse("api_contract".equals(a.get("type")),
                "an address-bound fact must never appear as an analogy: " + a);
        }
    }

    @Test
    void analogies_are_capped_so_the_context_cannot_be_flooded() {
        if (!svc.available()) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            putExperience("broker confirmation lesson number " + i,
                "never act before the broker confirms the cancel, case " + i, null);
        }
        Map<String, Object> r = semantic.recall(symptom(
            "we acted before the exchange acknowledged the cancellation"));
        assertTrue(analogies(r).size() <= ExperienceAnalogies.DEFAULT_CAP,
            "at most " + ExperienceAnalogies.DEFAULT_CAP + " analogies may reach the agent");
    }

    @Test
    void equality_boosts_the_ranking_but_never_decides_admission() {
        if (!svc.available()) {
            return;
        }
        String sameOp = store.put(ExperienceEntry.of(
            SymbolFact.of("lesson", "renaming across modules needs a full rebuild",
                Confidence.MEDIUM).details("stale binaries hid the breakage").build())
            .operation("rename_symbol").build());
        putExperience("renaming across modules needs a full rebuild too",
            "stale binaries hid the breakage as well", null);

        List<StoredEntry> both = store.all();
        List<ExperienceAnalogies.Analogy> ranked = ExperienceAnalogies.rank(
            both, new RecallQuery(null, null, "rename_symbol", null, null),
            Map.of(), 2, () -> null);

        assertEquals(sameOp, ranked.get(0).entry().id(),
            "the same-operation entry ranks first...");
        assertEquals(2, ranked.size(),
            "...but the other is still ADMITTED - equality boosts, it never gates");
        assertTrue(ranked.get(0).basis().contains("same operation"),
            "and the boost is named in words");
    }

    @Test
    void with_no_index_recall_behaves_exactly_as_it_did_before_this_sprint() {
        putExperience("database file already locked at startup",
            "the lock survived a crash", null);

        Map<String, Object> r = keywordOnly.recall(symptom("database file already locked"));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"));
        assertFalse(r.containsKey("analogies"),
            "no index means no analogies key at all - the pre-27 answer shape, unchanged");

        Map<String, Object> miss = keywordOnly.recall(symptom("something entirely unrelated"));
        assertEquals(ExperienceRetrieval.RESULT_ABSENCE, miss.get("result"),
            "and an honest absence stays an absence");
    }

    @Test
    void the_cue_text_is_the_words_of_the_cue() {
        assertEquals("blank window rename_symbol com.foo.Bar",
            ExperienceRetrieval.cueText(
                new RecallQuery("com.foo.Bar", null, "rename_symbol", "blank window", null)));
        assertEquals("", ExperienceRetrieval.cueText(null));
        assertEquals("", ExperienceRetrieval.cueText(
            new RecallQuery(null, null, null, null, null)));
    }
}
