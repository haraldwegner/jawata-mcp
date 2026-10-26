package org.jawata.resident;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.jawata.mcp.engine.DefaultEngineDirectories;
import org.jawata.mcp.engine.SettingKeys;
import org.jawata.resident.workspace.WorkspaceJson;
import org.jawata.spi.host.DeclaredWorkspace;
import org.jawata.spi.host.EngineSettings;

/**
 * The folders of the headless resident (Sprint 28k, Stage 8; ledger 28k-C7-5): the engine's folder layout, plus what only a resident
 * started from a launcher knows, which is the {@code workspace.json} the studio wrote and, failing that, the single project folder it was
 * given in the environment.
 */
public final class ResidentDirectories extends DefaultEngineDirectories {

    /**
     * @param settings where the named folders and the workspace root are read from
     */
    public ResidentDirectories(EngineSettings settings) {
        super(settings);
    }

    /**
     * The workspace's name and project folders from {@code workspace.json}; else the one folder the environment names, which has no name.
     *
     * @return what the launcher told this process, empty when it told it nothing
     */
    @Override
    public Optional<DeclaredWorkspace> declared() {
        Path dataDir = resolveDataDir();
        Path workspaceJson = dataDir == null ? null : findWorkspaceJson(dataDir);
        if (workspaceJson != null) {
            return Optional.of(new DeclaredWorkspace(WorkspaceJson.readProvenance(workspaceJson)[0],
                WorkspaceJson.readProjects(workspaceJson)));
        }
        String envRoot = SettingKeys.PROJECT_PATH.value().orElse(null);
        if (envRoot != null && !envRoot.isBlank()) {
            return Optional.of(new DeclaredWorkspace(null, List.of(Path.of(envRoot).toAbsolutePath().normalize())));
        }
        return Optional.empty();
    }

    /**
     * The project folders {@code workspace.json} lists: the one at the workspace root when there is one, else the one the walk up from
     * the data folder finds.
     */
    @Override
    protected List<Path> projectDirs() {
        Path dataDir = resolveDataDir();
        Path root = stableWorkspaceRoot(dataDir);
        Path workspaceJson = root != null && Files.isRegularFile(root.resolve("workspace.json"))
            ? root.resolve("workspace.json")
            : findWorkspaceJson(dataDir);
        return workspaceJson == null ? List.of() : WorkspaceJson.readProjects(workspaceJson);
    }
}
