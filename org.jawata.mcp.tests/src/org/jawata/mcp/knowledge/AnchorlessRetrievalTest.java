package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Sprint 28c — THE GATE. Retrieval with no code anchor, measured on the fixture
 * that was frozen before any of the code that must satisfy it.
 *
 * <p>Every record here carries a situation and NO symbol, package, operation or
 * snippet. Every question is prose a person would actually type, and shares
 * wording with the record's SITUATION while sharing none with its PRINCIPLE — so
 * an engine that indexes only the principle cannot pass by luck, and one that
 * matches on a code address cannot pass at all.</p>
 *
 * <p><b>The same fixture scored 0 of 5 against the abandoned build.</b> That is
 * what makes this a measurement rather than a demonstration: the questions were
 * written to be unanswerable by the design being replaced, and committed before
 * the replacement existed.</p>
 */
class AnchorlessRetrievalTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode fixture() throws Exception {
        Path p = AcceptanceFixtureTest.fixture("anchorless-retrieval.json");
        return JSON.readTree(Files.readString(p));
    }

    /**
     * Seed the frozen records through the production write path — the form gate,
     * the embedding recipe and the id assignment all included, because a fixture
     * loaded around the write path proves nothing about the write path.
     *
     * @return fixture id -&gt; the id the STORE assigned. The store mints its own,
     *     so the frozen ids are stable NAMES for the expectations rather than
     *     database keys; mapping them here keeps the fixture readable without
     *     pretending the store accepts foreign ids.
     */
    private static Map<String, String> seed(H2ExperienceStore store, JsonNode fx) {
        Map<String, String> assigned = new java.util.LinkedHashMap<>();
        for (JsonNode r : fx.get("records")) {
            String id = store.put(ExperienceEntry.of(
                    SymbolFact.of(r.get("type").asText(),
                        r.get("summary").asText(), Confidence.HIGH).build())
                .situation(r.get("situation").asText())
                .verdict(r.get("verdict").asText())
                .form(1)
                .build());
            assigned.put(r.get("id").asText(), id);
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
     * retrieval deleted. A test whose assertion is true by arithmetic is the
     * shape this sprint keeps finding, and it nearly shipped inside the sprint's
     * own headline gate.</p>
     *
     * <p>They are built from the fixture's own UNRELATED questions rather than
     * invented here: the fixture froze those as things the corpus must not answer,
     * so turning each into an entry produces distractors nobody chose to make
     * easy.</p>
     */
    private static void seedDistractors(H2ExperienceStore store, JsonNode fx) {
        int n = 0;
        for (JsonNode q : fx.get("unrelated_questions")) {
            n++;
            store.put(ExperienceEntry.of(
                    SymbolFact.of("lesson",
                        "Measure it before changing it, and write the number down (" + n + ").",
                        Confidence.MEDIUM).build())
                .situation("when " + q.asText())
                .verdict("worked")
                .form(1)
                .build());
        }
    }

    /**
     * THE POSITIVE HALF. Each question must nominate its own record, and the
     * record becomes an ANSWER only after a decision selects it.
     *
     * <p>The assertion is on the candidate set, not on rank 1. Ranking is an
     * ordering and the design says so; demanding the top slot would be asserting
     * a claim the engine is not allowed to make. What must hold is that the
     * right entry is IN FRONT OF the caller to judge — which is exactly what
     * failed before, where it was reachable by no wording at all.</p>
     */
    @Test
    void every_frozen_question_nominates_its_record_and_answers_only_after_a_decision()
            throws Exception {
        JsonNode fx = fixture();
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            Map<String, String> assigned = seed(store, fx);
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

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> candidates =
                    (List<Map<String, Object>>) nomination.get("candidates");
                List<String> ids = new ArrayList<>();
                for (Map<String, Object> c : candidates) {
                    ids.add(String.valueOf(c.get("id")));
                }
                // TOP THREE, fixed before the first run and not moved after it. A
                // caller reads a shortlist and judges; three is a shortlist. Rank 1
                // would be asserting a claim the design forbids the engine from
                // making, and "anywhere in the list" is not a measurement when the
                // list can hold most of the corpus.
                int rank = ids.indexOf(expected);
                if (rank < 0 || rank >= 3) {
                    missed.add(question + " -> expected " + expected
                        + (rank < 0 ? " NOT NOMINATED" : " at rank " + (rank + 1))
                        + ", candidates " + ids);
                    continue;
                }

                // The record is in front of the caller. It becomes an ANSWER only
                // when the caller says it applies — which is the whole design.
                String queryId = applicability.nominate(question, ids);
                Object decided = applicability.decide(queryId, List.of(expected));
                var decision = assertInstanceOf(ApplicabilityDecision.Decision.class, decided);
                Map<String, Object> answer = retrieval.answerFor(decision);

                assertEquals(ExperienceRetrieval.RESULT_MATCH, answer.get("result"), question);
                assertEquals(1, answer.get("count"), question);
                answered++;
            }

            assertTrue(missed.isEmpty(),
                "THE GATE: " + answered + " of " + fx.get("positive_questions").size()
                    + " frozen questions reached their record in the top 3, with no code "
                    + "anchor anywhere. MEASURED CONTROL: drop `situation` from "
                    + "EmbeddingService.documentOf and this falls to 3 of 5, so the field "
                    + "is doing real work rather than riding along. Missed:\n  "
                    + String.join("\n  ", missed));
        }
    }

    /**
     * THE NEGATIVE HALF, and the half the store could not do at all. Seven
     * unrelated questions may nominate whatever the ranking produces — that is
     * allowed and unavoidable, because no threshold separates nonsense from an
     * answer on a real corpus. What must hold is that choosing none of them
     * yields an ABSENCE with no entries and no consolation pile.
     */
    @Test
    void every_unrelated_question_ends_in_an_absence_when_nothing_is_selected()
            throws Exception {
        JsonNode fx = fixture();
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            seed(store, fx);
            ExperienceRetrieval retrieval = new ExperienceRetrieval(store, () -> null);
            ApplicabilityDecision applicability = new ApplicabilityDecision();

            for (JsonNode q : fx.get("unrelated_questions")) {
                String question = q.asText();
                Map<String, Object> nomination = retrieval.nominate(
                    question, ExperienceRetrieval.RETRIEVAL_BUDGET_MILLIS);

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> candidates =
                    (List<Map<String, Object>>) nomination.get("candidates");
                List<String> ids = new ArrayList<>();
                for (Map<String, Object> c : candidates) {
                    ids.add(String.valueOf(c.get("id")));
                }

                String queryId = applicability.nominate(question, ids);
                var decision = assertInstanceOf(ApplicabilityDecision.Decision.class,
                    applicability.decide(queryId, List.of()));
                Map<String, Object> answer = retrieval.answerFor(decision);

                assertEquals(ExperienceRetrieval.RESULT_ABSENCE, answer.get("result"),
                    "an unrelated question must end in an absence: " + question);
                assertEquals(0, answer.get("count"), question);
                assertEquals(List.of(), answer.get("entries"),
                    "and carry NO entries — the measured defect was eleven suggestions "
                        + "for each of seven questions like this one: " + question);
            }
        }
    }

    // The property these gates rest on — that no positive question shares wording
    // with the principle it must reach, so it cannot be answered from the summary —
    // is asserted by AcceptanceFixtureTest#every_positive_question_is_answerable_only
    // _from_the_situation. It is NOT restated here: a second copy of a check is a
    // second thing to keep in step, and this file would be the copy that drifts.
}
