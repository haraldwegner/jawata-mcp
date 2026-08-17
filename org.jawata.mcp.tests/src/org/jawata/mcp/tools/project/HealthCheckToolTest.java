package org.jawata.mcp.tools.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.mcp.ProjectLoadingState;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.HealthCheckTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HealthCheckToolTest {

    private HealthCheckTool toolWithProject;
    private HealthCheckTool toolWithoutProject;
    private HealthCheckTool toolLoading;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        toolWithProject = new HealthCheckTool(() -> true, () -> 56,
            () -> ProjectLoadingState.LOADED, () -> null);
        toolWithoutProject = new HealthCheckTool(() -> false, () -> 56,
            () -> ProjectLoadingState.NOT_LOADED, () -> null);
        toolLoading = new HealthCheckTool(() -> false, () -> 56,
            () -> ProjectLoadingState.LOADING, () -> null);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("returns complete health status when project loaded")
    void returnsCompleteHealthStatusWithProject() {
        ToolResponse r = toolWithProject.execute(objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Status
        assertEquals("Ready", data.get("status"));
        assertNotNull(data.get("message"));
        assertNotNull(data.get("version"));
        // bugs.md #14: version comes from McpProtocolHandler.serverVersion()
        // (bundle manifest), never the old hardcoded "2.0.0-SNAPSHOT". On the
        // plain-classpath test runtime that resolves to "unknown".
        assertNotEquals("2.0.0-SNAPSHOT", data.get("version"),
            "health_check version must not be the hardcoded placeholder");
        assertNotNull(data.get("uptime"));

        // Project info
        @SuppressWarnings("unchecked")
        Map<String, Object> project = (Map<String, Object>) data.get("project");
        assertTrue((Boolean) project.get("loaded"));

        // Java/OS info
        assertNotNull(data.get("java"));
        assertNotNull(data.get("os"));

        // Capabilities
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilities = (Map<String, Object>) data.get("capabilities");
        assertTrue((Boolean) capabilities.get("findReferences"));
        assertTrue((Boolean) capabilities.get("refactoring"));

        // Tool count
        assertEquals(56, data.get("toolCount"));
    }

    @Test @DisplayName("returns waiting status when no project loaded")
    void returnsWaitingStatusWithoutProject() {
        ToolResponse r = toolWithoutProject.execute(objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("Waiting for project", data.get("status"));

        @SuppressWarnings("unchecked")
        Map<String, Object> project = (Map<String, Object>) data.get("project");
        assertFalse((Boolean) project.get("loaded"));
    }

    @Test @DisplayName("always succeeds with no parameters")
    void alwaysSucceedsWithNoParameters() {
        assertTrue(toolWithProject.execute(null).isSuccess());
        assertTrue(toolWithoutProject.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test
    @DisplayName("mcp#29: nothing loaded after a FAILED load is NOT healthy — and says why")
    void aWorkspaceWhereEverythingFailedToLoadIsUnhealthy() {
        // The defect: health was computed over the loaded-project list, and a
        // total failure leaves that list EMPTY — "no unhealthy projects" then
        // read as healthy=true, in the same response whose project.status
        // already said "failed". A caller trusting the documented contract
        // ("false gates analyses and refactorings") treated a dead workspace as
        // fine and got empty answers.
        String reason = "all 1 workspace project(s) FAILED to load — first: jawata-mcp: "
            + "Maven resolution failed. Remedy: run the project's build once.";
        HealthCheckTool tool = new HealthCheckTool(() -> false, () -> 56,
            () -> ProjectLoadingState.FAILED, () -> reason,
            () -> new org.jawata.core.JdtServiceImpl());

        @SuppressWarnings("unchecked")
        Map<String, Object> workspace = (Map<String, Object>)
            getData(tool.execute(objectMapper.createObjectNode())).get("workspace");

        assertEquals(0, workspace.get("projectCount"), "nothing loaded — that is the premise");
        assertEquals(Boolean.FALSE, workspace.get("healthy"),
            "an empty workspace after a failed load is the one case the flag exists for: "
                + workspace);
        assertTrue(String.valueOf(workspace.get("warning")).contains("Maven resolution failed"),
            "and the warning carries the REASON, so the user knows what to fix: " + workspace);
    }

    @Test
    @DisplayName("an empty workspace that never tried to load is not branded unhealthy")
    void anEmptyButNotFailedWorkspaceStaysHealthy() {
        // The guard must not cry wolf: a server started before any project was
        // loaded is waiting, not broken.
        HealthCheckTool tool = new HealthCheckTool(() -> false, () -> 56,
            () -> ProjectLoadingState.NOT_LOADED, () -> null,
            () -> new org.jawata.core.JdtServiceImpl());

        @SuppressWarnings("unchecked")
        Map<String, Object> workspace = (Map<String, Object>)
            getData(tool.execute(objectMapper.createObjectNode())).get("workspace");

        assertEquals(Boolean.TRUE, workspace.get("healthy"), "got: " + workspace);
    }
}
