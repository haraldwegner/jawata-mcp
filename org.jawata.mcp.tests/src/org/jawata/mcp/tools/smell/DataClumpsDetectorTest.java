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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 17 — Data Clumps detector. Fixture {@code com.example.DataClumpsTargets}:
 * {@code plotPoint} and {@code movePoint} share the 3-param tuple
 * {@code (int px, int py, int pz)} — a clump at the default threshold (2). The
 * 1-param {@code single} is below the minimum clump width.
 */
class DataClumpsDetectorTest {

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
        args.put("kind", "data_clumps");
        if (threshold != null) {
            args.put("threshold", threshold.intValue());
        }
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "data_clumps must dispatch");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        return findings.stream().map(f -> String.valueOf(f.get("symbol"))).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("flags both methods sharing a 3-param tuple, not the single-param one")
    void flags_shared_tuple() {
        Set<String> hits = symbols(null);
        assertTrue(hits.contains("plotPoint"), "shared-tuple method should be flagged: " + hits);
        assertTrue(hits.contains("movePoint"), "shared-tuple method should be flagged: " + hits);
        assertFalse(hits.contains("single"), "1-param method below clump width must NOT be flagged: " + hits);
    }

    @Test
    @DisplayName("a high recurrence threshold spares a tuple that recurs only twice")
    void threshold_respected() {
        Set<String> hits = symbols(5);
        assertFalse(hits.contains("plotPoint"), "tuple recurring twice clears a threshold of 5: " + hits);
        assertFalse(hits.contains("movePoint"), "tuple recurring twice clears a threshold of 5: " + hits);
    }
}
