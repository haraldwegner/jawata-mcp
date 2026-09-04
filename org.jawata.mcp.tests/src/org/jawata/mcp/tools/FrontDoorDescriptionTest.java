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

    /**
     * INLINE'S PUBLISHED DESCRIPTION AS IT STOOD BEFORE THE SEAM — the golden.
     *
     * <p>It pins the prose across a comparison that is deliberately weak. M5 replaced
     * per-door hand-alignment with one uniform bullet rule, so byte-identity became the
     * wrong question — it would pin text the door no longer publishes — and what is left is
     * a whitespace-insensitive comparison. Without a golden, "the assembler produces a
     * description" would be true of any description at all.</p>
     *
     * <p><b>It lives here rather than on the door, and that is C6a's doing.</b> It sat as a
     * constant on {@code InlineTool} until the checkpoint's clause was read literally: no
     * door may retain a literal {@code USAGE:} line in its own source. This golden is the
     * whole OLD description, usage line included, so it was the last one left in those seven
     * files — and it was test data in a shipped class, read from exactly one place. Moving
     * it satisfies the clause as written and takes the constant out of production at the
     * same time.</p>
     */
    private static final String INLINE_LEGACY_DESCRIPTION = """
            Inline a method, a local variable, a whole class, a subclass into its
            parent, or a middle man's forwarding (behaviour-preserving, reversible).

            USAGE: inline(kind="<method|variable|class|subclass|middle_man>", filePath=..., line=..., column=...)

            - method   — inline all call sites of the method at the position.
            - variable — replace uses of the local variable at the position with its initializer.
            - class    — fold a class into the SINGLE class that uses it, then delete it.
                         Refuses when more than one class references it, when it has
                         subtypes, when the user holds none or several fields of its
                         type, when that field is assigned outside its own initializer,
                         when it has a constructor with a body, or when a member name
                         would collide. Each refusal names which. (find_quality_issue
                         kind=lazy_class locates candidates.)
            - subclass — fold a subclass that carries NO DISTINCTION into its parent:
                         its members move up, every reference to it becomes a reference
                         to the parent, and it is deleted. Refuses when the subclass
                         actually distinguishes something — it overrides a parent
                         method, an instanceof or a cast names its type, or its
                         constructor fixes an argument instead of forwarding — because
                         replacing a distinction with a field is a design decision.
                         Also refuses a subclass with subtypes (that is Collapse
                         Hierarchy), an abstract parent, a parent outside this
                         workspace, and a colliding member name.
            - middle_man — stop a class forwarding: every method whose whole body is
                         one call on one of its own fields, passing its parameters
                         through unchanged, is deleted and its call sites become
                         `middleMan.<accessor>().method(args)`. The accessor is
                         generated if the class has none — that exposure IS the
                         refactoring, and the summary says it happened. Refuses a class
                         with no forwarder at all. A class forwarding to SEVERAL
                         fields is NOT refused — name the one to remove with
                         `delegateField` and repeat; the fork's own GiantController is
                         why the old blanket refusal was wrong. A method that
                         transforms the result is left alone: that is behaviour, not
                         forwarding. (find_quality_issue kind=middle_man finds them.)

            IMPORTANT: ZERO-BASED coordinates. Applies by default; returns
            filesModified/diff/undoChangeId/summary. Pass auto_apply=false to stage only.

            Requires load_project to be called first.
            """;

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
        assertEquals(content(INLINE_LEGACY_DESCRIPTION),
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
        // C6a asks for a FILE-TEXT check — "no literal USAGE: line remains in the door's
        // SOURCE TEXT" — read from the file rather than through find_string_literals, which
        // walks StringLiteral nodes and cannot see a text block, so it returns nothing for
        // every door before the work and after it. That check now passes over the seven door
        // sources; the last literal in them was the golden of inline's OLD description, which
        // C6a moved into this file, where its only reader always was.
        //
        // THIS TEST IS THE OTHER HALF, and it is the stronger one. A file-text check reads
        // source; this reads what a CLIENT receives. Before M5 three of these doors published
        // a hand-written line differing from the generated one — extract's and generate's
        // said kind="<kind>", refactoring's said action="<action>", move's was wrapped across
        // two lines — so this failed then. A door that writes one back publishes two, and
        // fails again. Neither check subsumes the other: source could be clean while the
        // published text was assembled wrongly, and vice versa.
        for (FrontDoor door : sevenDoors()) {
            String described = door.getDescription();
            String generated =
                FrontDoorDescription.ASSEMBLER.usageLine(door) + door.usageNote();

            // THE WHOLE DESCRIPTION, not just its usage line — added at C6a because an audit
            // read the exit clause ("its description is assembled by the shared
            // FrontDoorDescription — asserted per door") against what was actually asserted
            // and found the strict form on two doors of seven. The usage-line checks below
            // are what M5(b) needs and they are kept, but a door could satisfy them while
            // writing the rest of its description itself.
            //
            // A REGRESSION LOCK, LABELLED ONE, because a second audit refused the checkpoint
            // over it and the refusal was half right. It cannot fail against today's code:
            // all seven getDescription() bodies are the single expression
            // `return FrontDoorDescription.ASSEMBLER.describe(this);`, so both sides of this
            // equality evaluate the same call. The auditor read that as the vacuous shape M6
            // deletes elsewhere.
            //
            // IT IS NOT THAT SHAPE, and the difference is what makes one deletable and this
            // one worth keeping. M6's enum assertion compared the schema's enum with
            // delegates().keySet() — but the schema PUTS publishedKinds() there and KindedTool
            // DEFINES publishedKinds() AS that key set, so the two sides are one expression a
            // definitional step apart, inside one interface whose javadoc tells implementors
            // never to override it. Nothing a door can do separates them. Here the subject is
            // getDescription(), which is Tool's own method and the place every tool in the
            // codebase writes its own text; these seven forwarding to the assembler is the
            // exception this stage created, not a definition, and it is one edit from being
            // undone.
            //
            // PROVED BY MUTATION rather than argued (C6a): inline's getDescription() made to
            // return a literal of its own fails THIS assertion first, and theDoorActuallyUsesIt
            // with it — 18 of 20 green, those two red. That is the state all seven doors were
            // in before M5, which is why the lock is worth its line.
            assertEquals(FrontDoorDescription.ASSEMBLER.describe(door), described,
                door.getName() + " must ROUTE its whole description through the shared"
                    + " assembler, not merely happen to carry the usage line the assembler"
                    + " would have produced");

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

    // ------------------------------------------------------------------
    // THE ASSEMBLY ITSELF, on a fake door — slot order, separators, the empty
    // routing table, and idempotence
    // ------------------------------------------------------------------
    //
    // Every test above asks a REAL door whether its text survived the move, which is the
    // question that mattered while the doors were converting. None of them pins the
    // ASSEMBLY: what order the regions come in, what separates them, and what happens at
    // the edges. A real door cannot answer those cleanly — its regions are long prose, and
    // `data`'s empty routing table is a state that disappears in Stage 5. So the four below
    // use a door built for the purpose, with parts short enough that the layout is the only
    // thing the assertion can be about.

    /** A door whose every region is a short marker, so only the assembly can fail a test. */
    private record FakeDelegate(String kindName, String kindSummary) implements KindDelegate {
        @Override
        public Map<String, Object> parameterSchema() {
            return Map.of();
        }
    }

    private static final class FakeDoor implements KindedTool {
        private final Map<String, KindDelegate> routed;

        FakeDoor(Map<String, KindDelegate> routed) {
            this.routed = routed;
        }

        @Override
        public String getName() {
            return "fake";
        }

        @Override
        public String discriminator() {
            return "flavour";
        }

        @Override
        public Map<String, KindDelegate> delegates() {
            return routed;
        }

        @Override
        public String preamble() {
            return "PREAMBLE";
        }

        @Override
        public String usageTail() {
            return ", TAIL";
        }

        @Override
        public String footer() {
            return "FOOTER";
        }

        @Override
        public String getDescription() {
            return FrontDoorDescription.ASSEMBLER.describe(this);
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return Map.of();
        }

        @Override
        public org.jawata.mcp.models.ToolResponse execute(
                com.fasterxml.jackson.databind.JsonNode arguments) {
            throw new UnsupportedOperationException("a fake door is never executed");
        }
    }

    private static FakeDoor fakeDoorWith(KindDelegate... delegates) {
        Map<String, KindDelegate> routed = new LinkedHashMap<>();
        for (KindDelegate delegate : delegates) {
            routed.put(delegate.kindName(), delegate);
        }
        return new FakeDoor(routed);
    }

    @Test
    @DisplayName("the four regions come in order, separated by exactly one blank line each")
    void theSlotOrderAndSeparatorsBelongToTheAssembler() {
        String described = FrontDoorDescription.ASSEMBLER.describe(fakeDoorWith(
            new FakeDelegate("first", "ONE"), new FakeDelegate("second", "TWO")));

        assertEquals("""
            PREAMBLE

            USAGE: fake(flavour="<first|second>", TAIL)

            - first — ONE
            - second — TWO

            FOOTER""", described,
            "the separation is the ASSEMBLER's and not each part's. A part carrying its own"
                + " leading or trailing blank line would make the spacing a property of"
                + " whichever door was written last, which is how four descriptions came to"
                + " be spaced three different ways");
    }

    @Test
    @DisplayName("a door with an EMPTY routing table still assembles, and says nothing about kinds")
    void anEmptyRoutingTableIsNotACrash() {
        // NO SHIPPED DOOR IS IN THIS STATE, and the earlier version of this comment said one
        // was — it called this "`data`'s literal state until Stage 5". Measured: DataTool
        // extends AbstractRefactoringTool and implements neither seam interface, so it is not
        // a FrontDoor at all and has no routing table to be empty. What the case actually
        // pins is the assembler's own boundary: asked to describe a door that routes nothing,
        // it must not throw and must not invent a bullet. That boundary is reached the moment
        // a door implements KindedTool before its delegates exist, which is how each of the
        // three remaining lanes starts.
        FakeDoor empty = fakeDoorWith();

        assertEquals(List.of(), empty.publishedKinds());
        assertEquals("", FrontDoorDescription.ASSEMBLER.kindBlockOf(empty),
            "no delegates means no bullets — not a placeholder, and not an exception");
        assertTrue(FrontDoorDescription.ASSEMBLER.describe(empty).contains("flavour=\"<>\""),
            "the usage line degenerates honestly rather than pretending to a kind list: "
                + FrontDoorDescription.ASSEMBLER.describe(empty));
    }

    @Test
    @DisplayName("a multi-line summary keeps its continuation lines attached to its bullet")
    void aMultiLineSummaryIsIndentedUnderItsBullet() {
        String block = FrontDoorDescription.ASSEMBLER.kindBlockOf(fakeDoorWith(
            new FakeDelegate("wordy", "LINE ONE\nLINE TWO"),
            new FakeDelegate("terse", "ONLY")));

        assertEquals("""
            - wordy — LINE ONE
              LINE TWO
            - terse — ONLY""", block,
            "a continuation line is indented so it reads as part of its bullet rather than"
                + " as a new one; the next bullet still starts at column zero");
    }

    @Test
    @DisplayName("assembling twice gives the same text — the assembler holds no state")
    void assemblyIsIdempotent() {
        // It is a single shared instance every door calls, so state on it would leak from
        // one door's description into the next one's.
        FakeDoor door = fakeDoorWith(
            new FakeDelegate("first", "ONE"), new FakeDelegate("second", "TWO"));

        assertEquals(FrontDoorDescription.ASSEMBLER.describe(door),
            FrontDoorDescription.ASSEMBLER.describe(door));
        // AND ACROSS DOORS, which is the leak that would actually happen: describe a
        // different door in between and the first one's text must be unchanged.
        String before = FrontDoorDescription.ASSEMBLER.describe(door);
        FrontDoorDescription.ASSEMBLER.describe(new InlineTool(() -> null, new RefactoringChangeCache()));
        assertEquals(before, FrontDoorDescription.ASSEMBLER.describe(door),
            "one door's assembly must not change another's");
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
