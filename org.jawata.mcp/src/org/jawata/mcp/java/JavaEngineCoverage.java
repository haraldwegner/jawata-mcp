package org.jawata.mcp.java;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.jawata.catalogue.CatalogueRow;
import org.jawata.catalogue.Refactorings;
import org.jawata.core.IJdtService;
import org.jawata.mcp.change.RefactoringChangeCache;
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
    public static int performs() {
        return performs(shippedOperations());
    }

    /**
     * The same, against a shipped set the CALLER supplies — for a test that varies it.
     *
     * <p><b>A caller that fetches the set and hands it straight back is the hazard, and this
     * class's own javadoc records it having already fired once:</b> a doors-only set missing
     * {@code rename_symbol} made the assertion stay green while the operation shipped to nobody.
     * `publishInto` did exactly that fetch-then-pass, which put the hazard on a PRODUCTION path
     * where it had only been in a test — an architect watch priced that and it was not priced
     * when it landed. Production now calls {@link #performs()} and {@link #claimsNotShipped()},
     * which ask this class for the set it already knows how to build; the overloads taking one
     * stay for the tests whose whole job is to vary it. Same shape as
     * {@code CureTier.derive(String)} beside {@code derive(String, List)} one package over.</p>
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
    public static List<String> claimsNotShipped() {
        return claimsNotShipped(shippedOperations());
    }

    /** The same, against a shipped set the CALLER supplies — see {@link #performs(Set)}. */
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

    /**
     * VERIFY THIS ENGINE'S COVERAGE AGAINST THE DOORS THAT ACTUALLY SHIP, and refuse to boot if
     * the two disagree.
     *
     * <p>It runs at the same moment and for the same reason as
     * {@code CureCatalog.validateAgainst}: after every tool has registered, which is the first
     * point at which "does anything back this claim?" has a true answer.</p>
     *
     * <p><b>IT WAS CALLED {@code publishInto(OperationRegistry)} AND IT PUBLISHED NOTHING.</b>
     * The architecture's §4-B asks that <i>"at registration the declaration is published into
     * the existing {@code OperationRegistry} … and gains the row key as an index"</i>, and the
     * first version wrote that index. An architect watch then measured that NOTHING READ IT —
     * both accessors at zero references, production and test — so the registry argument was
     * dead, the name described a thing that did not happen, and the index was state nobody
     * could reach. All three are gone.</p>
     *
     * <p><b>§4-B's CONSUMER, named here because this is where the writer lived.</b> The
     * architecture names it itself: <i>"the cure table's {@code recipe} field stops being a
     * global plan kind and becomes a row key that each provider's coverage resolves"</i> — and
     * {@code CureCatalog}, {@code CureLookup} and {@code CureTier} already resolve operations
     * through {@code OperationRegistry}. The first cure resolved through a row key brings the
     * index back with a reader whose failure an existing test can see.</p>
     *
     * <p>THIS PARAGRAPH IS HERE BECAUSE IT WENT MISSING ONCE. The sentence above used to point
     * at {@code OperationRegistry}'s javadoc for it; restoring that class's own {@code Snapshot}
     * documentation — itself a repair — deleted the paragraph being pointed at, leaving the two
     * files pointing at each other and the consumer named nowhere. A round-5 audit caught it,
     * which made it the FOURTH instance of one shape inside the fold of the first three: a
     * sentence that points at text the same commit changed, in a paragraph the commit did not
     * open. A cross-file pointer is a claim; this one is now a statement instead.</p>
     *
     * <p>So what this method does is narrower than its first name claimed, and it is the half
     * that was always real: it VERIFIES, and the verification is what dissolves the closed
     * test-only loop. §4-B's index is owed, not delivered.</p>
     *
     * <p><b>Why this method exists at all, stated plainly.</b> Without it the whole catalogue
     * was production CODE and not production BEHAVIOUR — nine references, every one in its own
     * test. Three instruments said so independently: the architect seat ranked it first, a
     * fresh-context audit raised it, and this repository's own unwired gate FAILED the suite
     * naming seven hollow members. The gate offered three remedies — wire it, delete it, or
     * baseline it with a reason — and baselining would have been the v3.4.0 shape, where
     * semantic recall shipped inert because the tests did the wiring.</p>
     *
     * <p>IT THROWS RATHER THAN LOGS, on the cure table's precedent. A coverage declaration
     * that names an operation nothing publishes is the same class of defect as a cure step
     * nothing backs: serving it would mean telling a caller to run something the front door
     * refuses. Boot failing loudly is the honest answer.</p>
     *
     * @return how many catalogue rows this engine actually performs — computed from what the
     *         doors publish, so a caller logs a number it did not invent. This is the figure
     *         the product previously could not state about itself: the sprint's headline
     *         "62 of 66" existed only inside a test.
     */
    public static int verifyAgainstShippedDoors() {
        // (1) A DECLARATION MAY NOT CLAIM AN OPERATION THIS ENGINE DOES NOT PUBLISH.
        // THROUGH THE NO-ARG FORM: this method used to fetch the shipped set and hand it to both
        // callees, which is the fetch-then-pass this class's javadoc records as having already
        // shipped a false green once. It belongs in a test that varies the set, not on the boot
        // path.
        List<String> unshipped = claimsNotShipped();
        if (!unshipped.isEmpty()) {
            throw new IllegalStateException(
                    "this engine's coverage declaration claims operations it does not publish, "
                            + "so a caller resolving one of these rows would be sent at a door "
                            + "that refuses: " + unshipped);
        }

        // (2) EVERY ROW OF THE LANE MUST BE DECLARED — performed or declined, never silent.
        // The third state is what the architecture forbids, and from outside it is
        // indistinguishable from having forgotten the row.
        // THROUGH THE ACCESSOR, not the private field. The unwired gate was right to call
        // `declarations()` hollow while this method reached past it: an accessor whose only
        // caller is a test is not published API, it is a test fixture with public visibility.
        Set<String> declared = new LinkedHashSet<>();
        for (Declaration d : declarations()) {
            declared.add(d.key());
        }
        // THE LANE RENDERS ITS OWN POPULATION, and this re-derived it. The first version walked
        // `Catalogue.withOrigin(Refactorings.FOWLER)` and filtered out the smells by STRING
        // PREFIX — re-deciding, here, which of the origin's rows are refactorings. The catalogue
        // already answers exactly that as `Refactorings.all()`, and the very next statement in
        // the caller uses `Refactorings.all().size()` as the M in "N of M". So the refusal's
        // population and the logged population came from two derivations of one fact in two
        // files: let them disagree and the product refuses on one set while reporting the other.
        // An architect watch found it. Iterating the lane deletes the prefix filter, and a second
        // provider does not inherit a copy of it.
        List<String> undeclared = new ArrayList<>();
        for (CatalogueRow row : Refactorings.all()) {
            if (!declared.contains(row.key())) {
                undeclared.add(row.key() + " (" + row.name() + ")");
            }
        }
        if (!undeclared.isEmpty()) {
            throw new IllegalStateException(
                    "the catalogue holds refactoring rows this engine declares nothing about, "
                            + "which is neither performing them nor declining them: " + undeclared);
        }

        // (3) THE SHIPPED PATTERN LANE MUST MATCH ITS OWN HEADER. The rows are a resource in
        // the jar, so a truncated or omitted resource is silent at runtime in a way source
        // cannot be — and the lane's count is a published figure.
        int patterns = org.jawata.catalogue.Patterns.all().size();
        int declaredPatterns = org.jawata.catalogue.Patterns.declaredCount();
        if (patterns != declaredPatterns) {
            throw new IllegalStateException(
                    "the shipped pattern lane holds " + patterns + " rows and declares "
                            + declaredPatterns + " — the resource in this jar is not the one it "
                            + "says it is");
        }

        // (4) THE ROW INDEX WAS HERE AND IS WITHDRAWN. It wrote each PERFORMS row's key into the
        // registry, and an architect watch measured that NOTHING READ IT — both accessors at
        // zero references, production and test. The commit's claim that "a row key is answerable
        // from production" was therefore false as shipped; what is true is narrower and is still
        // the thing C8's Finding 1 was about: the declaration is READ by production at boot, so
        // the closed test-only loop is gone and the product can STATE what it performs.
        //
        // This method's own javadoc carries the reason and names §4-B's consumer: the index
        // returns when a cure resolves through a row key, which gives it a reader whose failure
        // an existing test can see.
        //
        // THE ANSWER IS THE COMPUTED JOIN against the shipped surface, which is what it always
        // was — the removed counter was never what this returned, because rows 30/31 and 32/33
        // share one operation each, so a per-row count and the join legitimately differ.
        return performs();
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
