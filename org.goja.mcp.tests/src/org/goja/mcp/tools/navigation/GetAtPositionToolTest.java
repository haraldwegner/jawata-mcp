package org.goja.mcp.tools.navigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.AbstractTool;
import org.goja.mcp.tools.GetAtPositionTool;
import org.goja.mcp.tools.GetEnclosingElementTool;
import org.goja.mcp.tools.GetFieldAtPositionTool;
import org.goja.mcp.tools.GetHoverInfoTool;
import org.goja.mcp.tools.GetJavadocTool;
import org.goja.mcp.tools.GetMethodAtPositionTool;
import org.goja.mcp.tools.GetSignatureHelpTool;
import org.goja.mcp.tools.GetSuperMethodTool;
import org.goja.mcp.tools.GetSymbolInfoTool;
import org.goja.mcp.tools.GetTypeAtPositionTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 16b/A — verifies the parametric {@code get_at_position} front door
 * routes each {@code kind} to the correct narrow delegate. Strategy: routing
 * PARITY — at one position, the parametric output must byte-equal the narrow
 * tool's output for that kind, whether or not the position resolves. This proves
 * dispatch without re-testing each narrow tool's deep behaviour.
 */
class GetAtPositionToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetAtPositionTool tool;
    private ObjectMapper mapper;
    private String calculatorPath;
    private Map<String, AbstractTool> narrowByKind;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetAtPositionTool(() -> service);
        mapper = new ObjectMapper();
        Path projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();

        narrowByKind = new LinkedHashMap<>();
        narrowByKind.put("type", new GetTypeAtPositionTool(() -> service));
        narrowByKind.put("method", new GetMethodAtPositionTool(() -> service));
        narrowByKind.put("field", new GetFieldAtPositionTool(() -> service));
        narrowByKind.put("hover", new GetHoverInfoTool(() -> service));
        narrowByKind.put("javadoc", new GetJavadocTool(() -> service));
        narrowByKind.put("signature", new GetSignatureHelpTool(() -> service));
        narrowByKind.put("enclosing", new GetEnclosingElementTool(() -> service));
        narrowByKind.put("super", new GetSuperMethodTool(() -> service));
        narrowByKind.put("symbol", new GetSymbolInfoTool(() -> service));
    }

    private ObjectNode pos(String kind, int line, int column) {
        ObjectNode n = mapper.createObjectNode();
        if (kind != null) n.put("kind", kind);
        n.put("filePath", calculatorPath);
        n.put("line", line);
        n.put("column", column);
        return n;
    }

    @Test
    @DisplayName("schema advertises all nine kinds and requires kind")
    @SuppressWarnings("unchecked")
    void schema_lists_all_nine_kinds() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Map<String, Object> kind = (Map<String, Object>) props.get("kind");
        java.util.List<String> kinds = (java.util.List<String>) kind.get("enum");
        assertEquals(9, kinds.size(), "all nine views present");
        assertTrue(kinds.containsAll(java.util.List.of(
            "type", "method", "field", "hover", "javadoc",
            "signature", "enclosing", "super", "symbol")));
        java.util.List<String> required = (java.util.List<String>) schema.get("required");
        assertTrue(required.contains("kind"), "kind is required");
    }

    @Test
    @DisplayName("every kind routes to its narrow delegate (output parity at the type position)")
    void every_kind_routes_to_narrow_delegate() {
        // Calculator type declaration (5,13). Some kinds resolve, some don't —
        // either way the parametric output must equal the narrow delegate's.
        for (Map.Entry<String, AbstractTool> e : narrowByKind.entrySet()) {
            String kind = e.getKey();
            ToolResponse viaParam = tool.execute(pos(kind, 5, 13));
            ToolResponse viaNarrow = e.getValue().execute(pos(kind, 5, 13));

            assertEquals(viaNarrow.isSuccess(), viaParam.isSuccess(),
                "kind=" + kind + " success must match the narrow delegate");

            JsonNode narrowPayload = mapper.valueToTree(
                viaNarrow.isSuccess() ? viaNarrow.getData() : viaNarrow.getError());
            JsonNode paramPayload = mapper.valueToTree(
                viaParam.isSuccess() ? viaParam.getData() : viaParam.getError());
            assertEquals(narrowPayload, paramPayload,
                "kind=" + kind + " payload must equal the narrow delegate's");
        }
    }

    @Test
    @DisplayName("kind=type at the class declaration resolves the type")
    @SuppressWarnings("unchecked")
    void type_kind_resolves_calculator() {
        ToolResponse r = tool.execute(pos("type", 5, 13));
        assertTrue(r.isSuccess(), "type kind should resolve at the class declaration");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("com.example.Calculator", data.get("qualifiedName"));
    }

    @Test
    @DisplayName("missing kind returns INVALID_PARAMETER")
    void missing_kind_invalid() {
        assertFalse(tool.execute(pos(null, 5, 13)).isSuccess());
    }

    @Test
    @DisplayName("unknown kind returns INVALID_PARAMETER, not a stack trace")
    void unknown_kind_invalid() {
        assertFalse(tool.execute(pos("typo", 5, 13)).isSuccess());
    }
}
