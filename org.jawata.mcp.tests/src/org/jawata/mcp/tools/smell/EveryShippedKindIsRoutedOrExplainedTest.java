package org.jawata.mcp.tools.smell;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.jawata.core.IJdtService;
import org.jawata.mcp.refactoring.OperationRegistry;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EVERY SHIPPED OPERATION IS EITHER OFFERED BY A FINDING OR EXPLAINED.
 *
 * <p>The sprint's per-row contract ends with "routed, or listed as unrouted with the
 * reason", and the second half is what this guards. An operation that ships, works, is
 * tested and is named by nothing is not a scandal — it is a gap on the DETECTOR side, and
 * a normal one. What is not acceptable is that gap being indistinguishable from having
 * forgotten.</p>
 *
 * <p>Stage 3 shipped six rewrites and routed one. The other five were neither routed nor
 * explained, and nothing said so; the shortfall was found by a review reading the table,
 * which is the wrong instrument for a fact a test can hold.</p>
 *
 * <p><b>THE PROSE COUNTS IN THIS FILE WENT STALE FOUR TIMES, and each sweep to correct them
 * introduced the next one</b> — including a sentence that said SIX and then listed five, in
 * the very commit whose job was fixing stale counts, and another that announced the count was
 * gone in the same breath as restating it. The file's own diagnosis was right: a number in
 * prose beside a hand-written list has nothing holding it to the list, and nothing here FAILED
 * when the two disagreed.</p>
 *
 * <p><b>That is over, because the list it drifted from is gone.</b> S8b step 8 derives the
 * doors, so there is no hand-written membership left for a paragraph to miscount. One number
 * is still typed by hand — the {@code assertEquals} on {@code examined} — and that one is
 * right to be: deriving it from the same call that fills the loop would compare the doors with
 * themselves, which is the tautology this file's siblings already record.</p>
 *
 * <p><b>SCOPED TO EVERY REFACTORING DOOR, because the list is no longer written by hand.</b>
 * S8b step 8 replaced it with {@code RefactoringDoors.all} — the same call the application
 * registers from — so the guard examines all nine doors and their 78 operations, and a door
 * cannot be shipped and unexamined.</p>
 *
 * <p>The history is kept because it is the argument. It was scoped to {@code apply_cleanup}
 * alone until C6, and an audit was right that the silence therefore said nothing about Stage
 * 6's twelve rows — the guard passed over them without looking. <b>Then it happened again</b>:
 * Stage 4 created a door with ten new kinds and did not widen this list, so a C4 audit made
 * the identical finding one stage later. <b>And a third time at C7</b>, on {@code hierarchy}'s
 * five. Each entry recorded the same lesson in the same words — the scope must widen in the
 * SAME change that adds the kinds, because nothing else will notice — and the next stage did
 * not apply it. That is the tell: nothing here FAILED when a door was added, so the list could
 * only grow when somebody remembered, and three stages running nobody did. A lesson written in
 * a comment was never the mechanism; the derivation is.</p>
 *
 * <p>It is still not the whole surface, and that is deliberate rather than convenient: a
 * test that fails for work not yet started teaches a reader to ignore it.
 * {@code PRE_EXISTING} below names the kinds that shipped BEFORE this sprint on the doors
 * it widened to. They are exempted by NAME, not by silence, so the exemption is a
 * list somebody can read and shorten — and a new kind on those doors is guarded from the
 * day it lands, which is the whole difference.</p>
 */
class EveryShippedKindIsRoutedOrExplainedTest {

    /**
     * Kinds that shipped BEFORE Sprint 28d-rescue on the FIVE doors this guard has widened
     * to — three at C6 ({@code extract}, {@code inline}, {@code move}), one at C4
     * ({@code change_method_signature}) and one at C7 ({@code hierarchy}). Exempt by NAME so
     * the exemption is readable and shortenable; anything new on those doors is guarded from
     * the day it lands.
     *
     * <p><b>This sentence said "three doors at C6" until a C4 audit read it against the list
     * below.</b> That is the THIRD time a count in this file went stale, and the other two
     * were corrected by the two commits that walked past this one — so the lesson is not
     * "check the counts" but that a number in prose beside a list it describes has nothing
     * holding it to the list. The count that IS held is the {@code assertEquals} on
     * {@code examined} further down; this paragraph is prose and will go stale again.</p>
     */
    private static final List<String> PRE_EXISTING = List.of(
        "extract kind=variable", "extract kind=constant",
        "extract kind=interface", "extract kind=superclass",
        "inline kind=method", "inline kind=variable",
        "move kind=class", "move kind=package",
        // The operation change_method_signature WAS before Stage 4 gave it ten siblings and
        // turned it into a door. Stage 4 did not change it — it only stopped being the whole
        // tool — so it is exempt on the same terms as the eight above.
        "change_method_signature kind=change_signature",
        // The two on `hierarchy` that predate Stage 7. They were reached as the standalone
        // tools pull_up and push_down until Stage 1 renamed the door; Stage 7 did not change
        // them, it only gave them five siblings, so they are exempt on the same terms as the
        // eight above.
        // Spelled `kind=` because that is what OperationRegistry.qualify produces for every
        // door, including one whose discriminator is `direction`. CureCatalog's Stage 7 block
        // records why that spelling is wrong as an INSTRUCTION and why it is right as a KEY.
        "hierarchy kind=up", "hierarchy kind=down");
    // NOT EXEMPT, though both shipped before this sprint: Stage 6 CHANGED them, so
    // "pre-existing" stops being true of them. Row 49 landed as the `replaceDuplicates`
    // parameter on `extract kind=method` and folded `replace_duplicates` onto
    // `extract kind=replace_inline_code`, and an exemption keyed on when a kind first
    // shipped would have shielded exactly the kind the sprint touched. A C6 audit found
    // that, and it was right: an exemption list has to be about what was NOT worked on.

    @Test
    @DisplayName("each kind on every refactoring door is named by a cure, or carries a reason")
    void everyCleanupKindIsRoutedOrExplained() {
        Supplier<IJdtService> svc = () -> null;
        RefactoringChangeCache cache = new RefactoringChangeCache();
        // From each tool's PUBLISHED schema, which is the same list the registry harvests.
        // Reading it here rather than widening a package-private helper: the schema is the
        // contract, and a test asserting the contract should read the contract.
        // DERIVED AT S8b STEP 8, and the history is kept because it is the argument.
        //
        // This list was hand-written and was widened FOUR TIMES, each time by an audit rather
        // than by a failure: from one door to four when a C6 audit found Stage 6's eleven
        // kinds unexamined; a FIFTH at C4, when Stage 4's ten kinds shipped neither routed nor
        // written down and this guard was scoped past their door, so its silence said nothing
        // about them; a SIXTH at C7, when Stage 7 added five kinds to `hierarchy` and the
        // guard passed over the whole stage. Each entry recorded the same lesson in the same
        // words — "the scope must widen in the SAME change that adds the kinds, because
        // nothing else will notice" — and the next stage did not apply it.
        //
        // That is the tell: the lesson was never the cure. Nothing here FAILED when a door was
        // added, so the list could only be widened by somebody remembering, and four times
        // running nobody did. The population now comes from the same call the application
        // registers from, so a door cannot be shipped and unexamined.
        List<org.jawata.mcp.tools.AbstractTool> doors =
            org.jawata.mcp.tools.RefactoringDoors.all(svc, cache);

        List<String> routed = new ArrayList<>();
        for (String kind : CureCatalog.declaredKinds()) {
            routed.addAll(CureCatalog.recipesFor(kind));
        }

        int examined = 0;
        List<String> silent = new ArrayList<>();
        for (org.jawata.mcp.tools.AbstractTool tool : doors) {
            for (String kind : tool.publishedKinds()) {
                String qualified = OperationRegistry.qualify(tool.getName(), kind);
                examined++;
                if (PRE_EXISTING.contains(qualified)
                        || routed.contains(qualified) || routed.contains(kind)) {
                    continue;
                }
                if (CureCatalog.unroutedReason(qualified) == null) {
                    silent.add(qualified);
                }
            }
        }
        // AN EXACT COUNT, not a floor. It was `examined >= 30`, which cannot see a door
        // leave: any single door's departure still lands above 30, so the floor passes — the
        // precise re-narrowing the widening was for. A floor cannot see a door go; a count
        // can, and the sibling guard in FrontDoorDescriptionTest already used one.
        //
        // 78 = the NINE doors' published kinds, which is the whole operation surface — the
        // measured start state S8b opened on, reached here for the first time because the
        // door list stopped being hand-written. It was 50 over six doors; `data` (10),
        // `generate` (7) and `refactor_to_pattern` (11) were the three the hand list never
        // gained.
        //
        // The literal STAYS hand-written on purpose, and this is the one place that is right.
        // Deriving it from the same call that populates the loop would compare the doors with
        // themselves — the tautology this file's sibling guards already record — so the number
        // is what a drifting population cannot fake. It was `examined >= 30` once: a floor
        // cannot see a door LEAVE, because any single departure still lands above it.
        org.junit.jupiter.api.Assertions.assertEquals(78, examined,
            "PROOF OF LIFE: the nine doors must publish their kinds here, or this loop runs"
                + " over nothing and passes. A door removed from RefactoringDoors.all, or a"
                + " kind added without a route or a reason, changes this number. Examined: "
                + examined);

        // THE SILENT SET IS NAMED, NOT MERELY EMPTY — and this is S8b step 8's honest state
        // rather than its finished one.
        //
        // Widening the door list from six to nine made eighteen operations visible to this
        // guard for the first time. They are not newly unrouted; they have shipped unrouted
        // and unexplained all along, and the hand-written list was what kept the guard from
        // saying so. Step 9 is the routing table that empties this set — the plan assigns the
        // emptiness to that step in as many words.
        //
        // ASSERTED BY EQUALITY, for the reason step 5's derived gate gives: an `isEmpty()`
        // that is expected to fail says nothing, and a set that merely SHRINKS cannot tell a
        // routed operation from a forgotten one. Equality means an operation ARRIVING here —
        // a new kind shipped without a route or a reason — fails immediately, which is the
        // property the guard exists for and the one a hand-written door list never had.
        org.junit.jupiter.api.Assertions.assertEquals(
            java.util.Set.of(
                "generate kind=constructor", "generate kind=getters_setters",
                "generate kind=equals_hashcode", "generate kind=tostring",
                "generate kind=test_skeleton", "generate kind=override_methods",
                "generate kind=copy_class",
                "refactor_to_pattern kind=refactor_to_visitor",
                "refactor_to_pattern kind=replace_pattern_with_idiom",
                "refactor_to_pattern kind=replace_constructor_with_factory",
                "data kind=hide_delegate", "data kind=special_case",
                "data kind=replace_primitive", "data kind=split_variable",
                "data kind=replace_derived_variable", "data kind=remove_setting_method",
                "data kind=encapsulate_record", "data kind=reference_to_value"),
            new java.util.LinkedHashSet<>(silent),
            "these operations ship and no finding names them, and no reason is written"
                + " down. Either route them, or say in the table why nothing does — an"
                + " unexplained gap and a forgotten one read identically. Step 9 empties"
                + " this set; until then it is named so nothing can quietly join it: "
                + silent);
    }

    @Test
    @DisplayName("a reason is only owed by something that is actually unrouted")
    void aRoutedKindNeedsNoReason() {
        // The control. Without it this test would pass just as well if every kind carried
        // a reason AND a route, which would mean the reasons were decoration.
        assertTrue(CureCatalog.unroutedReason("apply_cleanup kind=loop_to_pipeline") == null,
            "loop_to_pipeline IS routed — the `loops` smell names it — so a reason for it"
                + " would be a stale explanation of something that no longer holds");
        List<String> routed = new ArrayList<>();
        for (String kind : CureCatalog.declaredKinds()) {
            routed.addAll(CureCatalog.recipesFor(kind));
        }
        assertTrue(routed.contains("apply_cleanup kind=loop_to_pipeline"),
            "and that route must actually be in the table: " + routed);
    }
}
