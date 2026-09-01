package org.jawata.mcp.tools.smell;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A finding's two halves must name the SAME fix.
 *
 * <p>Every finding carries two recommendations from two different places. The
 * detector writes a sentence — "Consider Replace Conditional with Polymorphism"
 * — hardcoded when the detector was. {@link CureCatalog} separately resolves a
 * runnable operation and a catalogue address. Until v4.0.0 wired the store in,
 * only one kind rendered the second half, so the two were never seen side by
 * side and could disagree unnoticed.</p>
 *
 * <p><b>They did.</b> Found by dogfooding v4.0.1 on release day, with one call
 * against real code: {@code switch_statements} answered with prose recommending
 * Replace Conditional with Polymorphism and a runnable instruction offering
 * Refactor to State. Sprint 28d had BUILT Replace Conditional with Polymorphism
 * — for that smell — and the table was never told. Neither of that sprint's two
 * new operations was referenced by any cure.</p>
 *
 * <p><b>Why nothing caught it.</b> The existing tests assert that a declaring
 * kind renders a cure carrying a tier, which this did. The wiring gate asks
 * whether a member has a caller outside the tests, and these operations do.
 * Neither can see a finding whose two halves disagree, which is the one question
 * this file asks.</p>
 *
 * <p><b>What it cannot see</b>, so a green is not over-read: it compares the
 * recipe key against the prose by name-matching, and a detector whose prose
 * names a refactoring with no runnable operation at all is exempt — those are
 * the advisory kinds, and they are correct. It also cannot judge whether a
 * SECOND route is appropriate; it checks the FIRST, because order is the claim.</p>
 */
class TheProseAndTheCureNameTheSameFixTest {

    /**
     * The prose each detector writes, against the recipe key that would satisfy
     * it. Written as a table rather than parsed out of source, because parsing
     * prose is how a check becomes clever and then wrong — and because a human
     * has to decide what "Consider Extract Method" is satisfied by.
     */
    private static final Map<String, List<String>> PROSE_SATISFIED_BY = Map.of(
        // "Consider Replace Conditional with Polymorphism."
        "switch_statements", List.of("replace_conditional_with_polymorphism"),
        // "Consider Replace Type Code with Class / Parameter Object"
        "type_code", List.of("replace_type_code_with_class"),
        // "Consider Extract Method" — Compose Method IS the composed form of it
        "long_method", List.of("compose_method"),
        // "Candidate for Inline Singleton (refactor_to_pattern kind=inline_singleton)"
        "singleton", List.of("inline_singleton"));

    @Test
    @DisplayName("the FIRST runnable cure is the one the detector's own prose names")
    void theFirstCureMatchesTheProse() {
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : PROSE_SATISFIED_BY.entrySet()) {
            String kind = e.getKey();
            List<CureCatalog.Cure> cures = CureCatalog.curesFor(kind);
            assertFalse(cures.isEmpty(), () -> kind + " declares no cure at all");

            String first = cures.get(0).recipe();
            if (first == null || !e.getValue().contains(first)) {
                wrong.add(kind + ": prose asks for " + e.getValue()
                    + ", table offers " + first);
            }
        }
        assertTrue(wrong.isEmpty(),
            () -> "a finding must not name one refactoring in its sentence and offer a"
                + " different one as runnable — a reader gets two answers and cannot tell"
                + " which is meant:\n  " + String.join("\n  ", wrong));
    }

    /*
     * DELETED: "every operation the product ships is reachable from some problem
     * that recommends it".
     *
     * It asserted something that is not true and should not be forced. Sprint
     * 28d's replace_constructor_with_factory has no smell whose prose asks for a
     * factory, and no detector should acquire one to satisfy a check. I wrote
     * this test, then edited the cure table to make it pass — bolting that
     * operation onto type_code, where it served the assertion rather than a
     * reader, and where the extra route cost the kind its PERFORM tier.
     *
     * The suite caught it: CureTierTest went from PERFORM to ADVISE on two kinds.
     * A check that can be satisfied by making the product worse is a check that
     * has to go, and the fact that its author is also the author of the change is
     * exactly why it went unnoticed for the ten minutes it existed.
     *
     * The finding it was reaching for is real and stays recorded: an operation
     * can ship, be registered, be tested and be runnable while nothing routes to
     * it. That is worth knowing. It is not worth a row invented to hide it.
     */

    @Test
    @DisplayName("the control: the check can fail, on a table that disagrees with its prose")
    void theControlTheCheckNeeds() {
        // A check only ever seen agreeing with itself is indistinguishable from
        // one that cannot disagree. This is the v4.0.1 state, by hand.
        Map<String, List<String>> prose = Map.of("switch_statements",
            List.of("replace_conditional_with_polymorphism"));
        List<CureCatalog.Cure> asShipped =
            List.of(new CureCatalog.Cure("refactor_to_state", "design:state"));

        String first = asShipped.get(0).recipe();
        assertFalse(prose.get("switch_statements").contains(first),
            "the pre-fix table must be recognised as disagreeing — if this passes, the"
                + " comparison above cannot detect the defect it was written for");
    }
}
