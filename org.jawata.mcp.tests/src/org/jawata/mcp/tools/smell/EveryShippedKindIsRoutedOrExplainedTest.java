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
 * <p>Scoped to the front doors THIS SPRINT HAS REACHED, which at C6 is four:
 * {@code apply_cleanup} from Stage 3, and {@code extract}, {@code inline} and {@code move}
 * from Stage 6. It was scoped to the first alone until C6, and an audit was right that the
 * silence therefore said nothing about Stage 6's twelve rows — the guard passed over them
 * without looking.</p>
 *
 * <p>It is still not the whole surface, and that is deliberate rather than convenient: a
 * test that fails for work not yet started teaches a reader to ignore it.
 * {@code PRE_EXISTING} below names the kinds that shipped BEFORE this sprint on the three
 * doors it widened to. They are exempted by NAME, not by silence, so the exemption is a
 * list somebody can read and shorten — and a new kind on those doors is guarded from the
 * day it lands, which is the whole difference.</p>
 */
class EveryShippedKindIsRoutedOrExplainedTest {

    /**
     * Kinds that shipped BEFORE Sprint 28d-rescue on the three doors this guard widened to
     * at C6. Exempt by NAME so the exemption is readable and shortenable; anything new on
     * those doors is guarded from the day it lands.
     */
    private static final List<String> PRE_EXISTING = List.of(
        "extract kind=variable", "extract kind=constant",
        "extract kind=interface", "extract kind=superclass",
        "inline kind=method", "inline kind=variable",
        "move kind=class", "move kind=package");
    // NOT EXEMPT, though both shipped before this sprint: Stage 6 CHANGED them, so
    // "pre-existing" stops being true of them. Row 49 landed as the `replaceDuplicates`
    // parameter on `extract kind=method` and folded `replace_duplicates` onto
    // `extract kind=replace_inline_code`, and an exemption keyed on when a kind first
    // shipped would have shielded exactly the kind the sprint touched. A C6 audit found
    // that, and it was right: an exemption list has to be about what was NOT worked on.

    @Test
    @DisplayName("each kind on the four reached front doors is named by a cure, or carries a reason")
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
            new org.jawata.mcp.tools.MoveTool(svc, cache));

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
        assertTrue(examined >= 30,
            "PROOF OF LIFE: the four doors must publish their kinds here, or this loop runs"
                + " over nothing and passes. Examined: " + examined);

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
