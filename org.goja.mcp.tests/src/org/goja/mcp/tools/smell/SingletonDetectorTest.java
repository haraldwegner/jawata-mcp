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

/** Sprint 19 — Singleton detector (fixture com.example.SingletonTargets), family kerievsky. */
class SingletonDetectorTest {

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
    private Set<String> symbols(String kindOrFamily, boolean isFamily) {
        ObjectNode args = mapper.createObjectNode();
        args.put(isFamily ? "family" : "kind", kindOrFamily);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        return findings.stream().map(f -> String.valueOf(f.get("symbol"))).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("flags the GoF singleton, not a plain class")
    void flags_singleton() {
        Set<String> hits = symbols("singleton", false);
        assertTrue(hits.contains("Registry"),
            "a private-ctor + static-holder + static-accessor class should be flagged: " + hits);
        assertFalse(hits.contains("PlainService"),
            "a normal class must NOT be flagged: " + hits);
    }

    @Test
    @DisplayName("singleton is projected under family=kerievsky")
    void projects_under_kerievsky_family() {
        Set<String> hits = symbols("kerievsky", true);
        assertTrue(hits.contains("Registry"),
            "family=kerievsky must include the singleton finding: " + hits);
    }
}
