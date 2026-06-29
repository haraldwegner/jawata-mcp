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
 * Sprint 17 — Incomplete Delegation detector (REFACTORING_LESSONS_LEARNED §7).
 * Fixture com.example.IncompleteDelegationTargets: {@code scanRoute} recovers
 * identity via a getter chain in a loop (flagged); {@code indexedRoute} uses a
 * key-&gt;items index (not flagged).
 */
class IncompleteDelegationDetectorTest {

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
    private Set<String> symbols() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "incomplete_delegation");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "incomplete_delegation must dispatch");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        return findings.stream().map(f -> String.valueOf(f.get("symbol"))).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("flags the scan that rebuilds identity from a collaborator, not the indexed route")
    void flags_incomplete_delegation() {
        Set<String> hits = symbols();
        assertTrue(hits.contains("scanRoute"),
            "O(n) identity-recovery scan should be flagged: " + hits);
        assertFalse(hits.contains("indexedRoute"),
            "the key->items indexed route must NOT be flagged: " + hits);
    }
}
