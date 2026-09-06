package org.jawata.mcp.learn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 26a D3b (C3): the deterministic architect-involvement gate — the four
 * triggers (smell / signature-hierarchy / large / plain) from the WRITTEN rule,
 * plus staged-diff reviewability. This gate replaces the retired edit-switch.
 */
class ArchitectGateTest {

    /**
     * The registry the gate reads, populated the way the application populates it.
     *
     * <p>EVERY refactoring front door is registered FROM THE TOOL. The gate used to carry two
     * literal sets of tool names and needed nothing to answer. It now asks the registry,
     * because a list of names in this package went stale the moment stage 1 renamed a tool and
     * nothing failed. A test that left the registry empty would see a gate that never fires —
     * which is exactly the failure being repaired, reproduced in the test instead of in
     * production.</p>
     *
     * <p><b>THREE DOORS WERE STILL LITERAL UNTIL AN ARCHITECT WATCH READ THIS AGAINST THE
     * DOORS, and the sentence above claimed otherwise.</b> It said only tools this test does
     * not construct remain literal — but {@code change_method_signature}, {@code hierarchy}
     * and {@code refactor_to_pattern} take the same two-argument constructor the three below
     * do, so nothing stopped them being built. Measured at the time: the literals said 0, 2
     * and 0 kinds where the doors publish 11, 7 and 11, and {@code change_method_signature}
     * was hard-coded structural where the tool inherits {@code false} and carries its
     * structural-ness PER KIND. So the registry disagreed with production in both directions
     * at once, and the step that had just widened the operation surface to that door — M10 —
     * was invisible here. A guard over a fact the door renders is the copy that grows when the
     * owner is not asked.</p>
     */
    private static org.jawata.mcp.refactoring.OperationRegistry wiredRegistry() {
        org.jawata.mcp.refactoring.OperationRegistry registry =
            new org.jawata.mcp.refactoring.OperationRegistry();
        // EVERY refactoring door comes from the tool, not from a literal. Three were literals
        // until a C6 audit read them: `extract` was registered with seven kinds and two
        // structural ones, `move` with three and one, and `inline` NOT AT ALL — the
        // pre-Stage-6 world, under a javadoc claiming this is populated the way the
        // application populates it. The gate's own test was therefore blind to exactly the
        // kinds the gate had gone silent on. THREE MORE were still literal after that repair,
        // and the same blindness applied: see the javadoc above.
        for (org.jawata.mcp.tools.Tool tool : java.util.List.<org.jawata.mcp.tools.Tool>of(
                new org.jawata.mcp.tools.ExtractTool(() -> null,
                    new org.jawata.mcp.refactoring.RefactoringChangeCache()),
                new org.jawata.mcp.tools.InlineTool(() -> null,
                    new org.jawata.mcp.refactoring.RefactoringChangeCache()),
                new org.jawata.mcp.tools.MoveTool(() -> null,
                    new org.jawata.mcp.refactoring.RefactoringChangeCache()),
                new org.jawata.mcp.tools.ChangeMethodSignatureTool(() -> null,
                    new org.jawata.mcp.refactoring.RefactoringChangeCache()),
                new org.jawata.mcp.tools.HierarchyTool(() -> null,
                    new org.jawata.mcp.refactoring.RefactoringChangeCache()),
                new org.jawata.mcp.tools.RefactorToPatternTool(() -> null,
                    new org.jawata.mcp.refactoring.RefactoringChangeCache()))) {
            registry.register(tool.getName(), tool.publishedKinds(), tool.isMechanical(),
                tool.isStructural(), tool.structuralKinds());
        }
        registry.register("format", java.util.List.of(), false, false, java.util.Set.of());
        registry.register("rename_symbol", java.util.List.of(), true, false,
            java.util.Set.of());
        return registry;
    }

    private static final ArchitectGate GATE = new ArchitectGate(500, wiredRegistry());
    private static final ObjectMapper OM = new ObjectMapper();

    private static ToolResponse mutate(String diff) {
        return ToolResponse.success(Map.of("filesModified", List.of("A.java"), "diff", diff));
    }

    private static ObjectNode args(String... kv) {
        ObjectNode n = OM.createObjectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            n.put(kv[i], kv[i + 1]);
        }
        return n;
    }

    private static String diffOf(int changedLines) {
        StringBuilder sb = new StringBuilder("--- a/A.java\n+++ b/A.java\n");
        for (int i = 0; i < changedLines; i++) {
            sb.append("+    int added").append(i).append(" = ").append(i).append(";\n");
        }
        return sb.toString();
    }

    @Test
    void a_smell_introducing_edit_is_flagged() {
        String steer = GATE.evaluate("rename_symbol", args(), mutate(diffOf(3)), true);
        assertNotNull(steer, "a smell on the delta involves the architect");
        assertTrue(steer.contains("code smell"), steer);
        assertTrue(steer.contains("/refactor"), "names how to involve the architect");
    }


    /**
     * A structural kind of the signature door, and one that is not — ASKED OF THE DOOR.
     *
     * <p>These two tests used to call {@code change_method_signature} with NO kind at all and
     * still expect the gate to fire, which worked only because this file registered that door
     * as unconditionally structural. The tool says otherwise: it inherits {@code
     * isStructural() == false} and carries structural-ness PER KIND, so a bare call is not a
     * structural edit and the gate is right not to flag it. Naming the kinds here rather than
     * hard-coding them is the same argument the registry above makes — the door renders this,
     * so ask it.</p>
     */
    private static org.jawata.mcp.tools.ChangeMethodSignatureTool signatureDoor() {
        return new org.jawata.mcp.tools.ChangeMethodSignatureTool(() -> null,
            new org.jawata.mcp.refactoring.RefactoringChangeCache());
    }

    private static String aStructuralSignatureKind() {
        return signatureDoor().structuralKinds().stream().sorted().findFirst().orElseThrow(
            () -> new AssertionError("PROOF OF LIFE: the signature door declares NO structural"
                + " kind, so the architect gate can never fire for it and these tests assert"
                + " nothing — which is the state M10's companion exists to prevent"));
    }

    @Test
    void a_signature_or_hierarchy_change_is_flagged() {
        String kind = aStructuralSignatureKind();
        String steer = GATE.evaluate("change_method_signature", args("kind", kind),
            mutate(diffOf(5)), false);
        assertNotNull(steer, "a structural signature change involves the architect: " + kind);
        assertTrue(steer.contains("signature or hierarchy"), steer);
    }

    @Test
    void a_non_structural_kind_of_the_same_door_is_NOT_flagged() {
        // THE CONTROL, and it is what makes the case above about the KIND rather than about
        // the door. Without it, a gate that flagged every call to this tool — which is
        // precisely what the old literal produced — would satisfy the assertion above.
        java.util.Set<String> structural = signatureDoor().structuralKinds();
        String plain = signatureDoor().publishedKinds().stream()
            .filter(k -> !structural.contains(k)).sorted().findFirst().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(plain != null,
            "every kind of this door is structural today, so there is no control to run");
        assertNull(GATE.evaluate("change_method_signature", args("kind", plain),
            mutate(diffOf(5)), false),
            "a non-structural kind of a door whose other kinds ARE structural must pass"
                + " untouched: " + plain);
    }

    @Test
    void an_extract_superclass_is_structural_and_flagged() {
        assertNotNull(GATE.evaluate("extract", args("kind", "superclass"), mutate(diffOf(5)), false),
            "extract superclass changes the hierarchy");
        assertNull(GATE.evaluate("extract", args("kind", "method"), mutate(diffOf(5)), false),
            "extract method is a local, non-structural extract");
    }

    @Test
    void a_large_edit_over_the_threshold_is_flagged() {
        String steer = GATE.evaluate("format", args(), mutate(diffOf(501)), false);
        assertNotNull(steer, "an edit over the 500-line threshold involves the architect");
        assertTrue(steer.contains("large") && steer.contains("501"), steer);
    }

    @Test
    void a_plain_small_non_structural_edit_passes_untouched() {
        assertNull(GATE.evaluate("format", args(), mutate(diffOf(4)), false),
            "statement-local, small, no smell, non-structural → no architect, the NEGATIVE");
        assertNull(GATE.evaluate("organize_imports", args(), mutate(diffOf(10)), false),
            "a routine import tidy is plain");
    }

    @Test
    void a_non_mutate_call_is_never_gated() {
        assertNull(GATE.evaluate("analyze", args("typeName", "com.foo.Bar"),
            ToolResponse.success(Map.of("kind", "type")), false),
            "a read is not an edit — nothing to gate");
    }

    @Test
    void a_flagged_edit_names_staged_reviewability_before_apply() {
        String steer = GATE.evaluate("change_method_signature",
            args("kind", aStructuralSignatureKind()), mutate(diffOf(5)), false);
        assertNotNull(steer, "the edit must be flagged before its steer can be read");
        assertTrue(steer.contains("auto_apply=false") && steer.contains("before it lands"),
            "the steer tells the agent a staged edit is reviewable BEFORE apply: " + steer);
    }

    @Test
    void the_threshold_is_configurable() {
        ArchitectGate strict = new ArchitectGate(10, wiredRegistry());
        assertNotNull(strict.evaluate("format", args(), mutate(diffOf(11)), false),
            "a lower threshold flags a smaller edit — the constant is tunable in dogfood");
        assertNull(new ArchitectGate(500, wiredRegistry()).evaluate("format", args(), mutate(diffOf(11)), false),
            "the same edit is plain under the default threshold");
    }
}
