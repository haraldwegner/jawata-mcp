package org.jawata.mcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, z67: a tool definition cut to the limit a client names. Measured against agy 1.5.15 on 2026-11-24: a definition of 3 012 characters
 * is offered to its model and one of 3 153 is not, so the engine's own long definitions (the memory tool is 20 000) never reached an Antigravity
 * principal, and its calls to them ended "tool hire is not enabled". What is pinned here is what the cutting must not lose: the parameter names,
 * the values a parameter may take, and the required parameters, and that a client that names no limit is given the definitions as they are.
 */
class ToolDefinitionFitTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode tool(int descriptionChars, int parameters, int parameterDescriptionChars) throws Exception {
        StringBuilder properties = new StringBuilder();
        for (int i = 0; i < parameters; i++) {
            properties.append(i == 0 ? "" : ",").append("\"p").append(i).append("\":{\"type\":\"string\",\"description\":\"").append("w ".repeat(parameterDescriptionChars / 2)).append("\"}");
        }
        properties.append(",\"kind\":{\"type\":\"string\",\"enum\":[\"record\",\"recall\"]}");
        return (ObjectNode) JSON.readTree("{\"name\":\"t\",\"description\":\"" + "word. ".repeat(descriptionChars / 6) + "\",\"inputSchema\":{\"type\":\"object\",\"properties\":{"
            + properties + "},\"required\":[\"p0\"]}}");
    }

    @Test
    void aDefinitionThatAlreadyFitsIsLeftAsItIs() throws Exception {
        ObjectNode small = tool(100, 3, 20);
        assertEquals(small, ToolDefinitionFit.fit(small, 5_000));
    }

    @Test
    void aLongDefinitionIsCutToTheLimitAndKeepsEveryParameterNameAndEveryAllowedValue() throws Exception {
        ObjectNode big = tool(3_000, 12, 400);
        assertTrue(ToolDefinitionFit.size(big) > 6_000, "the definition is long to begin with: " + ToolDefinitionFit.size(big));

        ObjectNode fitted = ToolDefinitionFit.fit(big, 2_000);

        assertTrue(ToolDefinitionFit.size(fitted) <= 2_000, "it fits: " + ToolDefinitionFit.size(fitted));
        JsonNode properties = fitted.path("inputSchema").path("properties");
        for (int i = 0; i < 12; i++) {
            assertTrue(properties.has("p" + i), "parameter p" + i + " is still named");
        }
        assertEquals("[\"record\",\"recall\"]", properties.path("kind").path("enum").toString(), "the values a parameter may take are not cut");
        assertEquals("[\"p0\"]", fitted.path("inputSchema").path("required").toString(), "the required parameters are not cut");
        assertTrue(ToolDefinitionFit.size(big) > 6_000, "the given definition is not changed");
    }

    @Test
    void aToolWithTooManyParametersLosesTheOnesDeclaredLastAndNeverKindOrTheRequiredOnes() throws Exception {
        ObjectNode many = tool(100, 80, 0);

        ObjectNode fitted = ToolDefinitionFit.fit(many, 1_200);

        JsonNode properties = fitted.path("inputSchema").path("properties");
        assertTrue(ToolDefinitionFit.size(fitted) <= 1_200, "it fits: " + ToolDefinitionFit.size(fitted));
        assertTrue(properties.has("p0") && properties.has("kind"), "the required parameter and kind stay");
        assertTrue(!properties.has("p79"), "a parameter declared late is left out");
    }

    @Test
    void aClientThatNamesNoLimitIsGivenTheDefinitionsAsPublished() throws Exception {
        List<Map<String, Object>> published = List.of(Map.of("name", "t", "description", "x".repeat(10_000)));

        assertSame(published.get(0), ToolDefinitionFit.fitAll(published, 0).get(0));
        assertEquals(0, ToolDefinitionFit.limitOf(JSON.readTree("{}")));
        assertEquals(0, ToolDefinitionFit.limitOf(null));
        assertEquals(2_000, ToolDefinitionFit.limitOf(JSON.readTree("{\"_meta\":{\"" + ToolDefinitionFit.META_KEY + "\":2000}}")));
    }
}
