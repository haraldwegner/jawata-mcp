package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Sprint 28c D13 — the weighted merge, and the defects it replaces.
 *
 * <p>These are arithmetic tests on a pure function, deliberately. The
 * end-to-end behaviour they underwrite is measured through the front door by
 * {@code build/anchorless-frontdoor-probe.sh}; what belongs HERE is the
 * properties a probe cannot isolate — that BM25's scale can no longer swamp the
 * meaning lanes, that a one-word match can no longer read as a perfect one, that
 * a missing lane costs rank rather than being papered over, and that the ranking
 * cannot see anything except proximity.</p>
 */
class RelevanceMergeTest {

    private static Map<String, Double> map(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        }
        return m;
    }

    private static LexicalIndex.Scores words(double ceiling, Object... kv) {
        return new LexicalIndex.Scores(map(kv), ceiling);
    }

    /**
     * THE FIRST DEFECT, stated as a test.
     *
     * <p>The old ranking added a cosine to a raw BM25 weight. Cosine lives in
     * 0..1; BM25 on a corpus this size routinely reaches double digits — so
     * {@code cosine + bm25} was BM25 with a rounding error attached, and an
     * entry the meaning lanes matched almost perfectly could not outrank one
     * they did not match at all.</p>
     *
     * <p>Here {@code wordy} is given the most favourable word score there is — it
     * carries every word of the question, so its raw score IS the ceiling — and it
     * still loses to an entry whose meaning fits.</p>
     */
    @Test
    void a_raw_bm25_score_can_no_longer_swamp_the_meaning_lanes() {
        Map<String, RelevanceMerge.Score> scores = RelevanceMerge.scoreAll(
            map("fitting", 0.95, "wordy", 0.05),      // situation lane
            map("fitting", 0.80, "wordy", 0.05),      // summary lane
            map("fitting", 0.60, "wordy", 0.05),      // details lane
            words(14.7, "wordy", 14.7, "fitting", 0.0));

        double oldRule = 0.95 + 0.0;                  // what the first sum computed
        double oldRuleWordy = 0.05 + 14.7;
        assertTrue(oldRuleWordy > oldRule,
            "the fixture must actually reproduce the old defect, or this test proves nothing");

        assertTrue(scores.get("fitting").total() > scores.get("wordy").total(),
            "an entry the three meaning lanes match must outrank one that only shares "
                + "words, however large that word score is — the word lane is normalised "
                + "to 0..1 before it is weighed, so its SCALE cannot decide the ranking");
    }

    /**
     * THE SECOND DEFECT, found by the 4.3.0 dogfood and stated as a test.
     *
     * <p>The word lane used to be rescaled by the best score in the result set, so
     * the best word match always read 1.0 and took the full word weight — whatever it
     * had matched. A note sharing ONE rare word of a twelve-word paraphrase then beat
     * the note that answered the question: on the real store, "at what point may I
     * treat pulling back an instruction I gave the exchange as finished" put a lesson
     * about standing instructions first, on the word "instruction", and the
     * cancel-confirmation fact was not in the top eight.</p>
     *
     * <p>The numbers below are that shape: the answer's meaning lanes sit where a real
     * paraphrase lands (0.3 to 0.45, absolute), and {@code oneWord} shares one word
     * whose rarity is an eighth of the question's.</p>
     */
    @Test
    void one_shared_word_no_longer_outranks_the_row_that_answers() {
        Map<String, Double> sit = map("answer", 0.45, "oneWord", 0.20);
        Map<String, Double> sum = map("answer", 0.40, "oneWord", 0.15);
        Map<String, Double> det = map("answer", 0.30, "oneWord", 0.10);
        double oneWordRaw = 2.0;       // one rare word's contribution
        double questionCeiling = 16.0; // every discriminating word of the question

        // The control: the OLD rule reproduces the defect on exactly these numbers.
        // oneWord is the only row with any shared word, so the best-in-set divisor is its
        // own score and it normalises to 1.0.
        double oldOneWord = RelevanceMerge.W_SITUATION * 0.20 + RelevanceMerge.W_SUMMARY * 0.15
            + RelevanceMerge.W_DETAILS * 0.10 + RelevanceMerge.W_WORDS * (oneWordRaw / oneWordRaw);
        double answer = RelevanceMerge.W_SITUATION * 0.45 + RelevanceMerge.W_SUMMARY * 0.40
            + RelevanceMerge.W_DETAILS * 0.30;
        assertTrue(oldOneWord > answer,
            "the control: under the best-in-set rule the one-word row must win, or this "
                + "fixture does not reproduce the dogfood and proves nothing");

        Map<String, RelevanceMerge.Score> scores = RelevanceMerge.scoreAll(
            sit, sum, det, words(questionCeiling, "oneWord", oneWordRaw));

        assertEquals(oneWordRaw / questionCeiling, scores.get("oneWord").words(), 1e-9,
            "a row carrying one eighth of the question's words reads one eighth, not 1.0");
        assertTrue(scores.get("answer").total() > scores.get("oneWord").total(),
            "the row whose meaning answers must outrank a row that shares one word of it");
    }

    /**
     * Normalisation is against a PERFECT word match of the question, so what a word
     * score says afterwards is "how much of the question's words this row carries" —
     * and the best row is no longer promoted to perfect just by being the best.
     */
    @Test
    void the_word_lane_is_rescaled_against_a_perfect_match_of_the_question() {
        assertEquals(Map.of(), RelevanceMerge.normalise(null, 8.0),
            "no word scores is a real state — the embedder-only path — not an error");
        assertEquals(Map.of(), RelevanceMerge.normalise(map("a", 3.0), 0.0),
            "and a question with no word any row could match normalises to nothing rather "
                + "than dividing by zero");

        Map<String, Double> n = RelevanceMerge.normalise(map("a", 8.0, "b", 2.0, "c", 4.0), 8.0);
        assertEquals(1.0, n.get("a"), 1e-9);
        assertEquals(0.25, n.get("b"), 1e-9);
        assertEquals(0.5, n.get("c"), 1e-9);

        Map<String, Double> partial = RelevanceMerge.normalise(map("b", 2.0, "c", 4.0), 8.0);
        assertEquals(0.5, partial.get("c"), 1e-9,
            "THE PROPERTY: when no row carries the whole question, the best row reads what it "
                + "carries — the old rule would have read 1.0 here");

        assertEquals(1.0, RelevanceMerge.normalise(map("short", 12.0), 8.0).get("short"), 1e-9,
            "a short row or a repeated word can exceed the average-length perfect match; it is "
                + "clamped, because more than the whole question is not a thing this lane means");

        // The same SHAPE at a different scale normalises identically: the ceiling is
        // computed from the corpus with the scores, so corpus size moves both together.
        Map<String, Double> big = RelevanceMerge.normalise(
            map("a", 800.0, "b", 200.0, "c", 400.0), 800.0);
        assertEquals(n, big,
            "corpus size changes BM25's magnitude; it must not change the ranking");
    }

    /**
     * A missing lane scores zero and the weights are NOT renormalised — an
     * entry that declares no situation cannot reach the rank of one that
     * declares a matching one.
     *
     * <p>The consequence is intended and is the reason it is asserted rather
     * than left to emerge: renormalising would let a bare row with one lucky
     * field score as though it had matched on everything, which is exactly how a
     * heading-shaped note out-ranks a written story.</p>
     */
    @Test
    void an_undeclared_field_costs_rank_and_is_not_renormalised_away() {
        Map<String, RelevanceMerge.Score> scores = RelevanceMerge.scoreAll(
            map("declared", 0.9),                    // "silent" has no situation at all
            map("declared", 0.9, "silent", 0.9),
            map("declared", 0.9, "silent", 0.9),
            LexicalIndex.Scores.NONE);

        assertEquals(0.0, scores.get("silent").situation(), 1e-9,
            "an absent lane is zero, not an average of the ones that are present");
        assertTrue(scores.get("declared").total() > scores.get("silent").total(),
            "so under-declaring costs rank; the two would tie if the weights were "
                + "renormalised over the lanes an entry happens to have");
        assertEquals(RelevanceMerge.W_SITUATION * 0.9,
            scores.get("declared").total() - scores.get("silent").total(), 1e-9,
            "and the gap is exactly the situation weight — nothing else moved");
    }

    /**
     * The union, not the intersection: a row only ONE lane found is still
     * ranked.
     *
     * <p>Requiring agreement would drop precisely the rows each lane exists to
     * catch that the others cannot — the word lane's rare-token match that no
     * vector sees, and the vector's paraphrase that shares no words.</p>
     */
    @Test
    void a_row_that_only_one_lane_found_is_still_scored() {
        Map<String, RelevanceMerge.Score> scores = RelevanceMerge.scoreAll(
            map("bySituation", 0.7), Map.of(), Map.of(), words(3.0, "byWords", 3.0));

        assertTrue(scores.containsKey("bySituation"));
        assertTrue(scores.containsKey("byWords"));
        assertEquals(RelevanceMerge.W_WORDS, scores.get("byWords").total(), 1e-9,
            "a row carrying every word of the question normalises to 1.0 and contributes "
                + "exactly its weight");
    }

    /**
     * RELEVANCE ONLY, and here that is a claim about the TYPE.
     *
     * <p>This test cannot pass an outcome, an origin, an age or a usage count to
     * the merge, because it has nowhere to put them — the parameters are three
     * proximity maps and the word scores. That is the point: ranking on any of those
     * would require widening a signature, which is a visible edit. What the test adds
     * beyond the signature is that the function is a pure function of its inputs, so
     * nothing is being read from a field or a clock behind the caller's back.</p>
     */
    @Test
    void the_ranking_is_a_pure_function_of_proximity() {
        Map<String, Double> sit = map("a", 0.4, "b", 0.6);
        Map<String, Double> sum = map("a", 0.9, "b", 0.1);
        Map<String, Double> det = map("a", 0.2, "b", 0.2);
        LexicalIndex.Scores w = words(5.0, "a", 1.0, "b", 5.0);

        Map<String, RelevanceMerge.Score> first = RelevanceMerge.scoreAll(sit, sum, det, w);
        Map<String, RelevanceMerge.Score> again = RelevanceMerge.scoreAll(sit, sum, det, w);
        assertEquals(first, again,
            "same proximities, same scores — no hidden state, no clock, no store");

        assertEquals(
            RelevanceMerge.W_SITUATION * 0.4 + RelevanceMerge.W_SUMMARY * 0.9
                + RelevanceMerge.W_DETAILS * 0.2 + RelevanceMerge.W_WORDS * 0.2,
            first.get("a").total(), 1e-9,
            "and the total is exactly the declared weights against the four lanes — "
                + "no term this test did not supply");
    }

    /** Nothing scored means nothing ranked, rather than an empty-looking zero row. */
    @Test
    void no_lane_scored_anything_yields_no_ranking_at_all() {
        assertTrue(RelevanceMerge.scoreAll(Map.of(), Map.of(), Map.of(),
            LexicalIndex.Scores.NONE).isEmpty());
        assertFalse(RelevanceMerge.scoreAll(Map.of(), map("x", 0.1), Map.of(),
            LexicalIndex.Scores.NONE).isEmpty(),
            "while one lane finding one row IS a ranking of one");
    }
}
