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
 * Sprint 17 — God Class detector. Fixture {@code com.example.GodClassTargets}:
 * {@code GodClassTarget} (21 members + 8 referencing types) is a God Class;
 * {@code LonelyLargeClass} (21 members, fan-in 0) is large but NOT a God Class.
 */
class GodClassDetectorTest {

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
    private Set<String> godClassSymbols() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "god_class");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "god_class must dispatch");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        return findings.stream().map(f -> String.valueOf(f.get("symbol"))).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("flags the large + central class, not the large + isolated one")
    void flags_god_not_lonely() {
        Set<String> hits = godClassSymbols();
        assertTrue(hits.contains("GodClassTarget"),
            "large class with high fan-in should be flagged: " + hits);
        assertFalse(hits.contains("LonelyLargeClass"),
            "large class with zero fan-in must NOT be a God Class: " + hits);
    }
}
