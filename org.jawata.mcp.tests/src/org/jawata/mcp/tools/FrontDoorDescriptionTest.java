package org.jawata.mcp.tools;

import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE DESCRIPTION SEAM — that it produces the right text, AND that a door actually uses it.
 *
 * <p>Those are two claims and only the second is easy to get wrong. A golden-file check passes
 * the moment the assembler CAN reproduce a door's text, whether or not the door's
 * {@code getDescription()} calls it — so a wiring that was never connected would look exactly
 * like a wiring that works. The plan names both halves for that reason, and they are separate
 * tests here so a failure says which one broke.</p>
 */
class FrontDoorDescriptionTest {

    private static InlineTool inline() {
        return new InlineTool(() -> null, new RefactoringChangeCache());
    }

    /** Every character that is not whitespace, in order — layout removed, content kept. */
    private static String content(String text) {
        return text.replaceAll("\\s+", "");
    }

    @Test
    @DisplayName("M5 changed the LAYOUT of inline's description and not one character of its prose")
    void theProjectionLosesNoProse() {
        // The strongest claim available once the bullets are projected, and the honest one.
        // M4 could assert byte-identity because it only moved the algorithm. M5 deliberately
        // replaces hand-alignment with one uniform rule, so byte-identity is the wrong
        // question — asserting it would mean pinning text the door no longer publishes.
        // What must NOT change is the prose, and this compares every non-whitespace
        // character in order: a dropped sentence, a mangled refusal, a bullet that lost its
        // tail all fail here, while the alignment is free to differ.
        assertEquals(content(InlineTool.LEGACY_DESCRIPTION),
            content(FrontDoorDescription.ASSEMBLER.describe(inline())),
            "the per-kind prose moved from the door onto its delegates; if a character of it"
                + " went missing on the way, the move lost documentation rather than"
                + " relocating it");
    }

    @Test
    @DisplayName("the projected block is built from the delegates, so each carries its own bullet")
    void theBlockComesFromTheDelegates() {
        InlineTool door = inline();
        String block = FrontDoorDescription.ASSEMBLER.kindBlockOf(door);

        door.delegates().forEach((kind, delegate) -> {
            assertTrue(block.contains("- " + kind + " — "),
                kind + " must appear as its own bullet: " + block);
            assertTrue(content(block).contains(content(delegate.kindSummary())),
                kind + "'s bullet must be what the DELEGATE says, not what the door said");
        });
        // PROOF OF LIFE: a door with no delegates would satisfy both loops vacuously.
        assertTrue(door.delegates().size() == 5, "inline routes five kinds");
    }

    @Test
    @DisplayName("the door ROUTES through the shared assembler, which the golden alone cannot show")
    void theDoorActuallyUsesIt() {
        InlineTool door = inline();
        assertEquals(FrontDoorDescription.ASSEMBLER.describe(door), door.getDescription(),
            "the golden above passes as soon as the assembler exists and can reproduce the"
                + " text — whether or not this door asks it. This is the half that fails when"
                + " the seam is built and never connected.");
    }

    @Test
    @DisplayName("the USAGE line is DERIVED: it names the door's discriminator and every routed kind")
    void theUsageLineComesFromTheRoutingTable() {
        InlineTool door = inline();
        String usage = FrontDoorDescription.ASSEMBLER.usageLine(door);

        assertTrue(usage.startsWith("USAGE: inline(kind=\"<"), usage);
        for (String kind : door.publishedKinds()) {
            assertTrue(usage.contains(kind),
                "a kind that ships must reach the usage line — that is the whole point of"
                    + " deriving it: " + kind + " missing from " + usage);
        }
        // PROOF OF LIFE for the loop above: a door publishing nothing would satisfy it.
        assertTrue(door.publishedKinds().size() >= 5,
            "inline publishes five kinds; an empty list makes the loop vacuous");
    }

    @Test
    @DisplayName("move's usage line is now ONE line — the step's single declared text change")
    void moveUsageLineIsNoLongerHandWrapped() {
        MoveTool door = new MoveTool(() -> null, new RefactoringChangeCache());
        String usage = FrontDoorDescription.ASSEMBLER.usageLine(door);

        // The published text for this door changed in exactly one way, and it is asserted
        // here rather than left in a commit message. The old line was wrapped across two
        // source lines because six kind names are long; a generated line is not wrapped,
        // and teaching the assembler to wrap would make a column width a constant somebody
        // maintains. The wrap was presentation; the kinds are the contract.
        assertEquals(-1, usage.indexOf('\n'), "a generated usage line is one line: " + usage);
        assertTrue(usage.contains("statements_into_function|statements_to_callers"),
            "and the two long kinds are now adjacent where the hand-wrap used to break: "
                + usage);
        assertEquals(door.publishedKinds().size(), usage.split("\\|").length,
            "every routed kind appears exactly once, separated by pipes: " + usage);
    }

    @Test
    @DisplayName("a door that has not adopted the seam is untouched by it")
    void anUnadoptedDoorKeepsItsOwnText() {
        // The seam is opt-in per door, by design: each converts in the step that owns it.
        // Without this, "every door is assembled" could be believed of doors that are not.
        // M4 wires three — extract, inline, move — and apply_cleanup is deliberately not
        // among them; it adopts at M5 with the rest.
        ApplyCleanupTool notYet = new ApplyCleanupTool(() -> null, new RefactoringChangeCache());
        assertTrue(notYet.preamble().isEmpty(),
            "apply_cleanup has not adopted the description seam yet, so its regions are empty");
        assertTrue(notYet.getDescription().contains("USAGE:"),
            "and it still publishes its own hand-written description");
    }
}
