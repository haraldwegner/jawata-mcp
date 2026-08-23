package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Sprint 28c — THE GATE. Retrieval with no code anchor, measured on a fixture
 * frozen before any of the code that must satisfy it.
 *
 * <p>Every record carries a situation and NO symbol, package, operation or
 * snippet. Every question is prose a person would type, sharing wording with the
 * record's SITUATION and none with its PRINCIPLE — so an engine that indexes only
 * the principle cannot pass by luck, and one that matches a code address cannot
 * pass at all.</p>
 */
class AnchorlessRetrievalTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The bar, fixed before the first run and not moved after it. */
    private static final int TOP_N = 3;

    private static JsonNode fixture() throws Exception {
        Path p = AcceptanceFixtureTest.fixture("anchorless-retrieval.json");
        return JSON.readTree(Files.readString(p));
    }

    /**
     * Seed through the production write path — form gate, embedding recipe and id
     * assignment included, because a fixture loaded AROUND the write path proves
     * nothing about it.
     *
     * @param anchor when true, give each record a code anchor it never needed, so
     *     a later refresh can take that code away
     * @return fixture id -&gt; the id the store assigned (the store mints its own,
     *     so the frozen ids are stable NAMES for expectations, not keys)
     */
    private static Map<String, String> seed(H2ExperienceStore store, JsonNode fx, boolean anchor) {
        Map<String, String> assigned = new LinkedHashMap<>();
        int n = 0;
        for (JsonNode r : fx.get("records")) {
            n++;
            SymbolFact.Builder fact = SymbolFact.of(
                r.get("type").asText(), r.get("summary").asText(), Confidence.HIGH);
            if (anchor) {
                fact.symbol("com.gone.Deleted" + n);
            }
            assigned.put(r.get("id").asText(), store.put(ExperienceEntry.of(fact.build())
                .situation(r.get("situation").asText())
                .verdict(r.get("verdict").asText())
                .form(1)
                .build()));
        }
        seedDistractors(store, fx);
        return assigned;
    }

    /**
     * Entries that must NOT be returned, so the ranking has something to be wrong
     * about.
     *
     * <p>Without these the gate cannot fail: five records and a candidate list of
     * {@link ExperienceRetrieval#MAX_CANDIDATES} means every record is always a
     * candidate, so "the expected id is among the candidates" would hold with
     * retrieval deleted. That is the shape this sprint keeps finding, and it
     * nearly shipped inside the sprint's own headline gate.</p>
     *
     * <p>They are built from the fixture's own UNRELATED questions rather than
     * invented here — the fixture froze those as things the corpus must not
     * answer, so nobody chose to make them easy.</p>
     */
    private static void seedDistractors(H2ExperienceStore store, JsonNode fx) {
        int n = 0;
        for (JsonNode q : fx.get("unrelated_questions")) {
            n++;
            store.put(ExperienceEntry.of(
                    SymbolFact.of("lesson",
                        "Measure it before changing it, and write the number down (" + n + ").",
                        Confidence.MEDIUM)
                    // Anchored to code that STILL EXISTS, and that is load-bearing for
                    // the anchor-loss gate rather than decoration. Maintenance holds
                    // every status change when NOT ONE anchor resolves, because a
                    // workspace that answers "gone" to everything is far more likely
                    // unloaded than emptied. A test where all five anchors vanish
                    // therefore measures the breaker, not the deletion. A real
                    // deletion removes some code and leaves the rest.
                    .symbol("com.present.Alive" + n)
                    .build())
                .situation("when " + q.asText())
                .verdict("worked")
                .form(1)
                .build());
        }
    }

    /** The code the five records point at is gone; everything else still resolves. */
    private static final ExperienceMaintenance.PointerResolver DELETED_THE_FIVE =
        fqn -> fqn != null && fqn.startsWith("com.gone") ? Boolean.FALSE : Boolean.TRUE;

    /** The ranked candidate ids for a question. */
    private static List<String> nominatedIds(ExperienceRetrieval retrieval, String question) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates =
            (List<Map<String, Object>>) retrieval.nominate(
                question, ExperienceRetrieval.RETRIEVAL_BUDGET_MILLIS).get("candidates");
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> c : candidates) {
            ids.add(String.valueOf(c.get("id")));
        }
        return ids;
    }

    /**
     * THE POSITIVE HALF. Each question must nominate its own record, and the
     * record becomes an ANSWER only after a decision selects it.
     *
     * <p>The bar is the top {@link #TOP_N}: a caller reads a shortlist and judges,
     * and three is a shortlist. Rank 1 would assert a claim the design forbids the
     * engine from making; "anywhere in the list" is not a measurement when the list
     * can hold most of the corpus.</p>
     */
    @Test
    void every_frozen_question_nominates_its_record_and_answers_only_after_a_decision()
            throws Exception {
        JsonNode fx = fixture();
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            Map<String, String> assigned = seed(store, fx, false);
            ExperienceRetrieval retrieval = new ExperienceRetrieval(store, () -> null);
            ApplicabilityDecision applicability = new ApplicabilityDecision();

            List<String> missed = new ArrayList<>();
            int answered = 0;
            for (JsonNode q : fx.get("positive_questions")) {
                String question = q.get("question").asText();
                String expected = assigned.get(q.get("expect_id").asText());

                Map<String, Object> nomination = retrieval.nominate(
                    question, ExperienceRetrieval.RETRIEVAL_BUDGET_MILLIS);
                assertEquals(ExperienceRetrieval.RESULT_NOMINATED, nomination.get("result"),
                    "nominating is never a match, however good the ranking: " + question);

                List<String> ids = nominatedIds(retrieval, question);
                int rank = ids.indexOf(expected);
                if (rank < 0 || rank >= TOP_N) {
                    missed.add(question + " -> expected " + expected
                        + (rank < 0 ? " NOT NOMINATED" : " at rank " + (rank + 1))
                        + ", candidates " + ids);
                    continue;
                }

                // In front of the caller. It becomes an ANSWER only when the caller
                // says it applies — which is the whole design.
                String queryId = applicability.nominate(question, ids);
                var decision = assertInstanceOf(ApplicabilityDecision.Decision.class,
                    applicability.decide(queryId, List.of(expected)));
                Map<String, Object> answer = retrieval.answerFor(decision);

                assertEquals(ExperienceRetrieval.RESULT_MATCH, answer.get("result"), question);
                assertEquals(1, answer.get("count"), question);
                answered++;
            }

            assertTrue(missed.isEmpty(),
                "THE GATE: " + answered + " of " + fx.get("positive_questions").size()
                    + " frozen questions reached their record in the top " + TOP_N
                    + ", with no code anchor anywhere. MEASURED CONTROL: drop `situation`"
                    + " from EmbeddingService.documentOf and this falls to 3 of 5, so the"
                    + " field is doing real work rather than riding along. Missed:\n  "
                    + String.join("\n  ", missed));
        }
    }

    /**
     * THE NEGATIVE HALF, and the half the store could not do at all. Unrelated
     * questions may nominate whatever the ranking produces — unavoidable, because
     * no threshold separates nonsense from an answer on a real corpus. What must
     * hold is that choosing none yields an ABSENCE with no entries and no
     * consolation pile.
     */
    @Test
    void every_unrelated_question_ends_in_an_absence_when_nothing_is_selected()
            throws Exception {
        JsonNode fx = fixture();
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            seed(store, fx, false);
            ExperienceRetrieval retrieval = new ExperienceRetrieval(store, () -> null);
            ApplicabilityDecision applicability = new ApplicabilityDecision();

            for (JsonNode q : fx.get("unrelated_questions")) {
                String question = q.asText();
                String queryId = applicability.nominate(
                    question, nominatedIds(retrieval, question));
                var decision = assertInstanceOf(ApplicabilityDecision.Decision.class,
                    applicability.decide(queryId, List.of()));
                Map<String, Object> answer = retrieval.answerFor(decision);

                assertEquals(ExperienceRetrieval.RESULT_ABSENCE, answer.get("result"),
                    "an unrelated question must end in an absence: " + question);
                assertEquals(0, answer.get("count"), question);
                assertEquals(List.of(), answer.get("entries"),
                    "and carry NO entries — the measured defect was eleven suggestions for "
                        + "each of seven questions like this one: " + question);
            }
        }
    }

    /**
     * THE SPRINT'S THESIS, as a gate: an experience outlives the code it was
     * learned on. Harald's words are the requirement — "due to a code change the
     * entry might be thrown out, but an experience is still a fucking experience".
     *
     * <p>The records are given a code anchor they never needed, the code is
     * declared gone, maintenance runs, and all five questions are asked again. Two
     * different failures are possible and both must not happen: the entries could
     * be SUPERSEDED by anchor resolution, or they could survive as rows and become
     * UNREACHABLE because retrieval leant on the anchor. The first is asserted on
     * status; the second by re-running the gate — and the second is the one a
     * status check alone cannot see.</p>
     */
    @Test
    void the_five_questions_still_answer_after_the_code_they_were_learned_on_is_gone()
            throws Exception {
        JsonNode fx = fixture();
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            Map<String, String> assigned = seed(store, fx, true);

            Map<String, Object> report =
                new ExperienceMaintenance(store, DELETED_THE_FIVE).refresh();

            for (StoredEntry e : store.all()) {
                if (!assigned.containsValue(e.id())) {
                    continue;
                }
                assertEquals(ExperienceEntry.CANDIDATE, e.status(),
                    "a form-1 entry is NEVER retired because its anchor stopped resolving: "
                        + "the anchor says WHERE it was learned, the situation says WHEN it "
                        + "applies, and only the second decides whether it is still true. "
                        + "Report: " + report);
                assertTrue(e.facets().hasDeadEvidence(),
                    "and the dead pointer is recorded as the fact it is, for a human to curate");
            }

            ExperienceRetrieval retrieval = new ExperienceRetrieval(store, () -> null);
            List<String> missed = new ArrayList<>();
            for (JsonNode q : fx.get("positive_questions")) {
                String question = q.get("question").asText();
                int rank = nominatedIds(retrieval, question)
                    .indexOf(assigned.get(q.get("expect_id").asText()));
                if (rank < 0 || rank >= TOP_N) {
                    missed.add(question + " -> "
                        + (rank < 0 ? "NOT NOMINATED" : "rank " + (rank + 1)));
                }
            }
            assertTrue(missed.isEmpty(),
                "the code is deleted and the experience still answers. Missed:\n  "
                    + String.join("\n  ", missed));
        }
    }

    // The property these gates rest on — that no positive question shares wording
    // with the principle it must reach — is asserted by AcceptanceFixtureTest#
    // every_positive_question_is_answerable_only_from_the_situation. It is NOT
    // restated here: a second copy of a check is a second thing to keep in step,
    // and this file would be the copy that drifts.
}
