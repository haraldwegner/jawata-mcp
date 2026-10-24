package org.jawata.mcp.tools.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jawata.core.IJdtService;
import org.jawata.core.ModelSource;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.LoadProjectTool;
import org.jawata.mcp.tools.ProjectTool;
import org.jawata.spi.host.HostWorkspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28k, E14: whether the engine may load, add or remove a project is the host's answer ({@code HostWorkspace#ownsWorkspace}),
 * not something a workspace verb decides for itself. A host that owns its workspace (an IDE) is followed, never driven.
 */
class HostOwnsWorkspaceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** A model that records every project it is asked to open. */
    private static final class RecordingModel implements ModelSource {
        final List<Path> opened = new ArrayList<>();

        @Override
        public IJdtService current() {
            return null;
        }

        @Override
        public IJdtService open(Path projectRoot) {
            opened.add(projectRoot);
            return null;
        }
    }

    private static HostWorkspace host(boolean owns) {
        return new HostWorkspace() {
            @Override
            public List<ProjectFolder> folders() {
                return List.of();
            }

            @Override
            public boolean ownsWorkspace() {
                return owns;
            }

            @Override
            public void onFoldersChanged(Runnable listener) {
            }
        };
    }

    @Test
    @DisplayName("load_project answers HOST_OWNS_WORKSPACE and opens nothing when the host owns the workspace")
    void loadProjectIsRefusedWhenTheHostOwnsTheWorkspace() {
        RecordingModel model = new RecordingModel();
        LoadProjectTool tool = new LoadProjectTool(model, host(false));

        ToolResponse r = tool.execute(OM.createObjectNode().put("projectPath", System.getProperty("java.io.tmpdir")));

        assertFalse(r.isSuccess());
        assertEquals("HOST_OWNS_WORKSPACE", r.getError().getCode());
        assertTrue(model.opened.isEmpty(), "the model must not be asked to open a project: " + model.opened);
    }

    @Test
    @DisplayName("project add and remove answer HOST_OWNS_WORKSPACE when the host owns the workspace")
    void addAndRemoveAreRefusedWhenTheHostOwnsTheWorkspace() {
        ProjectTool tool = new ProjectTool(() -> null, host(false));

        ObjectNode add = OM.createObjectNode().put("action", "add").put("projectPath", System.getProperty("java.io.tmpdir"));
        ObjectNode remove = OM.createObjectNode().put("action", "remove").put("projectKey", "anything");

        assertEquals("HOST_OWNS_WORKSPACE", tool.execute(add).getError().getCode());
        assertEquals("HOST_OWNS_WORKSPACE", tool.execute(remove).getError().getCode());
    }

    @Test
    @DisplayName("project list is not refused: reading the workspace is always allowed")
    void listIsNotRefused() {
        ProjectTool tool = new ProjectTool(() -> null, host(false));

        ToolResponse r = tool.execute(OM.createObjectNode().put("action", "list"));

        assertTrue(r.getError() == null || !"HOST_OWNS_WORKSPACE".equals(r.getError().getCode()),
            "list must not answer HOST_OWNS_WORKSPACE: " + r.getError());
    }

    @Test
    @DisplayName("load_project asks the model to open the normalised path when the engine owns the workspace")
    void loadProjectOpensThroughTheModelWhenTheEngineOwnsTheWorkspace() throws Exception {
        RecordingModel model = new RecordingModel();
        LoadProjectTool tool = new LoadProjectTool(model, host(true));
        Path dir = java.nio.file.Files.createTempDirectory("host-owns-workspace");
        try {
            tool.execute(OM.createObjectNode().put("projectPath", dir.toString()));

            assertEquals(List.of(dir.toAbsolutePath().normalize()), model.opened);
        } finally {
            java.nio.file.Files.deleteIfExists(dir);
        }
    }
}
