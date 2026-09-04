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

    @Test
    @DisplayName("the assembled description reproduces the door's previous text, byte for byte")
    void theAssemblyIsFaithful() {
        assertEquals(InlineTool.LEGACY_DESCRIPTION,
            FrontDoorDescription.ASSEMBLER.describe(inline()),
            "M4 MOVES the description algorithm; it does not rewrite the published contract."
                + " A difference here is a change to what every client reads.");
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
