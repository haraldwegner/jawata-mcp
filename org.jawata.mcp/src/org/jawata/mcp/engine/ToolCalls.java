package org.jawata.mcp.engine;

import java.util.List;
import java.util.Map;

import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The one entry a face has into the engine's tools (Sprint 28k, E19; contract §4.3): a face names a tool and gives its arguments,
 * and every call goes through the same gates in the same order. A face that holds this and nothing else cannot reach a tool class,
 * the registry's setters or a second way to dispatch.
 *
 * <p>As built, the method names are the registry's own ({@code callTool}, {@code getToolDefinitions}); the contract's outline
 * wrote {@code call} and {@code definitions}, and renaming the registry's methods would touch every test that dispatches.</p>
 */
public interface ToolCalls {

    /**
     * Runs one tool through the gates.
     *
     * @param name      the tool's published name
     * @param arguments the arguments the caller gave
     * @param caller    who is asking, for the sessions and the recording
     * @return the answer, never null
     */
    ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller)
            throws ToolNotFoundException;

    /**
     * Runs one tool for the engine's own code rather than for a client's session.
     *
     * @param name      the tool's published name
     * @param arguments the arguments to give it
     * @return the answer, never null
     */
    default ToolResponse callTool(String name, JsonNode arguments) throws ToolNotFoundException {
        return callTool(name, arguments, CallerIdentity.IN_PROCESS);
    }

    /**
     * Describes every tool the engine publishes.
     *
     * @return one description per tool, in the order the engine publishes them
     */
    List<Map<String, Object>> getToolDefinitions();
}
