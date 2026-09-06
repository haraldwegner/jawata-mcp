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

/** Sprint 17 — Refused Bequest detector (fixture com.example.RefusedBequestTargets). */
class RefusedBequestDetectorTest {

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
        args.put("kind", "refused_bequest");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "refused_bequest must dispatch");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        return findings.stream().map(f -> String.valueOf(f.get("symbol"))).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("flags the override that throws UnsupportedOperationException, not the honoured one")
    void flags_refusal() {
        Set<String> hits = symbols();
        // QUALIFIED AT S8b STEP 9, NEGATIVES INCLUDED — `hits` is a Set, so a bare name left
        // here makes assertFalse trivially true. The positive is the address this run printed;
        // the negative is read off the fixture, and BOTH declarations of `keepIt` are named,
        // because the base class and the honouring subclass each declare one and asserting
        // only the subclass would leave the other unmeasured.
        assertTrue(hits.contains("com.example.Refuser#doIt"),
            "override that throws UnsupportedOperationException should be flagged: " + hits);
        assertFalse(hits.contains("com.example.Honorer#keepIt"),
            "override with a real body must NOT be flagged: " + hits);
        assertFalse(hits.contains("com.example.BequestBase#keepIt"),
            "and neither must the base declaration it overrides: " + hits);
    }
}
