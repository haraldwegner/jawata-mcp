package org.jawata.mcp.tools.smell;

import org.jawata.mcp.domain.Finding;
import org.jawata.mcp.models.CodeAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A FINDING THAT CANNOT BE ACTED ON MUST SAY SO, AND SAY WHOSE FAULT IT IS.
 *
 * <p>A cure is an operation, and an operation has to be pointed somewhere. The
 * dangerous state is a kind whose cure IS runnable meeting a finding that carries no
 * address the cure could be run from — a bare member name, say, where a door needs a
 * qualified one. Rendering "run this" there hands over an instruction that fails at the
 * door, and the caller has no way to tell whether the cure, the address or their own
 * call was wrong.</p>
 *
 * <p>So it FAILS VISIBLE: the sentence says CONSIDER and NAMES THE DETECTOR, because the
 * detector is who has to emit a better address. The failure being refused is the
 * fail-open one — an instruction that looks fine and cannot be followed.</p>
 */
class AnIncompleteAddressFailsVisibleTest {

    /** switch_statements has one runnable cure whose step is a pattern kind, so it RUNs. */
    private static final String RUNNABLE_KIND = "switch_statements";

    /**
     * The operations these two kinds' cures name, supplied EXPLICITLY.
     *
     * <p>{@code CureTier.derive(kind)} consults the PROCESS registry, which a unit-test JVM
     * leaves empty because no tool has registered — so every kind would answer CONSIDER for a
     * reason about plumbing and every assertion below would pass while measuring nothing.
     * This file's sibling {@code CureTierTest} records the same trap twice, for {@code cqs}
     * and for {@code lazy_class}.</p>
     */
    private static final java.util.List<String> REGISTERED = java.util.List.of(
        "replace_conditional_with_polymorphism", "inline kind=class", "inline kind=subclass");

    private static String hintFor(Finding f) {
        return CureLookup.forKind(org.jawata.mcp.knowledge.CatalogueAddresses.of(null),
            f.kind()).hint(CodeAddress.of(f));
    }

    @Test
    @DisplayName("a bare symbol renders CONSIDER and names the detector that emitted it")
    void aBareSymbolCannotBeRunFrom() {
        // No file, no line, and `items` is a name no door can resolve.
        Finding bare = new Finding(RUNNABLE_KIND, null, -1, -1, "warning", "msg", "items");
        assertFalse(CodeAddress.of(bare).complete(),
            "a bare member name carries no package, so nothing can look it up");

        String hint = hintFor(bare);
        assertTrue(hint.contains("TIER: CONSIDER"),
            () -> "a cure that cannot be run from this finding must not be rendered as an"
                + " instruction: " + hint);
        assertTrue(hint.contains(RUNNABLE_KIND + " detector's gap"),
            () -> "and it must name WHOSE gap it is — the detector emits the address, so"
                + " a reader who wants it fixed knows where to look: " + hint);
        // AND NOTHING EXECUTABLE IS OFFERED. This used to read `bare.cures().isEmpty()` on a
        // Finding built by the convenience constructor — empty BY CONSTRUCTION, so it passed
        // with the whole join deleted. A C8b audit found it, and it was the only test step 4
        // added. It now calls the join itself, which is the thing that could be wrong.
        assertTrue(Cures.stepsFor(RUNNABLE_KIND, CodeAddress.of(bare), REGISTERED).isEmpty(),
            "the cure is RUNNABLE and the address is not, so the join must offer nothing"
                + " rather than an instruction that fails at the door");
    }

    @Test
    @DisplayName("a line with no column is NOT a complete position, and offers nothing")
    void aLineWithoutAColumnIsNotAPosition() {
        // THE SHAPE 37 OF THE 40 DETECTOR EMISSION SITES PRODUCE, and the one no test
        // constructed until a C8b audit said so. `complete()` used to ask only for a line,
        // so a finding like this was called complete, rendered RUN, and the door answered
        // INVALID_COORDINATES — the fail-open the method exists to prevent.
        //
        // The symbol is deliberately BARE: a qualified one satisfies complete()'s first
        // branch and this assertion would then be about that branch instead of this one.
        Finding lineOnly =
            new Finding(RUNNABLE_KIND, "/tmp/A.java", 12, -1, "warning", "m", "items");
        CodeAddress address = CodeAddress.of(lineOnly);
        assertEquals(11, address.line(), "the line still converts");
        assertEquals(-1, address.column(), "and the absent column stays absent");
        assertFalse(address.complete(),
            "half a position is not a position: every door that takes one demands both");
        assertFalse(address.arguments().containsKey("line"),
            "so neither coordinate is handed over — a lone line would make the door take"
                + " the positional path and then refuse");
        assertTrue(Cures.stepsFor(RUNNABLE_KIND, address, REGISTERED).isEmpty(),
            "and the join offers nothing, for the same reason the bare symbol does");
    }

    @Test
    @DisplayName("the join renders one step per runnable cure, with its discriminator")
    void theJoinRendersTheCuresThemselves() {
        // THE POSITIVE HALF, which nothing asserted at all: every test above proves the join
        // is SILENT where it should be, and silence is also what a deleted join produces.
        // `lazy_class` is used because it declares TWO runnable cures, so this also pins that
        // the ranked list arrives whole rather than as its first element.
        CodeAddress address = CodeAddress.of(new Finding(
            "lazy_class", "/tmp/A.java", 3, 7, "warning", "m", "com.foo.Bare"));
        java.util.List<org.jawata.mcp.models.NextStep> steps =
            Cures.stepsFor("lazy_class", address, REGISTERED);
        assertEquals(2, steps.size(),
            () -> "lazy_class ships two fixes and both are handed over: " + steps);
        assertEquals(address, steps.get(0).address(),
            "each step points at the address the finding carried, not at a fresh one");
        for (org.jawata.mcp.models.NextStep step : steps) {
            assertFalse(step.rendered().isEmpty(),
                () -> "and each renders to the wire shape a caller reads: " + step);
        }
    }

    @Test
    @DisplayName("a qualified symbol renders RUN and carries the symbol as the argument")
    void aQualifiedSymbolCanBeRunFrom() {
        Finding named =
            new Finding(RUNNABLE_KIND, null, -1, -1, "warning", "msg", "com.foo.Bar#items");
        CodeAddress address = CodeAddress.of(named);
        assertTrue(address.complete(), "a qualified symbol is what a door resolves");

        String hint = hintFor(named);
        assertTrue(hint.contains("TIER: RUN"),
            () -> "the same kind, with an address, is an instruction: " + hint);
        assertEquals("com.foo.Bar#items", address.arguments().get("symbol"),
            "and the argument handed to the door is the symbol the finding carried");
    }

    @Test
    @DisplayName("the 1-based finding line becomes a 0-based door line exactly once")
    void theLineIsConvertedInOnePlace() {
        // THE CONVERSION USED TO LIVE WHEREVER A CALLER REMEMBERED IT. A finding's line is
        // 1-based (JDT's convention) and every door's is 0-based, so the subtraction is a
        // fact about the boundary rather than about any one caller.
        Finding at10 = new Finding(RUNNABLE_KIND, "/tmp/A.java", 10, 5, "warning", "m", null);
        CodeAddress address = CodeAddress.of(at10);
        assertEquals(9, address.line(), "1-based 10 is 0-based 9");
        assertEquals(4, address.column(), "and the column converts with it");
        assertTrue(address.complete(), "a file and a line is the other complete form");
        assertEquals(9, address.arguments().get("line"),
            "and what reaches the door is the converted value, not the finding's");

        // NOT APPLICABLE STAYS NOT APPLICABLE. -1 is the domain's "no line"; subtracting
        // from it would produce -2, which is a number a door would try to use.
        Finding none = new Finding(RUNNABLE_KIND, "/tmp/A.java", -1, -1, "warning", "m", null);
        assertEquals(-1, CodeAddress.of(none).line(), "-1 means N/A and must not shift");
        assertFalse(CodeAddress.of(none).complete(), "a file with no line points at nothing");
    }
}
