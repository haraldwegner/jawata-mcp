package org.goja.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.SearchSymbolsTool;
import org.goja.mcp.tools.ToolRegistry;
import org.goja.mcp.tools.ToolRegistry.ToolNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 22a P2 riders: (a) search_symbols retries a bare no-wildcard query as a
 * substring match; (b) an unknown renamed tool name gets a "did you mean" hint.
 */
class RidersTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("search_symbols: a bare substring that matches nothing exact is retried as *substring*")
    void bareSubstring_findsViaWildcardFallback() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("visibility");
        SearchSymbolsTool tool = new SearchSymbolsTool(() -> service);

        ObjectNode args = mapper.createObjectNode();
        args.put("query", "idget");   // substring of Widget; no wildcard, no exact match

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) ((Map<String, Object>) r.getData()).get("results");
        assertFalse(results.isEmpty(), "the wildcard fallback must find Widget for 'idget': " + results);
        assertTrue(results.stream().anyMatch(m -> "Widget".equals(m.get("name"))),
            "Widget must be among the results: " + results);
    }

    @Test
    @DisplayName("ToolRegistry: a renamed tool name is answered with a 'did you mean' hint")
    void unknownRenamedTool_suggestsNewName() {
        ToolRegistry registry = new ToolRegistry();
        ToolNotFoundException ex = assertThrows(ToolNotFoundException.class,
            () -> registry.callTool("get_type_members", mapper.createObjectNode()));
        assertTrue(ex.getMessage().contains("inspect"),
            "the not-found error must name the current front door: " + ex.getMessage());
    }
}
