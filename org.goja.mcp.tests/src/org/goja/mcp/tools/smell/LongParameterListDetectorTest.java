package org.goja.mcp.tools.smell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.FindQualityIssueTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 17 — Long Parameter List detector. Fixture
 * {@code com.example.LongParameterListTargets}: {@code tooMany} (5 params) and
 * the 5-arg constructor are flagged at the default threshold (4); {@code ok}
 * (2 params) is not. Raising the threshold spares them.
 */
class LongParameterListDetectorTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindQualityIssueTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Set<String> symbols(Integer threshold) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "long_parameter_list");
        if (threshold != null) {
            args.put("threshold", threshold.intValue());
        }
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "long_parameter_list must dispatch");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        return findings.stream().map(f -> String.valueOf(f.get("symbol"))).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("flags 5-param method + constructor, not the 2-param method")
    void flags_long_lists() {
        Set<String> hits = symbols(null);
        assertTrue(hits.contains("tooMany"), "5-param method should be flagged: " + hits);
        assertTrue(hits.contains("LongParameterListTargets"),
            "5-arg constructor should be flagged (symbol = type name): " + hits);
        assertFalse(hits.contains("ok"), "2-param method must NOT be flagged: " + hits);
    }

    @Test
    @DisplayName("raising threshold above the width spares the method")
    void threshold_respected() {
        Set<String> hits = symbols(10);
        assertFalse(hits.contains("tooMany"), "5 params clears a threshold of 10: " + hits);
    }
}
