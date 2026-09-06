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
        assertTrue(bare.cures().isEmpty(),
            "and nothing executable is offered, because there is nothing to point at");
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
