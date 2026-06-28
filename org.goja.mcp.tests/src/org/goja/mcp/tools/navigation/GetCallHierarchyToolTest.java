package org.goja.mcp.tools.navigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.GetCallHierarchyIncomingTool;
import org.goja.mcp.tools.GetCallHierarchyOutgoingTool;
import org.goja.mcp.tools.GetCallHierarchyTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 16b/A — verifies the parametric {@code get_call_hierarchy} front door
 * routes {@code direction=incoming|outgoing} to the correct narrow delegate
 * (output parity), and rejects missing/unknown directions.
 */
class GetCallHierarchyToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetCallHierarchyTool tool;
    private GetCallHierarchyIncomingTool incoming;
    private GetCallHierarchyOutgoingTool outgoing;
    private ObjectMapper mapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetCallHierarchyTool(() -> service);
        incoming = new GetCallHierarchyIncomingTool(() -> service);
        outgoing = new GetCallHierarchyOutgoingTool(() -> service);
        mapper = new ObjectMapper();
        Path projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    private ObjectNode args(String direction) {
        ObjectNode n = mapper.createObjectNode();
        if (direction != null) n.put("direction", direction);
        n.put("filePath", calculatorPath);
        n.put("line", 14);   // the `add` method
        n.put("column", 15);
        return n;
    }

    @Test
    @DisplayName("direction=incoming routes to the callers delegate (parity)")
    void incoming_routes_to_narrow() {
        ToolResponse viaParam = tool.execute(args("incoming"));
        ToolResponse viaNarrow = incoming.execute(args("incoming"));
        assertTrue(viaParam.isSuccess());
        assertEquals(mapper.valueToTree(viaNarrow.getData()),
            mapper.valueToTree(viaParam.getData()));
    }

    @Test
    @DisplayName("direction=outgoing routes to the callees delegate (parity)")
    void outgoing_routes_to_narrow() {
        ToolResponse viaParam = tool.execute(args("outgoing"));
        ToolResponse viaNarrow = outgoing.execute(args("outgoing"));
        assertTrue(viaParam.isSuccess());
        assertEquals(mapper.valueToTree(viaNarrow.getData()),
            mapper.valueToTree(viaParam.getData()));
    }

    @Test
    @DisplayName("incoming on the `add` method reports the method name")
    @SuppressWarnings("unchecked")
    void incoming_reports_method() {
        Map<String, Object> data = (Map<String, Object>) tool.execute(args("incoming")).getData();
        assertEquals("add", data.get("method"));
    }

    @Test
    @DisplayName("missing direction returns INVALID_PARAMETER")
    void missing_direction_invalid() {
        assertFalse(tool.execute(args(null)).isSuccess());
    }

    @Test
    @DisplayName("unknown direction returns INVALID_PARAMETER")
    void unknown_direction_invalid() {
        assertFalse(tool.execute(args("sideways")).isSuccess());
    }
}
