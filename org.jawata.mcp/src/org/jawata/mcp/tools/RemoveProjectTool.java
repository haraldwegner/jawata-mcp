package org.jawata.mcp.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.spi.host.HostWorkspace;
import org.jawata.spi.lang.LoadedWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Remove a project from the workspace without restarting the MCP service.
 * Used by jawata-studio when a user removes a project from a running
 * port-grouped workspace (Sprint 10 live update).
 *
 * <p>USAGE: <code>remove_project(projectKey="payments-api")</code>
 *
 * <p>OUTPUT: { projectKey, removed, newDefaultProjectKey }
 *
 * <p>Primarily for orchestration by jawata-studio. Idempotent: removing
 * an unknown key returns {removed: false} without error.
 */
public class RemoveProjectTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RemoveProjectTool.class);

    private final Supplier<? extends LoadedWorkspace> serviceSupplier;

    private final HostWorkspace workspace;

    /**
     * @param serviceSupplier the language's loaded workspace, read at call time
     * @param workspace       whether the engine may change the workspace; when it may not, the tool answers HOST_OWNS_WORKSPACE
     */
    public RemoveProjectTool(Supplier<? extends LoadedWorkspace> serviceSupplier, HostWorkspace workspace) {
        this.serviceSupplier = serviceSupplier;
        this.workspace = workspace;
    }

    @Override
    public String getName() {
        return "remove_project";
    }

    @Override
    public String getDescription() {
        return """
            Remove a project from the workspace without restarting the MCP
            service. Idempotent — removing an unknown key returns
            {removed: false} without error.

            USAGE: remove_project(projectKey="payments-api")
            OUTPUT: { projectKey, removed, newDefaultProjectKey }

            If the removed project was the default (the one tools use when
            no projectKey is specified), the next available project becomes
            the default. If no projects remain, the service is left empty
            and most analysis tools will report "no project loaded" until
            load_project or add_project is called.

            Primarily intended for orchestration by jawata-studio.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "projectKey", Map.of(
                "type", "string",
                "description", "Project key returned by load_project, add_project, or list_projects"
            )
        ));
        schema.put("required", List.of("projectKey"));
        return schema;
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        if (!workspace.ownsWorkspace()) {
            return ToolResponse.hostOwnsWorkspace("project remove");
        }
        if (arguments == null || !arguments.has("projectKey")) {
            return ToolResponse.invalidParameter("projectKey", "Required parameter missing");
        }
        LoadedWorkspace service = serviceSupplier.get();
        if (service == null) {
            return ToolResponse.error(
                "PROJECT_NOT_LOADED",
                "No workspace exists yet. There is nothing to remove.",
                "Call load_project to create a workspace before invoking project(action=remove).");
        }

        String projectKey = arguments.get("projectKey").asText();

        try {
            log.info("Removing project '{}' from workspace", projectKey);
            boolean removed = service.removeProject(projectKey);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("projectKey", projectKey);
            result.put("removed", removed);
            service.defaultProjectKey().ifPresent(k -> result.put("newDefaultProjectKey", k));
            return ToolResponse.success(result);

        } catch (Exception e) {
            log.error("Failed to remove project", e);
            return ToolResponse.internalError(e);
        }
    }
}
