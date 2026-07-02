package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.RefactoringTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 18 — refactoring(action=plan): decompose a kind into an ordered, inspectable
 * step list. Pure descriptor construction (no workspace mutation); a real service is
 * loaded only to satisfy the front door's project-loaded gate.
 */
class RefactorPlanToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RefactoringTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new RefactoringTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode plan(String kind) {
        ObjectNode n = mapper.createObjectNode();
        n.put("action", "plan");
        n.put("kind", kind);
        n.put("filePath", "com/example/Sample.java");
        return n;
    }

    @Test
    @DisplayName("inline_singleton yields a single refactor_to_pattern step + a planId")
    void inlineSingleton_singleStep() {
        ObjectNode a = plan("inline_singleton");
        a.put("line", 5);
        a.put("column", 2);

        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        Map<String, Object> d = getData(r);
        assertEquals("plan", d.get("action"));
        assertNotNull(d.get("planId"));

        List<?> steps = (List<?>) d.get("steps");
        assertEquals(1, steps.size());
        Map<?, ?> s0 = (Map<?, ?>) steps.get(0);
        assertEquals("refactor_to_pattern", s0.get("tool"));
        assertEquals("inline_singleton", ((JsonNode) s0.get("args")).get("kind").asText());
    }

    @Test
    @DisplayName("compose_method yields one extract step per section, ordered bottom-up")
    void composeMethod_ordersBottomUp() {
        ObjectNode a = plan("compose_method");
        ArrayNode sections = a.putArray("sections");
        sections.add(section(10, 8, 12, 9, "low"));
        sections.add(section(20, 8, 22, 9, "high"));

        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        List<?> steps = (List<?>) getData(r).get("steps");
        assertEquals(2, steps.size());

        Map<?, ?> first = (Map<?, ?>) steps.get(0);
        assertEquals("extract", first.get("tool"));
        JsonNode firstArgs = (JsonNode) first.get("args");
        assertEquals("method", firstArgs.get("kind").asText());
        // Bottom-up: the higher section (startLine 20, "high") is extracted first.
        assertEquals("high", firstArgs.get("methodName").asText(), "extract bottom-up so offsets don't shift");
        assertEquals(20, firstArgs.get("startLine").asInt());
        assertEquals("low", ((JsonNode) ((Map<?, ?>) steps.get(1)).get("args")).get("methodName").asText());
    }

    @Test
    @DisplayName("replace_type_code_with_class requires newTypeName")
    void replaceTypeCode_needsNewTypeName() {
        assertFalse(tool.execute(plan("replace_type_code_with_class")).isSuccess());

        ObjectNode ok = plan("replace_type_code_with_class");
        ok.put("line", 3);
        ok.put("column", 6);
        ok.put("newTypeName", "OrderStatus");
        assertTrue(tool.execute(ok).isSuccess());
    }

    @Test
    @DisplayName("unknown plan kind and missing filePath are rejected")
    void rejectsBadInput() {
        assertFalse(tool.execute(plan("frobnicate")).isSuccess());

        ObjectNode noFile = mapper.createObjectNode();
        noFile.put("action", "plan");
        noFile.put("kind", "inline_singleton");
        assertFalse(tool.execute(noFile).isSuccess());
    }

    private ObjectNode section(int sl, int sc, int el, int ec, String name) {
        ObjectNode s = mapper.createObjectNode();
        s.put("startLine", sl);
        s.put("startColumn", sc);
        s.put("endLine", el);
        s.put("endColumn", ec);
        s.put("methodName", name);
        return s;
    }
}
