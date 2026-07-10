package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindQualityIssueTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 22a 2.6.1 — find_quality_issue sweep hardening (dogfood-surfaced on the deployed
 * v2.6.0 resident, invisible to single-file fixture tests):
 * <ul>
 *   <li>#1 a whole-family sweep is bounded — {@code summary} mode + a default findings cap.</li>
 *   <li>#3 a relative {@code filePath} resolves against the project root and yields a
 *       project-relative path (it previously resolved against the CWD / AppImage mount).</li>
 * </ul>
 */
class SweepHardeningTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new FindQualityIssueTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @Test
    @DisplayName("#1 summary mode returns counts-by-kind + conflicts, no full findings array")
    @SuppressWarnings("unchecked")
    void summary_mode_returns_counts_not_findings() {
        ObjectNode a = mapper.createObjectNode();
        a.put("family", "fowler");
        a.put("threshold", 1);   // low threshold -> many findings, deterministically
        a.put("summary", true);

        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        Map<String, Object> d = data(r);

        assertEquals(Boolean.TRUE, d.get("summary"));
        Map<String, Object> byKind = (Map<String, Object>) d.get("byKind");
        assertNotNull(byKind, () -> "byKind counts present: " + d);
        assertFalse(byKind.isEmpty(), () -> "byKind is non-empty: " + d);
        assertNull(d.get("findings"), "summary mode omits the full findings array");
        assertTrue(d.containsKey("conflicts"), "conflicts are still surfaced in summary mode");
        assertTrue(((Number) d.get("count")).intValue() > 0, "the full count is reported");
    }

    @Test
    @DisplayName("#1 limit caps the findings + flags truncation; count carries the full total")
    void limit_caps_findings_and_flags_truncation() {
        ObjectNode a = mapper.createObjectNode();
        a.put("family", "fowler");
        a.put("threshold", 1);
        a.put("limit", 2);

        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        Map<String, Object> d = data(r);

        List<?> findings = (List<?>) d.get("findings");
        int total = ((Number) d.get("count")).intValue();
        assertTrue(findings.size() <= 2, () -> "findings capped to the limit: " + findings.size());
        assertEquals(findings.size(), ((Number) d.get("returnedCount")).intValue());
        if (total > 2) {
            assertEquals(Boolean.TRUE, d.get("truncated"), "an over-limit sweep is flagged truncated");
            assertTrue(d.containsKey("hint"), "a paging hint is offered");
        }
    }

    @Test
    @DisplayName("#3 a relative filePath resolves against the project root -> project-relative finding path")
    @SuppressWarnings("unchecked")
    void scoped_kind_sweep_resolves_relative_filepath() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "long_method");
        a.put("threshold", 1);
        a.put("filePath", "src/main/java/com/example/ComposeMethodTargets.java"); // relative

        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        Map<String, Object> d = data(r);

        List<Map<String, Object>> findings = (List<Map<String, Object>>) d.get("findings");
        assertFalse(findings.isEmpty(),
            "a relative filePath must resolve against the project root "
                + "(before the fix it resolved against the CWD/mount and found nothing)");
        for (Map<String, Object> f : findings) {
            String fp = String.valueOf(f.get("filePath"));
            assertFalse(fp.startsWith("/"),
                () -> "a detector path must be project-relative, not an absolute/mount path: " + fp);
        }
    }
}
