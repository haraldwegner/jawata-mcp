package org.jawata.mcp.catalogue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.jawata.catalogue.CatalogueRow;
import org.jawata.catalogue.Refactorings;
import org.jawata.core.IJdtService;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractTool;
import org.jawata.mcp.tools.FrontDoor;
import org.jawata.mcp.tools.RefactoringDoors;

/**
 * WHAT THIS ENGINE DOES ABOUT EACH CATALOGUE ROW — the Java provider's own declaration.
 *
 * <h2>jawata-duplicate: this REPLACES the third field of {@code PerformedRefactoringCountTest}</h2>
 *
 * <p>That test holds {@code record Row(int number, String name, String operation)} and joins the
 * third field against the shipped doors itself. The first two fields moved to
 * {@link Refactorings} one commit ago, because they are Fowler's and no engine owns them. <b>The
 * third field is ours</b> — {@code extract kind=method} is a Java engine's spelling — and the
 * architecture says so in as many words: <i>"that is the join that must become per-provider, and
 * it is a change to the row's third field, not to the catalogue."</i></p>
 *
 * <p>C8's clause is that the engine's declaration <i>RECOMPUTES its own 62 of 66 rather than
 * being told it</i>. A count computed inside a test is not the engine declaring anything: it is
 * a test deriving a number the product cannot answer. {@link #performs} is that answer, and it is
 * computed from {@link #shippedOperations()} — what the doors actually publish — so a kind
 * renamed or dropped changes the number rather than leaving it true by assertion.</p>
 *
 * <h2>NOT_APPLICABLE is not silence, and a note is required either way</h2>
 *
 * <p>Four rows are declined, and the architecture's own box insists the distinction be carried:
 * {@code NOT_APPLICABLE ≠ NONE, a note required on both}. So a decline is a DECLARATION with a
 * reason rather than a missing entry — the reasons are the signed spec's, quoted from
 * {@code sprint-28d-rescue-fowler.md} rather than written here, because why the product declines
 * a published refactoring is a decision with his signature on it and not a sentence for this file
 * to invent.</p>
 *
 * <p>Every catalogue row must appear. A row with no declaration at all is the third state this
 * whole arrangement exists to forbid: from outside it is indistinguishable from having forgotten
 * it, which is the same argument {@code EverySmellIsDetectedOrDeclinedTest} makes for the
 * twenty-four smells.</p>
 *
 * <h2>RECORDED FOR C8: the DETECT axis is not here</h2>
 *
 * <p>The architecture's box names two axes per row — {@code DETECT yes/no · PERFORM yes/no}. This
 * class carries PERFORM over the refactoring lane, which is what C8's clause names. The DETECT
 * declaration for the twenty-four smells still lives in {@code FowlerDetectors.DECLINED} plus the
 * registered kinds, asserted on that side. Folding both axes into one coverage object is a design
 * move rather than a transcription, so it is raised at the checkpoint instead of taken
 * mid-stage.</p>
 */
public final class JavaEngineCoverage {

    /** What this engine claims about one row. */
    public enum Verdict {
        /** This engine performs the row, through the operation the declaration names. */
        PERFORMS,
        /**
         * This engine deliberately does not perform the row, and says why.
         *
         * <p>Distinct from having no declaration: that is an omission, this is a decision.</p>
         */
        NOT_APPLICABLE
    }

    /**
     * One row's declaration.
     *
     * @param key       the catalogue key this is about, e.g. {@code fowler:23}
     * @param verdict   performed, or deliberately not
     * @param operation the operation that performs it, or {@code null} when declined
     * @param note      why — required on a decline, optional on a performance
     */
    public record Declaration(String key, Verdict verdict, String operation, String note) {

        public Declaration {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("a declaration must say which row it is about");
            }
            if (verdict == Verdict.PERFORMS && (operation == null || operation.isBlank())) {
                throw new IllegalArgumentException(
                        "a PERFORMS declaration must name the operation that performs it: " + key);
            }
            if (verdict == Verdict.NOT_APPLICABLE) {
                if (operation != null) {
                    throw new IllegalArgumentException(
                            "a declined row names no operation — a note is what it carries: " + key);
                }
                // A BLANK NOTE WOULD SATISFY "a declination exists" AND TELL A READER NOTHING,
                // which is the state the whole NOT_APPLICABLE-is-not-NONE clause replaces.
                if (note == null || note.isBlank()) {
                    throw new IllegalArgumentException(
                            "a declined row must carry a reason a reader can weigh: " + key);
                }
            }
        }
    }

    private static final List<Declaration> DECLARATIONS = declare();

    private JavaEngineCoverage() {}

    /** This engine's claim about every row of the refactoring lane, in catalogue order. */
    public static List<Declaration> declarations() {
        return DECLARATIONS;
    }

    /**
     * How many of the catalogue's refactorings this engine performs — COMPUTED, not asserted.
     *
     * <p>A declaration claiming an operation the doors do not publish does NOT count. That is the
     * whole point of computing rather than declaring a number: a kind renamed or retired moves
     * this, where a literal would have stayed true about nothing.</p>
     */
    public static int performs(Set<String> shippedOperations) {
        int n = 0;
        for (Declaration d : DECLARATIONS) {
            if (d.verdict() == Verdict.PERFORMS && shippedOperations.contains(d.operation())) {
                n++;
            }
        }
        return n;
    }

    /**
     * The declarations that claim an operation this engine does not publish.
     *
     * <p>Empty is the healthy state. A non-empty answer names the refactoring that silently
     * stopped being performed, which is the fact a bare count cannot carry.</p>
     */
    public static List<String> claimsNotShipped(Set<String> shippedOperations) {
        List<String> out = new ArrayList<>();
        for (Declaration d : DECLARATIONS) {
            if (d.verdict() == Verdict.PERFORMS && !shippedOperations.contains(d.operation())) {
                CatalogueRow row = org.jawata.catalogue.Catalogue.byKey(d.key());
                out.add(d.key() + " " + (row == null ? "(no such row)" : row.name())
                        + " claims '" + d.operation() + "', which this engine does not publish");
            }
        }
        return out;
    }

    /**
     * Every operation this engine publishes, in the spelling a caller types.
     *
     * <p>Built from the doors the application itself registers from, so it cannot drift from what
     * ships. The standalone tools are included for the reason a C9 audit found the hard way: two
     * rows are performed by {@code rename_symbol}, which is a tool rather than a door kind, and
     * while this set was built from doors alone those two rows joined against a tool the set did
     * not contain — the assertion stayed green while the operation shipped to nobody.</p>
     *
     * <p>A null service is correct here: this asks what the doors PUBLISH, which is a fact about
     * their declared kinds and needs no project loaded.</p>
     */
    public static Set<String> shippedOperations() {
        Supplier<IJdtService> none = () -> null;
        RefactoringChangeCache cache = new RefactoringChangeCache();
        Set<String> ops = new LinkedHashSet<>();
        for (AbstractTool door : RefactoringDoors.all(none, cache)) {
            String discriminator = ((FrontDoor) door).discriminator();
            for (String kind : door.publishedKinds()) {
                ops.add(door.getName() + " " + discriminator + "=" + kind);
            }
        }
        for (AbstractTool tool : RefactoringDoors.standalone(none, cache, () -> null)) {
            ops.add(tool.getName());
        }
        return ops;
    }

    private static List<Declaration> declare() {
        List<Declaration> d = new ArrayList<>();
        performs(d, 1, "change_method_signature kind=change_signature");
        performs(d, 2, "data kind=reference_to_value");
        // The four declines carry the SIGNED SPEC'S OWN REASONS, quoted from
        // sprint-28d-rescue-fowler.md rather than composed here.
        declines(d, 3, "a repository / identity map; the key and where the repository lives are "
                + "design choices, not mechanics — so it ships as advice rather than as an "
                + "operation");
        performs(d, 4, "hierarchy direction=collapse_hierarchy");
        performs(d, 5, "extract kind=combine_functions");
        declines(d, 6, "Fowler's own text names Combine Functions into Class as the alternative; "
                + "in Java the transform is a copy-with-derived-fields function, which is row 5 "
                + "with a record");
        performs(d, 7, "apply_cleanup kind=consolidate_conditional");
        performs(d, 8, "refactor_to_pattern kind=decompose_conditional");
        performs(d, 9, "data kind=encapsulate_collection");
        performs(d, 10, "data kind=encapsulate_record");
        performs(d, 11, "data kind=encapsulate_field");
        performs(d, 12, "extract kind=class");
        performs(d, 13, "extract kind=method");
        performs(d, 14, "extract kind=superclass");
        performs(d, 15, "extract kind=variable");
        performs(d, 16, "data kind=hide_delegate");
        performs(d, 17, "inline kind=class");
        performs(d, 18, "inline kind=method");
        performs(d, 19, "inline kind=variable");
        declines(d, 20, "a one-line insertion with no compiler-checkable content; the tool would "
                + "add nothing the edit does not");
        performs(d, 21, "change_method_signature kind=introduce_parameter_object");
        performs(d, 22, "data kind=special_case");
        performs(d, 23, "move kind=field");
        performs(d, 24, "move kind=method");
        performs(d, 25, "move kind=statements_into_function");
        performs(d, 26, "move kind=statements_to_callers");
        performs(d, 27, "change_method_signature kind=parameterize_function");
        performs(d, 28, "change_method_signature kind=preserve_whole_object");
        performs(d, 29, "hierarchy direction=pull_up_constructor_body");
        // 30/31 and 32/33 share one operation each: pulling a field up and pulling a method up
        // are one engine operation over two catalogue rows. The count is over ROWS, which is why
        // 62 is not the number of distinct operations.
        performs(d, 30, "hierarchy direction=up");
        performs(d, 31, "hierarchy direction=up");
        performs(d, 32, "hierarchy direction=down");
        performs(d, 33, "hierarchy direction=down");
        performs(d, 34, "apply_cleanup kind=remove_dead_code");
        performs(d, 35, "change_method_signature kind=remove_flag_argument");
        performs(d, 36, "inline kind=middle_man");
        performs(d, 37, "data kind=remove_setting_method");
        performs(d, 38, "inline kind=subclass");
        // 39/40: a standalone TOOL rather than a door kind, which is why shippedOperations()
        // includes the standalone set — see its javadoc for what that omission once cost.
        performs(d, 39, "rename_symbol");
        performs(d, 40, "rename_symbol");
        performs(d, 41, "change_method_signature kind=replace_command_with_function");
        performs(d, 42, "refactor_to_pattern kind=replace_conditional_with_polymorphism");
        performs(d, 43, "refactor_to_pattern kind=replace_constructor_with_factory");
        performs(d, 44, "apply_cleanup kind=control_flag_to_break");
        performs(d, 45, "data kind=replace_derived_variable");
        performs(d, 46, "change_method_signature kind=replace_error_code_with_exception");
        performs(d, 47, "change_method_signature kind=replace_exception_with_precheck");
        performs(d, 48, "extract kind=function_to_command");
        performs(d, 49, "extract kind=replace_inline_code");
        performs(d, 50, "apply_cleanup kind=loop_to_pipeline");
        performs(d, 51, "extract kind=constant");
        performs(d, 52, "apply_cleanup kind=guard_clauses");
        performs(d, 53, "change_method_signature kind=replace_parameter_with_query");
        performs(d, 54, "data kind=replace_primitive");
        performs(d, 55, "change_method_signature kind=replace_query_with_parameter");
        performs(d, 56, "hierarchy direction=replace_subclass_with_delegate");
        performs(d, 57, "hierarchy direction=replace_superclass_with_delegate");
        performs(d, 58, "extract kind=temp_to_query");
        performs(d, 59, "hierarchy direction=replace_type_code_with_subclasses");
        performs(d, 60, "apply_cleanup kind=return_modified_value");
        performs(d, 61, "change_method_signature kind=separate_query_from_modifier");
        performs(d, 62, "apply_cleanup kind=slide_declaration");
        performs(d, 63, "apply_cleanup kind=split_loop");
        performs(d, 64, "extract kind=split_phase");
        performs(d, 65, "data kind=split_variable");
        declines(d, 66, "the new algorithm is authored content; the tool could only paste a body "
                + "and verify — which apply_plan already does for any step");
        return List.copyOf(d);
    }

    private static void performs(List<Declaration> into, int fowlerNumber, String operation) {
        into.add(new Declaration(key(fowlerNumber), Verdict.PERFORMS, operation, null));
    }

    private static void declines(List<Declaration> into, int fowlerNumber, String note) {
        into.add(new Declaration(key(fowlerNumber), Verdict.NOT_APPLICABLE, null, note));
    }

    private static String key(int fowlerNumber) {
        return Refactorings.FOWLER + CatalogueRow.SEPARATOR + fowlerNumber;
    }
}
