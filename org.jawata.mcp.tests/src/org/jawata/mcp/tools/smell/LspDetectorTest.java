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

/** Sprint 20 — Liskov Substitution detector (fixture com.example.LspTargets). */
class LspDetectorTest {

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
    private Set<String> symbols(String kind) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", kind);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), kind + " must dispatch");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        return findings.stream().map(f -> String.valueOf(f.get("symbol"))).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("flags any single-throw override, not a real one")
    void flags_contract_rejection() {
        Set<String> hits = symbols("lsp");
        assertTrue(hits.contains("op"), "UOE-throwing override should be flagged: " + hits);
        assertTrue(hits.contains("compute"), "any single-throw override should be flagged: " + hits);
        assertFalse(hits.contains("run"), "a real override must NOT be flagged: " + hits);
    }

    @Test
    @DisplayName("lsp is broader than refused_bequest (which catches only UnsupportedOperationException)")
    void lsp_broader_than_refused_bequest() {
        Set<String> rb = symbols("refused_bequest");
        // QUALIFIED AT S8b STEP 9 — and ONLY the refused_bequest half, which is the detector
        // step 9 changed. The `hits` assertions above read `lsp`, whose detector still emits
        // an identifier, so qualifying them would assert an address nothing produces.
        assertTrue(rb.contains("com.example.Rejecter#op"),
            "refused_bequest catches the UnsupportedOperationException override: " + rb);
        assertFalse(rb.contains("com.example.Rejecter#compute"),
            "refused_bequest must NOT catch the IllegalStateException override (lsp does): " + rb);
    }
}
