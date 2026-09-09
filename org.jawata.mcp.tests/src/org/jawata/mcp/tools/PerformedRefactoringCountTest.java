package org.jawata.mcp.tools;

import org.jawata.core.IJdtService;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SIXTY-TWO OF FOWLER'S SIXTY-SIX, RECOMPUTED FROM THE SHIPPED TOOL LIST.
 *
 * <p>This is Sprint 28d-rescue's headline figure and its C9 exit clause, quoted from the
 * signed spec: <i>"the count of refactorings jawata performs reaches 62 of 66, counted from
 * the shipped tool list, not asserted."</i></p>
 *
 * <h2>Why this did not exist until Stage 9</h2>
 *
 * <p>It was carried as C6's declared deviation #1 for four checkpoints, and the reason was
 * structural rather than an omission: <b>nothing in the code mapped a Fowler row to the kind
 * that performs it.</b> That mapping lived only in the spec's 90-row inventory, which is a
 * document. {@code Stage6ShippedCountTest}'s javadoc states the same thing about itself —
 * three hand-written per-tool integers are a contract check, not a recomputation — and says
 * the table has to exist IN CODE first. This class is that table.</p>
 *
 * <h2>What is external and what is computed — the whole soundness argument</h2>
 *
 * <p>The left column below is an EXTERNAL, HISTORICAL fact: Fowler's published catalogue,
 * 66 refactorings (refactoring.com, read 2026-09-02 and recorded in the spec at
 * {@code 7ffd516}). No object here owns it and nothing here can change it — the same
 * exemption {@code EverySmellIsDetectedOrDeclinedTest} makes for the twenty-four smells, and
 * for the same reason: a catalogue derived from our own kinds would agree with them by
 * construction and could never fail.</p>
 *
 * <p>The right column is OURS, and it is never checked against another hand-written list. It
 * is joined against the operations the product actually publishes, built from
 * {@link RefactoringDoors#all} — the same call {@code JawataApplication.registerTools()}
 * registers from, so a door that stops shipping stops shipping HERE too. <b>62 is not
 * written on either side; it is counted.</b></p>
 *
 * <h2>What makes it fail, and WHICH assertion does the work</h2>
 *
 * <p>A kind renamed or dropped removes its operation from the shipped set, so its row stops
 * being performed: the JOIN assertion fails and names the refactoring that silently stopped
 * being performed. That is the discriminating half. The count assertion beside it is a size
 * guard — given an empty unshipped list it is the catalogue's own 66 less its four declines,
 * so it fails when the TABLE loses a row rather than when the PRODUCT loses an operation.</p>
 *
 * <p><b>An earlier version of this paragraph claimed the count itself "falls below 62 —
 * naming the row", and that was false about the code below it</b>: the two assertions ran in
 * sequence, so the join threw first and the count was never observed below 62. A C9 auditor
 * measured it. They now run under {@code assertAll}, so a dropped kind reports both — but the
 * two remain different claims and this paragraph no longer merges them.</p>
 *
 * <h2>The four declined rows are a CLAIM, not a gap</h2>
 *
 * <p>Rows 3, 6, 20 and 66 carry {@code null}, which asserts that the spec declines them in
 * writing with Harald's signature — Change Value to Reference (a repository and an identity
 * map are design choices, not mechanics), Combine Functions into Transform, Introduce
 * Assertion, and Substitute Algorithm (the new algorithm is authored content). A row that
 * quietly became {@code null} to make the arithmetic work would move the declined count,
 * which is asserted separately.</p>
 */
class PerformedRefactoringCountTest {

    /** One row of Fowler's catalogue: the book's number and name, and what performs it. */
    private record Row(int number, String name, String operation) {}

    /**
     * Fowler's 66, in the catalogue's own order, each against the operation that performs
     * it or {@code null} where the spec declines it.
     *
     * <p>Two rows share {@code hierarchy direction=up} and two share
     * {@code direction=down} — Fowler counts Pull Up Field and Pull Up Method separately
     * and one operation performs both — so the performed COUNT is over rows, never over
     * distinct operations. Rows 39 and 40 name {@code rename_symbol}, which is a standalone
     * tool rather than a front-door kind; see {@link #shippedOperations}.</p>
     */
    private static final List<Row> FOWLER_CATALOGUE = catalogue();

    private static List<Row> catalogue() {
        List<Row> r = new ArrayList<>();
        r.add(new Row(1, "Change Function Declaration", "change_method_signature kind=change_signature"));
        r.add(new Row(2, "Change Reference to Value", "data kind=reference_to_value"));
        r.add(new Row(3, "Change Value to Reference", null));
        r.add(new Row(4, "Collapse Hierarchy", "hierarchy direction=collapse_hierarchy"));
        r.add(new Row(5, "Combine Functions into Class", "extract kind=combine_functions"));
        r.add(new Row(6, "Combine Functions into Transform", null));
        r.add(new Row(7, "Consolidate Conditional Expression", "apply_cleanup kind=consolidate_conditional"));
        r.add(new Row(8, "Decompose Conditional", "refactor_to_pattern kind=decompose_conditional"));
        r.add(new Row(9, "Encapsulate Collection", "data kind=encapsulate_collection"));
        r.add(new Row(10, "Encapsulate Record", "data kind=encapsulate_record"));
        r.add(new Row(11, "Encapsulate Variable", "data kind=encapsulate_field"));
        r.add(new Row(12, "Extract Class", "extract kind=class"));
        r.add(new Row(13, "Extract Function", "extract kind=method"));
        r.add(new Row(14, "Extract Superclass", "extract kind=superclass"));
        r.add(new Row(15, "Extract Variable", "extract kind=variable"));
        r.add(new Row(16, "Hide Delegate", "data kind=hide_delegate"));
        r.add(new Row(17, "Inline Class", "inline kind=class"));
        r.add(new Row(18, "Inline Function", "inline kind=method"));
        r.add(new Row(19, "Inline Variable", "inline kind=variable"));
        r.add(new Row(20, "Introduce Assertion", null));
        r.add(new Row(21, "Introduce Parameter Object", "change_method_signature kind=introduce_parameter_object"));
        r.add(new Row(22, "Introduce Special Case", "data kind=special_case"));
        r.add(new Row(23, "Move Field", "move kind=field"));
        r.add(new Row(24, "Move Function", "move kind=method"));
        r.add(new Row(25, "Move Statements into Function", "move kind=statements_into_function"));
        r.add(new Row(26, "Move Statements to Callers", "move kind=statements_to_callers"));
        r.add(new Row(27, "Parameterize Function", "change_method_signature kind=parameterize_function"));
        r.add(new Row(28, "Preserve Whole Object", "change_method_signature kind=preserve_whole_object"));
        r.add(new Row(29, "Pull Up Constructor Body", "hierarchy direction=pull_up_constructor_body"));
        r.add(new Row(30, "Pull Up Field", "hierarchy direction=up"));
        r.add(new Row(31, "Pull Up Method", "hierarchy direction=up"));
        r.add(new Row(32, "Push Down Field", "hierarchy direction=down"));
        r.add(new Row(33, "Push Down Method", "hierarchy direction=down"));
        r.add(new Row(34, "Remove Dead Code", "apply_cleanup kind=remove_dead_code"));
        r.add(new Row(35, "Remove Flag Argument", "change_method_signature kind=remove_flag_argument"));
        r.add(new Row(36, "Remove Middle Man", "inline kind=middle_man"));
        r.add(new Row(37, "Remove Setting Method", "data kind=remove_setting_method"));
        r.add(new Row(38, "Remove Subclass", "inline kind=subclass"));
        r.add(new Row(39, "Rename Field", "rename_symbol"));
        r.add(new Row(40, "Rename Variable", "rename_symbol"));
        r.add(new Row(41, "Replace Command with Function", "change_method_signature kind=replace_command_with_function"));
        r.add(new Row(42, "Replace Conditional with Polymorphism", "refactor_to_pattern kind=replace_conditional_with_polymorphism"));
        r.add(new Row(43, "Replace Constructor with Factory Function", "refactor_to_pattern kind=replace_constructor_with_factory"));
        r.add(new Row(44, "Replace Control Flag with Break", "apply_cleanup kind=control_flag_to_break"));
        r.add(new Row(45, "Replace Derived Variable with Query", "data kind=replace_derived_variable"));
        r.add(new Row(46, "Replace Error Code with Exception", "change_method_signature kind=replace_error_code_with_exception"));
        r.add(new Row(47, "Replace Exception with Precheck", "change_method_signature kind=replace_exception_with_precheck"));
        r.add(new Row(48, "Replace Function with Command", "extract kind=function_to_command"));
        r.add(new Row(49, "Replace Inline Code with Function Call", "extract kind=replace_inline_code"));
        r.add(new Row(50, "Replace Loop with Pipeline", "apply_cleanup kind=loop_to_pipeline"));
        r.add(new Row(51, "Replace Magic Literal", "extract kind=constant"));
        r.add(new Row(52, "Replace Nested Conditional with Guard Clauses", "apply_cleanup kind=guard_clauses"));
        r.add(new Row(53, "Replace Parameter with Query", "change_method_signature kind=replace_parameter_with_query"));
        r.add(new Row(54, "Replace Primitive with Object", "data kind=replace_primitive"));
        r.add(new Row(55, "Replace Query with Parameter", "change_method_signature kind=replace_query_with_parameter"));
        r.add(new Row(56, "Replace Subclass with Delegate", "hierarchy direction=replace_subclass_with_delegate"));
        r.add(new Row(57, "Replace Superclass with Delegate", "hierarchy direction=replace_superclass_with_delegate"));
        r.add(new Row(58, "Replace Temp with Query", "extract kind=temp_to_query"));
        r.add(new Row(59, "Replace Type Code with Subclasses", "hierarchy direction=replace_type_code_with_subclasses"));
        r.add(new Row(60, "Return Modified Value", "apply_cleanup kind=return_modified_value"));
        r.add(new Row(61, "Separate Query from Modifier", "change_method_signature kind=separate_query_from_modifier"));
        r.add(new Row(62, "Slide Statements", "apply_cleanup kind=slide_declaration"));
        r.add(new Row(63, "Split Loop", "apply_cleanup kind=split_loop"));
        r.add(new Row(64, "Split Phase", "extract kind=split_phase"));
        r.add(new Row(65, "Split Variable", "data kind=split_variable"));
        r.add(new Row(66, "Substitute Algorithm", null));
        return List.copyOf(r);
    }

    /**
     * Everything the product publishes that can perform a refactoring, in the spelling a
     * caller would type.
     *
     * <p><b>BOTH halves are DERIVED, and the first version of this method got that wrong.</b>
     * The door half comes from {@link RefactoringDoors#all}, the same call the application
     * registers from, and each door is asked for its own discriminator so {@code hierarchy}
     * renders {@code direction=} rather than a guess. The standalone half — the tools that
     * perform a refactoring without dispatching on a discriminator, of which exactly one
     * carries a Fowler row ({@code rename_symbol}, rows 39 and 40) — now comes from
     * {@link RefactoringDoors#standalone}, which the application registers from too.</p>
     *
     * <p><b>It used to say {@code new RenameSymbolTool(...)}, and a C9 architect watch measured
     * what that cost.</b> Sixty rows were counted from the shipped list and two were counted
     * from a tool this test CONSTRUCTED — so deleting the registration would have left the test
     * green while {@code rename_symbol} shipped to nobody. C9's clause is "counted from the
     * shipped tool list", and for two rows it was not. This is the vacuity shape the whole
     * checkpoint is about, in the test written to close a deviation about it, and no gate could
     * have caught it: the vacuous half passes.</p>
     */
    private static Set<String> shippedOperations() {
        Supplier<IJdtService> none = () -> null;
        RefactoringChangeCache cache = new RefactoringChangeCache();
        Set<String> ops = new LinkedHashSet<>();
        for (AbstractTool door : RefactoringDoors.all(none, cache)) {
            String discriminator = ((FrontDoor) door).discriminator();
            for (String kind : door.publishedKinds()) {
                ops.add(door.getName() + " " + discriminator + "=" + kind);
            }
        }
        for (AbstractTool tool : RefactoringDoors.standalone(none, cache)) {
            ops.add(tool.getName());
        }
        return ops;
    }

    @Test
    @DisplayName("the catalogue is the book's, not ours: exactly sixty-six, numbered once each")
    void theBookHasSixtySix() {
        // NOT a count of our own lists. This pins the EXTERNAL fact, so it fails if someone
        // adds a row for a refactoring the catalogue does not have, or drops one it does.
        assertEquals(66, FOWLER_CATALOGUE.size(),
            "Fowler's published catalogue is 66 refactorings (refactoring.com, read"
                + " 2026-09-02, recorded in the sprint spec at 7ffd516)");

        Map<Integer, String> byNumber = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        for (Row row : FOWLER_CATALOGUE) {
            String previous = byNumber.put(row.number(), row.name());
            if (previous != null) {
                duplicates.add(row.number() + " is both '" + previous + "' and '" + row.name() + "'");
            }
        }
        assertTrue(duplicates.isEmpty(),
            "a row number appears twice, so the table holds fewer refactorings than it"
                + " counts and the recomputation below is over the wrong population: "
                + duplicates);

        List<Integer> missing = new ArrayList<>();
        for (int n = 1; n <= 66; n++) {
            if (!byNumber.containsKey(n)) {
                missing.add(n);
            }
        }
        assertTrue(missing.isEmpty(),
            "the numbering is the spec inventory's and must be 1..66 with no holes: "
                + missing);

        long declined = FOWLER_CATALOGUE.stream().filter(row -> row.operation() == null).count();
        assertEquals(4, declined,
            "the spec declines exactly four with Harald's signature — rows 3, 6, 20 and 66."
                + " This number is allowed to move, but it moves in the same change that"
                + " adds or removes a declination, which is the whole of what the stale-count"
                + " findings in this sprint were about");
    }

    @Test
    @DisplayName("62 of 66, COUNTED from the shipped tool list rather than asserted")
    void theHeadlineCountIsRecomputed() {
        Set<String> shipped = shippedOperations();

        // PROOF OF LIFE. With an empty or tiny operation set every row below reads as not
        // performed, the count is zero, and the failure would look like a missing row rather
        // than a broken join. The floor is deliberately well under the real surface.
        assertTrue(shipped.size() >= 40,
            "the product must publish a real operation surface here, or this whole test is"
                + " measuring a construction failure: " + shipped);

        List<String> notShipped = new ArrayList<>();
        int performed = 0;
        for (Row row : FOWLER_CATALOGUE) {
            if (row.operation() == null) {
                continue;                       // declined in writing; see the size test
            }
            if (shipped.contains(row.operation())) {
                performed++;
            } else {
                notShipped.add("row " + row.number() + " " + row.name()
                    + " claims '" + row.operation() + "', which the product does not publish");
            }
        }

        final int counted = performed;

        // BOTH UNDER assertAll, AND THAT IS A REPAIR RATHER THAN A STYLE CHOICE. They ran in
        // sequence, so the join assertion threw first and `performed` was never OBSERVED below
        // 62 — which made this class's own javadoc ("the count falls below 62 — naming the
        // row") false about the code four lines under it. A C9 auditor found it. Under
        // assertAll a dropped kind reports BOTH: which refactoring stopped being performed,
        // and what the count became. Sprint 28d-rescue learned this at row 21 and the lesson
        // did not reach here.
        org.junit.jupiter.api.Assertions.assertAll(
            () -> assertTrue(notShipped.isEmpty(),
                "every row above claims an operation, and each claim is joined against what"
                    + " the product ACTUALLY publishes — so a kind renamed or dropped shows up"
                    + " here naming the refactoring it silently stopped performing: "
                    + notShipped),
            () -> assertEquals(62, counted,
                "the sprint's headline figure, and C9's clause: jawata performs 62 of Fowler's"
                    + " 66. Counted " + counted + " of " + FOWLER_CATALOGUE.size() + " over "
                    + shipped.size() + " shipped operations."
                    + "\n  WHICH HALF OF THIS IS THE DISCRIMINATOR, stated because an earlier"
                    + " version of this message overstated it: the assertion that carries the"
                    + " weight is the join above — it is what a renamed or dropped kind fails,"
                    + " and it names the row. THIS number is a size guard: given an empty"
                    + " unshipped list it is the catalogue's own 66 less its four declines, so"
                    + " it fails when the TABLE loses a row rather than when the PRODUCT loses"
                    + " an operation. Both are worth failing on; they are not the same claim."));
    }

    /**
     * C9's PER-DOOR clause, over all eight doors it names — five of which nothing gated.
     *
     * <p>The plan's C9 exit assigns a kind count per tool and says why a ceiling would not do:
     * it <i>"would pass a tool that finished two kinds short"</i>. {@code Stage6ShippedCountTest}
     * pins three of them, because Stage 6 owned three doors. <b>A C9 auditor searched every
     * test reading {@code publishedKinds()} and found the other five pinned nowhere</b> —
     * {@code apply_cleanup}, {@code change_method_signature}, {@code hierarchy}, {@code data}
     * and {@code refactor_to_pattern}. It read the delegate lists and confirmed all five are
     * CORRECT, so this was a gate gap rather than a defect; but C9's own sentence condemns an
     * absent gate for the same reason it condemns a ceiling, and one is what stood.</p>
     *
     * <p>The eight numbers are the PLAN's, fixed before the work started, which is what makes
     * writing them here legitimate rather than the hand-written-list defect this sprint keeps
     * finding: the left side is a contract we were held to and the right side is read off the
     * shipped doors. {@code generate} is the ninth door and C9 assigns it no count, so it is
     * asserted as PRESENT and not as a number — inventing one would be writing a contract
     * rather than checking one.</p>
     */
    @Test
    @DisplayName("all EIGHT doors publish exactly the kind count C9 assigns — not a ceiling")
    void everyDoorPublishesItsAssignedKindCount() {
        Map<String, Integer> assigned = new LinkedHashMap<>();
        assigned.put("extract", 11);
        assigned.put("inline", 5);
        assigned.put("move", 6);
        assigned.put("apply_cleanup", 10);
        assigned.put("change_method_signature", 11);
        assigned.put("hierarchy", 7);
        // ELEVEN since mcp#63: Stage 5's ten plus add_record_component, the operation
        // change_method_signature refuses and points here.
        assigned.put("data", 11);
        assigned.put("refactor_to_pattern", 11);

        Map<String, Integer> published = new LinkedHashMap<>();
        for (AbstractTool door : RefactoringDoors.all(() -> null, new RefactoringChangeCache())) {
            published.put(door.getName(), door.publishedKinds().size());
        }

        assertTrue(published.containsKey("generate"),
            "the ninth door must still ship even though C9 assigns it no count; without this"
                + " it could vanish and the eight below would not notice: " + published.keySet());

        Map<String, Integer> counted = new LinkedHashMap<>();
        assigned.keySet().forEach(door -> counted.put(door, published.get(door)));
        assertEquals(assigned, counted,
            "C9 names a count per door and rejects a ceiling, because a ceiling 'would pass a"
                + " tool that finished two kinds short'. These are read off the doors the"
                + " application registers from. Published: " + published);
    }
}
