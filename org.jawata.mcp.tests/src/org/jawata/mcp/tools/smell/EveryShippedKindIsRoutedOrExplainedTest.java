package org.jawata.mcp.tools.smell;

import org.jawata.core.IJdtService;
import org.jawata.mcp.refactoring.OperationRegistry;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ApplyCleanupTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * <p><b>THE COUNTS IN THE PROSE BELOW WENT STALE AGAIN AT C7, and a round-2 audit listed all
 * three.</b> They are corrected here, and the correction is worth less than the sentence that
 * predicted it: this file already says "a number in prose beside a list it describes has
 * nothing holding it to the list", and then went stale a fourth time in the paragraph making
 * that point. The number that IS held is the {@code assertEquals} on {@code examined}. The
 * cure is deriving the door list from the registered doors rather than typing it, which is
 * Stage 9's M6c — every stale count in this file is one more argument for it.</p>
 *
 * <p>Scoped to the front doors THIS SPRINT HAS REACHED, which at C7 is SIX:
 * {@code apply_cleanup} from Stage 3, {@code extract}, {@code inline} and {@code move} from
 * Stage 6, {@code change_method_signature} from Stage 4, and {@code hierarchy} from Stage 7.
 * It was scoped to the first alone until C6, and an audit was right that the silence
 * therefore said nothing about Stage 6's twelve rows — the guard passed over them without
 * looking. <b>Then it happened again</b>: Stage 4 created a door with ten new kinds and did
 * not widen this list, so a C4 audit made the identical finding one stage later. <b>And a
 * third time at C7</b>, on {@code hierarchy}'s five. The lesson stopped getting narrower
 * after the second telling: the scope must widen in the SAME change that adds the kinds,
 * because nothing else will notice — and three stages have now proved that a lesson written
 * in a comment is not a mechanism.</p>
 *
 * <p><b>The sentence above SAID SIX and then LISTED FIVE until a C7 round-3 audit read it</b> —
 * omitting the very door C7 added, inside the commit whose stated job was this file's stale
 * counts, and in the same self-contradiction shape that commit had just repaired one file
 * over. It names all six now. That is the argument for M6c in one line: a hand-written list
 * and a hand-written count of it disagree the moment anyone edits either, and nothing here
 * fails when they do.</p>
 *
 * <p><b>THIS PARAGRAPH USED TO COUNT THE STALE COUNTS, and every count it gave was wrong.</b>
 * It has now been corrected three times, and each correction supplied a fresh number that the
 * next audit falsified — including one that asserted the count was gone in the same sentence
 * that restated it. So there is no number here at all, and that is the repair rather than a
 * gap in it. What is true without arithmetic: <b>every stale count in this file was a
 * hand-written number sitting beside a hand-written list, and each sweep to correct them
 * introduced one more.</b> Nothing in that history is a mechanism. The derivation in Stage 9's
 * M6c is — and until it lands, the honest form of this paragraph is one with nothing in it to
 * go stale.</p>
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
    @DisplayName("each kind on the six reached front doors is named by a cure, or carries a reason")
    void everyCleanupKindIsRoutedOrExplained() {
        Supplier<IJdtService> svc = () -> null;
        RefactoringChangeCache cache = new RefactoringChangeCache();
        // From each tool's PUBLISHED schema, which is the same list the registry harvests.
        // Reading it here rather than widening a package-private helper: the schema is the
        // contract, and a test asserting the contract should read the contract.
        List<org.jawata.mcp.tools.AbstractTool> doors = List.of(
            new ApplyCleanupTool(svc, cache),
            new org.jawata.mcp.tools.ExtractTool(svc, cache),
            new org.jawata.mcp.tools.InlineTool(svc, cache),
            new org.jawata.mcp.tools.MoveTool(svc, cache),
            // FIFTH at C4. Stage 4's ten kinds shipped neither routed nor written down, and
            // this guard was scoped past their door — so its silence said nothing about them,
            // which is word for word what a C6 audit said about Stage 6 and the reason the
            // list grew from one door to four. Third stage, same omission: the scope has to
            // widen in the SAME change that adds the kinds, or the guard passes over exactly
            // the work that was done.
            new org.jawata.mcp.tools.ChangeMethodSignatureTool(svc, cache),
            // SIXTH at C7, and the paragraph above predicted this exactly. Stage 7 added five
            // kinds to `hierarchy` and did not widen this list, so the guard passed over the
            // whole stage — which is what a C6 audit said about Stage 6 and a C4 audit said
            // about Stage 4, in the same words. FOURTH stage, same omission. The lesson has
            // been written down three times and applied zero times, which says the lesson is
            // not the cure: nothing here FAILS when a door is added, so the list can only be
            // widened by somebody remembering. Deriving it from the registered doors is the
            // cure, and it is Stage 9's M6c — this entry is the fourth argument for it.
            new org.jawata.mcp.tools.HierarchyTool(svc, cache));

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
        // 50 = the six doors' published kinds, and both halves are MEASURED rather than
        // arithmetic: a C7 round-2 audit removed HierarchyTool from the list above and the
        // failure read `Examined: 43`, so this door contributes exactly 7 — up, down and the
        // five Stage 7 rows — against the 43 the previous five published.
        org.junit.jupiter.api.Assertions.assertEquals(50, examined,
            "PROOF OF LIFE: the six doors must publish their kinds here, or this loop runs"
                + " over nothing and passes. A door removed from the list above, or a kind"
                + " added without a route or a reason, changes this number. Examined: "
                + examined);

        assertTrue(silent.isEmpty(),
            "these operations ship and no finding names them, and no reason is written"
                + " down. Either route them, or say in the table why nothing does — an"
                + " unexplained gap and a forgotten one read identically: " + silent);
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
