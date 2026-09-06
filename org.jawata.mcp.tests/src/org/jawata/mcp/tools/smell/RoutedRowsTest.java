package org.jawata.mcp.tools.smell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D5 — <b>THE ROUTING FLOOR, COMPUTED.</b>
 *
 * <p>Sprint 28d-rescue built 45 refactorings. Thirty of them have a detector: the spec's
 * inventory carries a <i>Finds it</i> column, and 45 built rows minus the 15 whose cell reads
 * "—" is where the number 30 comes from. D5 says every one of those thirty must be NAMED BY A
 * CURE, at any rank — a fix nothing can reach from the finding that reports its smell has not
 * been delivered to anybody.</p>
 *
 * <h2>Why the column is copied here, and what that copy is for</h2>
 *
 * <p>This looks like the hand-written list this sprint spent a stage deleting, and it is the
 * opposite: an EXTERNAL FACT, cited to its source, which nothing in this repository derives.
 * The spec is signed and lives in another repository; the cure table is ours. A test that read
 * the floor off the cure table would agree with it by construction and would go on agreeing
 * after a route was dropped — the tautology {@code EveryDeclaringKindRendersItsCureTest}
 * refuses in its own javadoc, and the same defence {@code EverySmellIsDetectedOrDeclinedTest}
 * makes for Fowler's 24.</p>
 *
 * <p>The column alone is not enough to join, and that is why the constant is a TRIPLE. It maps
 * a row to a SMELL; it says nothing about which operation the sprint built for that row, so a
 * two-column copy could not be checked against a cure table keyed by operation. The third
 * element is this plan's own selection, and pinning it means a renamed kind or a re-keyed
 * operation turns this test red instead of quietly passing on a row that no longer routes.</p>
 */
class RoutedRowsTest {

    /**
     * One detector-bearing row of the spec's inventory: its number, the smell whose <i>Finds
     * it</i> cell names it, and the operation key S8b step 9 routes it through.
     *
     * <p>A row the column gives TWO smells for appears once, under the smell the cure table
     * actually declares it on — D5 asks whether the row is reachable, not through how many
     * doors.</p>
     */
    private record Row(int number, String smell, String operation) {
    }

    /**
     * The thirty, transcribed from
     * {@code jawata-enterprise/docs/sprints/jawata-mcp/sprint-28d-rescue-fowler.md} at
     * {@code 7ffd516} — the inventory table's <i>Finds it</i> column, built rows only.
     *
     * <p>The fifteen built rows whose cell reads "—" are NOT here and are not a gap: 2, 7, 25,
     * 26, 29, 35, 41, 44, 45, 46, 47, 55, 62, 63, 65. Nothing reports their shape, which is a
     * detector question rather than a routing one, and each carries a written reason in
     * {@link CureCatalog}'s unrouted table.</p>
     */
    private static final List<Row> DETECTOR_BEARING_ROWS = List.of(
        new Row(4, "speculative_generality", "hierarchy kind=collapse_hierarchy"),
        new Row(5, "feature_envy", "extract kind=combine_functions"),
        new Row(8, "long_method", "refactor_to_pattern kind=decompose_conditional"),
        new Row(9, "encapsulation", "data kind=encapsulate_collection"),
        new Row(10, "encapsulation", "data kind=encapsulate_record"),
        new Row(16, "message_chains", "data kind=hide_delegate"),
        new Row(17, "lazy_class", "inline kind=class"),
        new Row(21, "long_parameter_list",
            "change_method_signature kind=introduce_parameter_object"),
        new Row(22, "temporary_field", "data kind=special_case"),
        new Row(23, "inappropriate_intimacy", "move kind=field"),
        new Row(24, "feature_envy", "move kind=method"),
        new Row(27, "duplicated_code", "change_method_signature kind=parameterize_function"),
        new Row(28, "long_parameter_list",
            "change_method_signature kind=preserve_whole_object"),
        new Row(34, "speculative_generality", "apply_cleanup kind=remove_dead_code"),
        new Row(36, "middle_man", "inline kind=middle_man"),
        new Row(37, "encapsulation", "data kind=remove_setting_method"),
        new Row(38, "lazy_class", "inline kind=subclass"),
        new Row(48, "long_method", "extract kind=function_to_command"),
        new Row(49, "duplicated_code", "extract kind=replace_inline_code"),
        new Row(50, "loops", "apply_cleanup kind=loop_to_pipeline"),
        new Row(52, "long_method", "apply_cleanup kind=guard_clauses"),
        new Row(53, "long_parameter_list",
            "change_method_signature kind=replace_parameter_with_query"),
        new Row(54, "primitive_obsession", "data kind=replace_primitive"),
        new Row(56, "refused_bequest", "hierarchy kind=replace_subclass_with_delegate"),
        new Row(57, "composition_over_inheritance",
            "hierarchy kind=replace_superclass_with_delegate"),
        new Row(58, "long_method", "extract kind=temp_to_query"),
        new Row(59, "type_code", "hierarchy kind=replace_type_code_with_subclasses"),
        new Row(60, "cqs", "apply_cleanup kind=return_modified_value"),
        new Row(61, "cqs", "change_method_signature kind=separate_query_from_modifier"),
        new Row(64, "divergent_change", "extract kind=split_phase"));

    /**
     * The floor D5 states, derived here rather than typed: 45 built rows less the 15 the
     * column marks "—".
     */
    private static final int FLOOR = 30;

    @Test
    @DisplayName("the constant IS the spec's column — 30 detector-bearing rows, none repeated")
    void theTranscribedColumnHasTheShapeTheSpecGivesIt() {
        // PROOF THE COPY IS THE RIGHT SIZE. Without this, dropping a row from the list below
        // would quietly lower the floor the next test measures against — a list that shrinks
        // to match its own answer is the failure mode this whole test exists to refuse.
        assertEquals(FLOOR, DETECTOR_BEARING_ROWS.size(),
            "45 built rows less the 15 whose 'Finds it' cell reads '—' is 30; this"
                + " transcription must have exactly that many rows");
        Set<Integer> numbers = new LinkedHashSet<>();
        List<Integer> repeated = new ArrayList<>();
        for (Row row : DETECTOR_BEARING_ROWS) {
            if (!numbers.add(row.number())) {
                repeated.add(row.number());
            }
        }
        assertEquals(List.of(), repeated,
            "each inventory row appears once — a row listed twice would count twice toward"
                + " the floor and hide a row that is missing");
    }

    @Test
    @DisplayName("every detector-bearing row is named by a cure of the smell that finds it")
    void everyDetectorBearingRowIsRouted() {
        Map<Integer, String> unrouted = new LinkedHashMap<>();
        Set<Integer> routed = new LinkedHashSet<>();
        for (Row row : DETECTOR_BEARING_ROWS) {
            List<String> declared = CureCatalog.curesFor(row.smell()).stream()
                .map(CureCatalog.Cure::recipe)
                .filter(java.util.Objects::nonNull)
                .toList();
            if (declared.contains(row.operation())) {
                routed.add(row.number());
            } else {
                unrouted.put(row.number(), "row " + row.number() + ": '" + row.smell()
                    + "' declares " + declared + " and none of them is '"
                    + row.operation() + "'");
            }
        }

        assertTrue(unrouted.isEmpty(),
            "D5: every row the inventory's 'Finds it' column gives a detector must be named"
                + " by a cure of that detector's smell, at any rank. These are built and"
                + " nothing points at them:\n  "
                + String.join("\n  ", unrouted.values()));
        assertEquals(FLOOR, routed.size(),
            "the count D5 measures, computed from the cure table rather than asserted");
    }
}
