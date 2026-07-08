package org.goja.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 22a R.2 — experience(kind=fallback_report) tallies the observer-recorded
 * goja-fallback declarations into a ranked capability-gap backlog, and excludes
 * unrelated failure_modes.
 */
class FallbackReportTest {

    private H2ExperienceStore store;
    private ExperienceTool tool;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private void seedFallback(String reason) {
        store.put(ExperienceEntry.of(
            SymbolFact.of("failure_mode", "goja-fallback slip: " + reason, Confidence.HIGH).build()).build());
    }

    @Test
    @DisplayName("fallback_report ranks the recorded gaps and excludes non-fallback failure_modes")
    void tallies_and_excludes() {
        seedFallback("grep on .java");
        seedFallback("grep on .java");
        seedFallback("bazel not supported");
        // a failure_mode that is NOT a goja-fallback slip — must be excluded.
        store.put(ExperienceEntry.of(
            SymbolFact.of("failure_mode", "some unrelated failure", Confidence.HIGH).build()).build());

        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "fallback_report");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();

        assertEquals(2, ((Number) data.get("distinctGaps")).intValue(),
            "two distinct gaps; the unrelated failure_mode is excluded: " + data);
        assertTrue(((Number) data.get("totalFallbacks")).intValue() >= 2,
            "every fallback declaration is counted: " + data);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gaps = (List<Map<String, Object>>) data.get("gaps");
        List<String> reasons = gaps.stream().map(g -> String.valueOf(g.get("reason"))).toList();
        assertTrue(reasons.contains("grep on .java") && reasons.contains("bazel not supported"),
            "both gap reasons are present: " + reasons);
        assertFalse(reasons.contains("some unrelated failure"), "non-fallback excluded: " + reasons);

        // Ranked by frequency: the repeated gap is at least as common as the single one.
        int grep = gaps.stream().filter(g -> "grep on .java".equals(g.get("reason")))
            .mapToInt(g -> ((Number) g.get("count")).intValue()).findFirst().orElse(0);
        int bazel = gaps.stream().filter(g -> "bazel not supported".equals(g.get("reason")))
            .mapToInt(g -> ((Number) g.get("count")).intValue()).findFirst().orElse(0);
        assertTrue(grep >= bazel, "the more frequent gap ranks at least as high: grep=" + grep + " bazel=" + bazel);
        assertEquals("grep on .java", gaps.get(0).get("reason"), "most frequent gap ranked first");
    }
}
