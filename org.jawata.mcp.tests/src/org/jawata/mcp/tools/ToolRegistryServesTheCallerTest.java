package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h, S6 (D4): a tool that must tell its callers apart, the hire continuing the brain session of the caller's own earlier hire,
 * reads who is calling from {@link CallerIdentity#serving()}. That only works if the registry serves every call under its caller, so this
 * is the test of the wire, not of the tool: a tool run through {@code callTool} sees the caller that made the call, and nobody is left
 * serving once the call has returned.
 */
class ToolRegistryServesTheCallerTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** The callers a tool saw while it ran, in order. */
    private final List<Optional<CallerIdentity>> seen = new ArrayList<>();

    private ToolRegistry registryWithAWatchingTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String getName() {
                return "who_is_calling";
            }
            @Override public String getDescription() {
                return "a stand-in tool that notes who is calling it while it runs";
            }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of();
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                seen.add(CallerIdentity.serving());
                return ToolResponse.success(Map.of("seen", true));
            }
        });
        return registry;
    }

    @Test
    @DisplayName("a tool run through the registry sees the caller that made the call, each call its own, and nobody is serving after the call returns")
    void aToolSeesItsOwnCallerAndNobodyIsLeftServing() throws Exception {
        ToolRegistry registry = registryWithAWatchingTool();
        JsonNode none = OM.readTree("{}");

        registry.callTool("who_is_calling", none, CallerIdentity.supplied("caller-one"));
        registry.callTool("who_is_calling", none, CallerIdentity.supplied("caller-two"));

        assertAll(
            () -> assertEquals(List.of(Optional.of(CallerIdentity.supplied("caller-one")), Optional.of(CallerIdentity.supplied("caller-two"))), seen),
            () -> assertTrue(CallerIdentity.serving().isEmpty(), "a caller was left serving on this thread after its call returned"));
    }
}
