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
 * <p>Scoped to {@code apply_cleanup} deliberately. The whole surface would fail today for
 * kinds this sprint has not reached, and a test that fails for work not yet started
 * teaches a reader to ignore it.</p>
 */
class EveryShippedKindIsRoutedOrExplainedTest {

    @Test
    @DisplayName("each apply_cleanup kind is named by a cure, or carries a written reason")
    void everyCleanupKindIsRoutedOrExplained() {
        Supplier<IJdtService> svc = () -> null;
        ApplyCleanupTool tool = new ApplyCleanupTool(svc, new RefactoringChangeCache());
        // From the tool's PUBLISHED schema, which is the same list the registry
        // harvests. Reading it here rather than widening a package-private helper: the
        // schema is the contract, and a test asserting the contract should read the
        // contract.
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> properties =
            (java.util.Map<String, Object>) tool.getInputSchema().get("properties");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> kindSchema =
            (java.util.Map<String, Object>) properties.get("kind");
        List<String> kinds = new ArrayList<>();
        for (Object value : (java.util.Collection<?>) kindSchema.get("enum")) {
            kinds.add(String.valueOf(value));
        }
        assertTrue(kinds.size() >= 8,
            "PROOF OF LIFE: the tool must publish its kinds here, or this loop runs over"
                + " nothing and passes. Got: " + kinds);

        List<String> routed = new ArrayList<>();
        for (String kind : CureCatalog.declaredKinds()) {
            routed.addAll(CureCatalog.recipesFor(kind));
        }

        List<String> silent = new ArrayList<>();
        for (String kind : kinds) {
            String qualified = OperationRegistry.qualify(tool.getName(), kind);
            if (routed.contains(qualified) || routed.contains(kind)) {
                continue;
            }
            if (CureCatalog.unroutedReason(qualified) == null) {
                silent.add(qualified);
            }
        }

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
