package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.jawata.mcp.fixtures.StubTool;
import org.jawata.mcp.learn.PlanLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h item 9 (i) slice 3: {@code plan_steps} changes the plan ledger with {@code action=enter}, so it is not published as read-only,
 * as {@code git} and the other tools with an action that writes are not.
 */
class PlanStepsIsNotReadOnlyTest {

    @SuppressWarnings("unchecked")
    private static boolean publishedAsReadOnly(Map<String, Object> definition) {
        Object annotations = definition.get("annotations");
        return annotations instanceof Map<?, ?> map && Boolean.TRUE.equals(((Map<String, Object>) map).get("readOnlyHint"));
    }

    @Test
    @DisplayName("plan_steps is published without the read-only hint, because its enter action changes the ledger")
    void planStepsIsNotPublishedAsReadOnly() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new PlanStepsTool(new PlanLedger()));

        assertEquals(false, publishedAsReadOnly(registry.getToolDefinitions().get(0)));
    }

    @Test
    @DisplayName("control: a tool named analyze is still published with the read-only hint, so the test above is not blind to the hint")
    void analyzeIsStillPublishedAsReadOnly() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(StubTool.named("analyze"));

        assertEquals(true, publishedAsReadOnly(registry.getToolDefinitions().get(0)));
    }
}
