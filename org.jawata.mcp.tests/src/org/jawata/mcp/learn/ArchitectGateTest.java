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

    private static final ArchitectGate GATE = new ArchitectGate(500);
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

    @Test
    void a_signature_or_hierarchy_change_is_flagged() {
        String steer = GATE.evaluate("change_method_signature", args(), mutate(diffOf(5)), false);
        assertNotNull(steer, "a signature change involves the architect");
        assertTrue(steer.contains("signature or hierarchy"), steer);
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
        String steer = GATE.evaluate("change_method_signature", args(), mutate(diffOf(5)), false);
        assertTrue(steer.contains("auto_apply=false") && steer.contains("before it lands"),
            "the steer tells the agent a staged edit is reviewable BEFORE apply: " + steer);
    }

    @Test
    void the_threshold_is_configurable() {
        ArchitectGate strict = new ArchitectGate(10);
        assertNotNull(strict.evaluate("format", args(), mutate(diffOf(11)), false),
            "a lower threshold flags a smaller edit — the constant is tunable in dogfood");
        assertNull(new ArchitectGate(500).evaluate("format", args(), mutate(diffOf(11)), false),
            "the same edit is plain under the default threshold");
    }
}
