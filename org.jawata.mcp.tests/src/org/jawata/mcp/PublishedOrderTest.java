package org.jawata.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Where the 44 sit in the published list, and where a tool that is not one of them goes (Sprint 28k, E12). The whole list against the
 * golden is {@code TheFortyFourPublishedToolsTest}; this holds the rules of the arrangement itself, with stub tools.
 */
class PublishedOrderTest {

    private static Tool stub(String name) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "stub"; }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                return ToolResponse.success(Map.of());
            }
        };
    }

    private static List<Tool> stubs(List<String> names) {
        List<Tool> tools = new ArrayList<>();
        names.forEach(name -> tools.add(stub(name)));
        return tools;
    }

    private static List<String> names(List<Tool> tools) {
        return tools.stream().map(Tool::getName).toList();
    }

    @Test
    @DisplayName("tools named among the 44 come out in the order a client sees them, whatever order they arrive in")
    void theNamedToolsComeOutInPublishedOrder() {
        List<Tool> arranged = PublishedOrder.arrange(stubs(List.of("experience", "hire", "health_check", "search_symbols")));

        assertEquals(List.of("health_check", "search_symbols", "hire", "experience"), names(arranged));
    }

    @Test
    @DisplayName("a tool the 44 do not name follows them, in the order it arrived")
    void anUnnamedToolFollowsTheNamedOnes() {
        List<Tool> arranged = PublishedOrder.arrange(
            stubs(List.of("cpp_build", "experience", "cpp_index", "health_check")));

        assertEquals(List.of("health_check", "experience", "cpp_build", "cpp_index"), names(arranged));
    }

    @Test
    @DisplayName("two tools answering to one name are refused, not resolved by letting the later one win")
    void twoToolsWithOneNameAreRefused() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> PublishedOrder.arrange(stubs(List.of("health_check", "inspect", "health_check"))));

        assertEquals("two tools answer to health_check", refused.getMessage());
    }
}
