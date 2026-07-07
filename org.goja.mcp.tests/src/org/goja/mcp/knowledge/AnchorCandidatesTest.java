package org.goja.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.goja.mcp.knowledge.AnchorCandidates.Candidate;
import org.junit.jupiter.api.Test;

/**
 * Sprint 21e Stage 0 — candidate extraction + notation normalization, pure parsing.
 *
 * The accepted shapes are the REAL ORB corpus tokens (verified 2026-07-07 against
 * strategies_orb/docs/agent_recall_gap_lessons.md): unqualified `Type.member`,
 * `Type#member`, mixed notation, args in mentions. A mis-classified span merely fails
 * resolution later — these tests pin what must and must not become a candidate.
 */
class AnchorCandidatesTest {

    private static Optional<Candidate> only(String text) {
        List<Candidate> all = AnchorCandidates.extract(text);
        assertTrue(all.size() <= 1, "expected at most one candidate, got " + all);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    @Test
    void dot_member_notation_parses_type_and_member() {
        Candidate c = only("Released in `SlotManager.freeSlot` field-by-field.").orElseThrow();
        assertEquals("SlotManager", c.typeToken());
        assertEquals(List.of("freeSlot"), c.members());
        assertEquals(1, c.mentions());
    }

    @Test
    void hash_member_notation_parses_type_and_member() {
        Candidate c = only("Cleared only via `Slot#activeOrder` callback.").orElseThrow();
        assertEquals("Slot", c.typeToken());
        assertEquals(List.of("activeOrder"), c.members());
    }

    @Test
    void trailing_args_are_stripped_before_classification() {
        Candidate c = only("The call `Slot.attachOrder(order)` runs late.").orElseThrow();
        assertEquals("Slot", c.typeToken());
        assertEquals(List.of("attachOrder"), c.members());
    }

    @Test
    void bare_lowercase_member_is_not_a_candidate() {
        assertTrue(only("Calling `freeSlot()` directly is the bug.").isEmpty());
    }

    @Test
    void fenced_code_blocks_are_ignored() {
        String text = """
            Prose before.
            ```java
            SlotManager.freeSlot(4); // `SlotManager.freeSlot` inside a fence
            ```
            Prose after with no spans.
            """;
        assertTrue(AnchorCandidates.extract(text).isEmpty());
    }

    @Test
    void non_symbol_spans_are_discarded() {
        String text = "SQL `LIKE fqn || '.%'`, a path `docs/sprints/x.md`, a flag `-Dtest=Foo`,"
                + " an expression `activeOrder.symbol=BAC != slotSymbol=INTC`.";
        assertTrue(AnchorCandidates.extract(text).isEmpty());
    }

    @Test
    void screaming_snake_constants_are_not_type_candidates() {
        assertTrue(only("The alarm `SLOT_IDENTITY_MISMATCH` fired.").isEmpty());
    }

    @Test
    void mention_counting_aggregates_dot_and_hash_notation() {
        String text = "`SlotManager.freeSlot` misses state; `SlotManager#freeSlot` again;"
                + " plain `SlotManager` too.";
        Candidate c = only(text).orElseThrow();
        assertEquals("SlotManager", c.typeToken());
        assertEquals(3, c.mentions());
        assertEquals(List.of("freeSlot"), c.members());
    }

    @Test
    void qualified_token_keeps_its_package_in_the_type_token() {
        Candidate c = only("The hook cue is `pipeline.SlotManager#freeSlot`.").orElseThrow();
        assertEquals("pipeline.SlotManager", c.typeToken());
        assertEquals(List.of("freeSlot"), c.members());
    }

    @Test
    void type_only_mention_has_no_member() {
        Candidate c = only("`SlotManager` owns slots.").orElseThrow();
        assertEquals("SlotManager", c.typeToken());
        assertTrue(c.members().isEmpty());
    }

    @Test
    void lowercase_dotted_chain_is_not_a_candidate() {
        assertTrue(only("Property `foo.bar` is unrelated.").isEmpty());
    }

    @Test
    void distinct_members_of_one_type_are_collected_in_order() {
        String text = "`Slot#activeOrder` then `Slot.attachOrder(o)` on the same type.";
        Candidate c = only(text).orElseThrow();
        assertEquals("Slot", c.typeToken());
        assertEquals(2, c.mentions());
        assertEquals(List.of("activeOrder", "attachOrder"), c.members());
    }

    @Test
    void candidates_preserve_first_appearance_order() {
        String text = "`SlotManager.freeSlot` before `Slot#activeOrder` and `SlotManager` again.";
        List<Candidate> all = AnchorCandidates.extract(text);
        assertEquals(2, all.size());
        assertEquals("SlotManager", all.get(0).typeToken());
        assertEquals("Slot", all.get(1).typeToken());
        assertEquals(2, all.get(0).mentions());
    }

    @Test
    void filename_style_span_counts_as_a_type_mention_with_nonexistent_member() {
        // `SlotManager.java` IS a reference to the type; the bogus member fails the
        // Stage-1 member-exists check and the anchor stays type-level.
        Candidate c = only("See `SlotManager.java` for details.").orElseThrow();
        assertEquals("SlotManager", c.typeToken());
        assertEquals(List.of("java"), c.members());
    }
}
