package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28f Stage 7 — the form an AREA takes.
 *
 * <p>An area says what one PACKAGE is for, the way a job says what one member is for.
 * It is the same lane and the same failure: a summary that hands back the name it was
 * given. {@code "Org jawata mcp knowledge"} is the package identifier with spaces in it
 * and tells a reader nothing they did not type to get there.</p>
 *
 * <p><b>What this test does NOT own, said so the absence is not read as an oversight.</b>
 * The deliverable also says "one per package". That is a UNIQUENESS rule about what the
 * store already holds, not a judgement about the text in front of the gate — the form
 * check is pure and sees one entry at a time, so it cannot know whether a sibling
 * exists. It belongs with the catalogue ledger, which is the deliverable that tracks
 * which packages are described; enforcing it here would need the gate to query the
 * store, which is the kind of reach this class has stayed clear of.</p>
 */
class AreaSummaryTest {

    /** The package an area hangs on, which is its anchor. */
    private static final String PACKAGE = "org.jawata.mcp.knowledge";

    /** What the package is FOR — the thing its name cannot say. */
    private static final String GOOD_AREA =
        "Holds the knowledge store: the rows, the lanes they sit in, the loader that reads"
            + " memory files, and the retrieval that answers with them.";

    private static Optional<EntryForm.Refusal> area(String summary) {
        return EntryForm.check("area", summary, List.of(), null, null, PACKAGE);
    }

    @Test
    void an_area_that_says_what_the_package_is_for_is_admitted() {
        assertTrue(area(GOOD_AREA).isEmpty(),
            "an area owes no situation and no outcome, so a plain statement of what the"
                + " package is for is the whole form. If this is refused, an earlier gate is"
                + " eating areas and the refusal below proves nothing");
    }

    /**
     * The same rule the job form applies, reached through the same branch — which is the
     * point of the two types sharing a lane rather than each getting their own gate.
     */
    @Test
    void the_packages_own_name_re_spaced_is_not_an_area() {
        Optional<EntryForm.Refusal> r = area("Org jawata mcp knowledge");
        assertTrue(r.isPresent(),
            "the package identifier spelled with spaces is the name, not its purpose");
        assertEquals("summary", r.get().field());
        assertTrue(r.get().message().contains("restates"),
            () -> "and it must say WHY: " + r.get().message());
    }

    /**
     * AND THE LANE IS WHAT BINDS IT, not the word "area". The same text under a type
     * outside the code lane is admitted, so the rule cannot be a global ban on short
     * name-shaped summaries.
     */
    @Test
    void the_same_text_is_admitted_outside_the_code_lane() {
        assertTrue(
            EntryForm.check("domain_fact", "Org jawata mcp knowledge", List.of(), null, null,
                PACKAGE).isEmpty(),
            "a domain fact may name a package — the code lane's rule must bind to the code"
                + " lane only");
    }
}
