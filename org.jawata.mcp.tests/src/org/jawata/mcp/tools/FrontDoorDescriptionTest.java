package org.jawata.mcp.tools;

import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("move's projected block loses no prose either, though its bullets were laid out differently")
    void moveProjectionLosesNoProse() {
        // A SECOND door, and deliberately one whose hand-alignment differed: two of move's
        // six bullets carried the kind name on a line of its own because the name is long.
        // If the projection quietly dropped or duplicated text when a bullet's first line was
        // empty, inline alone would not have shown it.
        MoveTool door = new MoveTool(() -> null, new RefactoringChangeCache());
        assertEquals(content(MoveTool.LEGACY_KIND_BLOCK),
            content(FrontDoorDescription.ASSEMBLER.kindBlockOf(door)),
            "every non-whitespace character of move's six bullets must survive the move onto"
                + " its delegates");
    }

    @Test
    @DisplayName("extract's eleven bullets project too, and its lead-in line survives the move")
    void extractProjectionLosesNoProse() {
        // The THIRD door, and the one with a region no delegate owns: its block opens with a
        // line about the coordinates that is true of every kind and is not a bullet. A pure
        // projection over delegates() would drop it silently, which is why the door keeps it
        // as kindBlockLeadIn() and the assembler puts it back.
        ExtractTool door = new ExtractTool(() -> null, new RefactoringChangeCache());
        String block = FrontDoorDescription.ASSEMBLER.kindBlockOf(door);

        assertTrue(block.startsWith("Kinds and their params (all ZERO-BASED coordinates):\n- "),
            "the lead-in must open the block and the first bullet must follow it directly,"
                + " with no blank line the hand-written block did not have: " + block);
        assertEquals(content(ExtractTool.LEGACY_KIND_BLOCK), content(block),
            "every non-whitespace character of extract's eleven bullets, and of the lead-in"
                + " above them, must survive the move onto its delegates");
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
    @DisplayName("refactor_to_pattern's eleven bullets survive PER KIND, though one changed place")
    void refactorToPatternProjectionLosesNoProsePerKind() {
        // Compared per kind and not in order, because this door's declared text change IS a
        // reorder: decompose_conditional was written third, beside compose_method, and is
        // eleventh in the routing table and in the published `kind` enum. The projection
        // makes all three agree. An ordered comparison would fail on that and say nothing
        // about whether any bullet's prose survived, which is the thing worth checking.
        RefactorToPatternTool door = new RefactorToPatternTool(() -> null, new RefactoringChangeCache());
        assertEquals(bullets(RefactorToPatternTool.LEGACY_KIND_BLOCK),
            bullets(FrontDoorDescription.ASSEMBLER.kindBlockOf(door)),
            "every kind's prose must survive the move onto its delegate, character for"
                + " non-whitespace character");
    }

    @Test
    @DisplayName("…and the reorder is real: decompose_conditional moved from third to last")
    void refactorToPatternBulletOrderIsNowTheRoutingOrder() {
        // The declared change, asserted rather than left in a commit message.
        RefactorToPatternTool door = new RefactorToPatternTool(() -> null, new RefactoringChangeCache());
        List<String> was = List.copyOf(bullets(RefactorToPatternTool.LEGACY_KIND_BLOCK).keySet());
        List<String> now = List.copyOf(
            bullets(FrontDoorDescription.ASSEMBLER.kindBlockOf(door)).keySet());

        assertEquals(2, was.indexOf("decompose_conditional"), "it was written third: " + was);
        assertEquals(10, now.indexOf("decompose_conditional"), "it is now last: " + now);
        assertEquals(door.publishedKinds(), now,
            "and the bullets are now in the order the door routes and publishes them, which"
                + " is what a reader comparing the kind enum against the bullets will find");
    }

    @Test
    @DisplayName("generate's seven bullets project in place")
    void generateProjectionLosesNoProse() {
        org.jawata.mcp.tools.codegen.GenerateTool door =
            new org.jawata.mcp.tools.codegen.GenerateTool(() -> null, new RefactoringChangeCache());
        assertEquals(content(org.jawata.mcp.tools.codegen.GenerateTool.LEGACY_KIND_BLOCK),
            content(FrontDoorDescription.ASSEMBLER.kindBlockOf(door)),
            "generate's bullets were already in routing order, so this one compares in order");
    }

    @Test
    @DisplayName("apply_cleanup's bullets stopped repeating their own kind name")
    void applyCleanupBulletsNoLongerNameTheirOwnKind() {
        // This door's block was ALREADY derived — from each rule's describe(), which opened
        // with the kind name padded by eye to a column. So the routing key and the prose
        // both carried the name, and a projection over the old text would have rendered
        // "- add_final — add_final           — mark parameters…". The rule now says only
        // what it does, which is the duplicate this stage removes rather than a layout
        // preference.
        ApplyCleanupTool door = new ApplyCleanupTool(() -> null, new RefactoringChangeCache());
        String block = FrontDoorDescription.ASSEMBLER.kindBlockOf(door);

        door.delegates().forEach((kind, delegate) -> {
            assertTrue(block.contains("- " + kind + " — "),
                kind + " must appear as its own bullet: " + block);
            assertFalse(delegate.kindSummary().strip().startsWith(kind),
                kind + "'s prose must not open with its own name — the routing key supplies"
                    + " it, and saying it twice is the copy being removed");
        });
        assertEquals(10, door.delegates().size(), "apply_cleanup routes ten rules");
    }

    @Test
    @DisplayName("apply_cleanup's usage is a SECTION, and all four call shapes survive")
    void applyCleanupUsageSectionSurvives() {
        // The one door whose usage is more than a line. It is carried by usageNote(), a
        // region, rather than by a branch in the assembler — which is the bargain of the
        // held collaborator: one algorithm for every door, and what differs is the parts.
        ApplyCleanupTool door = new ApplyCleanupTool(() -> null, new RefactoringChangeCache());
        String described = door.getDescription();

        assertTrue(described.contains("USAGE: apply_cleanup(kind=\"<add_final|"), described);
        for (String shape : List.of(
                "apply_cleanup(kind=\"<kind>\", filePath=\"path/to/File.java\")",
                "apply_cleanup(kind=\"<kind>\", filePath=..., line=N, column=M)",
                "apply_cleanup(kind=\"<kind>\", symbol=\"pkg.Type#member\")")) {
            assertTrue(described.contains(shape), "a call shape went missing: " + shape);
        }
        assertTrue(described.contains("that do: "
                + String.join(", ", ApplyCleanupTool.POSITION_REFUSING_KINDS) + "."),
            "and the refusing kinds are still named, still from the constant: " + described);
    }

    @Test
    @DisplayName("refactoring takes the DESCRIPTION seam and deliberately not the routing seam")
    void refactoringTakesOneSeamNotTwo() {
        RefactoringTool door = new RefactoringTool(() -> null, new RefactoringChangeCache());

        assertFalse(door instanceof KindedTool,
            "its verbs are lifecycle operations over a change some other door produced."
                + " Admitting it to KindedTool would publish apply and undo as things a cure"
                + " step may name — and would make the registry's name exclusion, which"
                + " Stage 9 retires in favour of this very instanceof, mean something else");
        assertEquals(FrontDoorDescription.ASSEMBLER.describe(door), door.getDescription(),
            "but it DOES route its description through the shared assembler");
        assertEquals("action", door.discriminator());
    }

    @Test
    @DisplayName("refactoring's usage line names its ACTIONS, not another door's plan kinds")
    void refactoringPublishesItsActionsAndNotThePlanKinds() {
        // A real defect the override closes. Tool#publishedKinds() walks the schema for
        // `kind` or `direction` and stops at the first it finds; this tool's schema declares
        // BOTH `action` — its discriminator — and `kind`, which is a parameter of ONE action.
        // Inherited, the usage line would have advertised refactor_to_pattern's six plannable
        // operations as this door's own.
        RefactoringTool door = new RefactoringTool(() -> null, new RefactoringChangeCache());

        assertEquals(
            List.of("apply", "undo", "inspect", "plan", "apply_plan", "inspect_plan", "undo_plan"),
            door.publishedKinds(), "the seven lifecycle verbs");
        String usage = FrontDoorDescription.ASSEMBLER.usageLine(door);
        assertTrue(usage.startsWith("USAGE: refactoring(action=\"<apply|undo|inspect|plan|"),
            usage);
        assertFalse(usage.contains("compose_method"),
            "compose_method is refactor_to_pattern's operation and is published by it: "
                + usage);
    }

    @Test
    @DisplayName("a door that has not adopted the seam is untouched by it")
    void anUnadoptedDoorKeepsItsOwnText() {
        // The seam is opt-in per door, by design: each converts in the step that owns it.
        // Without this, "every door is assembled" could be believed of doors that are not.
        // M5 converts seven; `data` adopts inside Stage 5 as it grows from one kind to ten,
        // because converting a door and then growing it does the work twice.
        DataTool notYet = new DataTool(() -> null, new RefactoringChangeCache());
        assertTrue(notYet.getDescription().contains("USAGE:"),
            "data still publishes its own hand-written description");
    }

    @Test
    @DisplayName("M5's discriminator: none of the seven doors publishes a usage line of its own")
    void noDoorWritesItsOwnUsageLine() {
        // THE CHECK THAT CANNOT PASS ON A NO-OP, and it took two tries to find one.
        //
        // The plan's wording is "no literal USAGE: line remains in the door's SOURCE TEXT",
        // read from the file rather than through find_string_literals — which walks
        // StringLiteral nodes and cannot see a text block, so it returns nothing for every
        // door before the work and after it. Run over these seven files, that check finds one
        // real hit: InlineTool.LEGACY_DESCRIPTION, a retained golden of the OLD description,
        // whose subject is history and which no published path reads. A file-text check
        // cannot tell that from a door that still writes its own line.
        //
        // So the subject is the PUBLISHED text instead, which is what the clause is actually
        // about. Before M5 three of these doors published a hand-written line that differed
        // from the generated one — extract's and generate's said kind="<kind>", refactoring's
        // said action="<action>", move's was wrapped across two lines — so this failed. A
        // door that writes one back publishes two, and fails again.
        for (FrontDoor door : sevenDoors()) {
            String described = door.getDescription();
            String generated =
                FrontDoorDescription.ASSEMBLER.usageLine(door) + door.usageNote();

            assertEquals(1, occurrences(described, "USAGE:"),
                door.getName() + " must publish exactly ONE usage line, and it must be the"
                    + " generated one:\n" + described);
            assertTrue(described.contains(generated),
                door.getName() + " must publish the GENERATED usage line.\n  expected: "
                    + generated + "\n  in: " + described);
        }
        // PROOF OF LIFE: an empty list would satisfy the loop.
        assertEquals(7, sevenDoors().size(),
            "M5 converts seven doors — the six that take both seams plus refactoring, which"
                + " takes the description seam only");
    }

    /** The seven doors M5 converts, in the order the plan's step table names them. */
    private static List<FrontDoor> sevenDoors() {
        RefactoringChangeCache cache = new RefactoringChangeCache();
        return List.of(
            new ExtractTool(() -> null, cache),
            new InlineTool(() -> null, cache),
            new MoveTool(() -> null, cache),
            new RefactorToPatternTool(() -> null, cache),
            new org.jawata.mcp.tools.codegen.GenerateTool(() -> null, cache),
            new ApplyCleanupTool(() -> null, cache),
            new RefactoringTool(() -> null, cache));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    /**
     * The block's bullets, keyed by kind, each with its whitespace removed.
     *
     * <p>Splitting on a line that STARTS with {@code "- "} works on both sides: the
     * hand-written blocks indent their continuation lines to a column, and the projection
     * indents them by two, so no continuation line can be mistaken for a bullet.</p>
     */
    private static Map<String, String> bullets(String block) {
        Map<String, String> byKind = new LinkedHashMap<>();
        for (String chunk : block.split("(?m)^- ")) {
            int dash = chunk.indexOf('—');
            if (chunk.isBlank() || dash < 0) {
                continue;
            }
            byKind.put(chunk.substring(0, dash).strip(), content(chunk.substring(dash + 1)));
        }
        return byKind;
    }
}
