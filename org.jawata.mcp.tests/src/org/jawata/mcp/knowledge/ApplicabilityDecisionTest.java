package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Sprint 28c D2 — the split between nominating and answering.
 *
 * <p>The measured defect: seven nonsense design questions each returned the
 * maximum eleven candidates, and the policy that produced them states in its own
 * javadoc that no threshold separates nonsense from an answer. These tests pin
 * the property that makes abstention possible at all — ranking never says
 * anything fits, so an empty selection is the honest outcome rather than a
 * failure to find something.</p>
 */
class ApplicabilityDecisionTest {

    private static final String Q = "how should I structure a component that must "
        + "keep working when a downstream service is failing?";

    @Test
    void an_empty_selection_is_an_absence_and_an_absence_is_an_answer() {
        ApplicabilityDecision d = new ApplicabilityDecision();
        String queryId = d.nominate(Q, List.of("a", "b", "c"));

        Object out = d.decide(queryId, List.of());

        ApplicabilityDecision.Decision decision =
            assertInstanceOf(ApplicabilityDecision.Decision.class, out,
                "deciding that nothing applies is a normal outcome, not a refusal");
        assertTrue(decision.isAbsence(),
            "three candidates were offered and none judged applicable — that is an "
                + "ABSENCE. The store's measured failure was being unable to produce "
                + "this, so a nonsense question came back with eleven suggestions");
        assertEquals(List.of(), decision.selected());
    }

    @Test
    void a_selection_becomes_a_match_ordered_by_the_ranking() {
        ApplicabilityDecision d = new ApplicabilityDecision();
        String queryId = d.nominate(Q, List.of("first", "second", "third"));

        Object out = d.decide(queryId, List.of("third", "first"));

        ApplicabilityDecision.Decision decision =
            assertInstanceOf(ApplicabilityDecision.Decision.class, out);
        assertEquals(ApplicabilityDecision.Result.MATCH, decision.result());
        assertEquals(List.of("first", "third"), decision.selected(),
            "returned in the RANKING's order, not the order the caller happened to "
                + "list them: the response reads as an answer, and its order should be "
                + "the engine's one defensible claim rather than an artifact of the request");
        assertEquals(Q, decision.question(),
            "and it carries the question, so a journal row can say what was asked");
    }

    /**
     * The door this design exists to close. Without the query_id check a caller
     * could name any entry in the store and have it returned as vouched — the
     * old similarity pile, re-entering through something labelled a decision.
     */
    @Test
    void an_id_that_was_never_nominated_is_refused_rather_than_dropped() {
        ApplicabilityDecision d = new ApplicabilityDecision();
        String queryId = d.nominate(Q, List.of("offered"));

        Object out = d.decide(queryId, List.of("offered", "never-nominated"));

        ApplicabilityDecision.Refusal refusal =
            assertInstanceOf(ApplicabilityDecision.Refusal.class, out,
                "silently dropping the unknown id would turn a caller's mistake into a "
                    + "smaller answer that still looks confident");
        assertTrue(refusal.reason().contains("never-nominated"),
            "and it names the id, so the caller can see what it got wrong: " + refusal.reason());
    }

    @Test
    void deciding_twice_on_one_nomination_is_refused() {
        ApplicabilityDecision d = new ApplicabilityDecision();
        String queryId = d.nominate(Q, List.of("a"));
        assertInstanceOf(ApplicabilityDecision.Decision.class, d.decide(queryId, List.of("a")));

        Object second = d.decide(queryId, List.of());

        assertInstanceOf(ApplicabilityDecision.Refusal.class, second,
            "a second decision is either a mistake or a retry after a lost response; "
                + "answering it again silently endorses whichever arrived last");
    }

    /**
     * An expired nomination must NOT read as "nothing applied". The two are
     * different claims and only one of them is knowledge — recording an absence
     * for a question nobody answered is the empty-result-as-an-answer failure
     * this codebase keeps finding.
     */
    @Test
    void an_unknown_query_is_refused_and_never_reported_as_an_absence() {
        ApplicabilityDecision d = new ApplicabilityDecision();

        Object out = d.decide("00000000-0000-0000-0000-000000000000", List.of());

        ApplicabilityDecision.Refusal refusal =
            assertInstanceOf(ApplicabilityDecision.Refusal.class, out);
        assertTrue(refusal.reason().contains("NOT an absence"),
            "the refusal says so in as many words, because the caller's next move is to "
                + "ask again — not to record that nothing applied: " + refusal.reason());
    }

    @Test
    void a_forgetful_caller_cannot_grow_the_resident() {
        ApplicabilityDecision d = new ApplicabilityDecision();
        String first = d.nominate(Q, List.of("a"));

        // ONE SHORT of the bound: `first` plus this many is exactly MAX_OPEN, so
        // nothing has been evicted yet.
        for (int i = 0; i < ApplicabilityDecision.MAX_OPEN - 1; i++) {
            d.nominate(Q + " #" + i, List.of("a"));
        }
        String stillOpen = d.nominate("a question asked just under the bound", List.of("a"));

        // THE VALUE OF THE BOUND, pinned through the contract. Asserting only that an
        // over-the-bound nomination is evicted does NOT pin it: `size() > 1` passes
        // that check while destroying every concurrent caller's nomination, so a
        // resident serving two clients would refuse almost every decide. This half
        // says the bound is not smaller than it claims.
        Object survivor = d.decide(stillOpen, List.of());
        assertInstanceOf(ApplicabilityDecision.Decision.class, survivor,
            "a nomination made while the register is at its bound is still open — a "
                + "bound of 1 would evict it and refuse every second caller");

        // Now push past the bound and watch the OLDEST go.
        for (int i = 0; i < ApplicabilityDecision.MAX_OPEN; i++) {
            d.nominate(Q + " overflow #" + i, List.of("a"));
        }
        assertInstanceOf(ApplicabilityDecision.Refusal.class, d.decide(first, List.of()),
            "and the register is bounded — a caller that nominates and never decides "
                + "leaks nothing — with the evicted one refused rather than answered as "
                + "an absence, because it was never decided by anybody");
    }

    @Test
    void a_question_with_no_near_neighbours_is_still_a_legitimate_nomination() {
        ApplicabilityDecision d = new ApplicabilityDecision();
        String queryId = d.nominate("what colour is the number seven?", List.of());

        // Again through the contract. An empty candidate list that was never REGISTERED
        // would make decide refuse with "not open"; a Decision proves the nomination is
        // real, and isAbsence proves what it means. Offering nothing is a legitimate
        // nomination for a question the corpus has no neighbours for — the alternative
        // is returning the least-bad rows, which is exactly what produced eleven
        // suggestions for each of seven nonsense questions.
        // THE REGISTER HELD AN EMPTY LIST, not merely some list. Proven by trying to
        // select something: decide validates the selection against what was nominated,
        // so a fabricated candidate would be accepted and this would come back a
        // Decision. Asserting `decision.selected()` is empty instead proves nothing —
        // selected is the caller's argument intersected with the candidates, and the
        // caller passed nothing, so it is empty whatever nominate registered.
        ApplicabilityDecision second = new ApplicabilityDecision();
        String probe = second.nominate("what colour is the number seven?", List.of());
        assertInstanceOf(ApplicabilityDecision.Refusal.class,
            second.decide(probe, List.of("anything-at-all")),
            "nothing was offered, so nothing can be selected — if the register had "
                + "quietly filled in the least-bad rows this would be a match, which is "
                + "exactly what produced eleven suggestions for each of seven nonsense "
                + "questions");

        ApplicabilityDecision.Decision decision = assertInstanceOf(
            ApplicabilityDecision.Decision.class, d.decide(queryId, List.of()),
            "a nomination with no candidates is still open and still decidable");
        assertTrue(decision.isAbsence(),
            "and deciding on it is an absence — a real answer for a question the corpus "
                + "has no neighbours for");
    }

    @Test
    void every_nomination_gets_its_own_id() {
        ApplicabilityDecision d = new ApplicabilityDecision();
        assertNotEquals(d.nominate(Q, List.of("a")), d.nominate(Q, List.of("a")),
            "two askings of the same question are two conversations; sharing an id "
                + "would let one caller's decision consume another's nomination");
    }
}
