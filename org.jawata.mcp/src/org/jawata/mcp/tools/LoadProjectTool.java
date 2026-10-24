package org.jawata.mcp.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.jawata.core.ModelSource;
import org.jawata.mcp.engine.EngineState;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.spi.host.HostWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Load a Java project for analysis.
 * MUST be called before using navigation/analysis tools.
 *
 * Adapted from src/main/java/dev/jawata/tools/LoadProjectTool.java
 */
public class LoadProjectTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(LoadProjectTool.class);

    private final ModelSource model;
    private final HostWorkspace workspace;

    /**
     * @param model     where the Java model comes from, and how a project comes into it
     * @param workspace whether the engine owns the workspace; when it does not, this verb is refused
     */
    public LoadProjectTool(ModelSource model, HostWorkspace workspace) {
        this.model = model;
        this.workspace = workspace;
    }

    @Override
    public String getName() {
        return "load_project";
    }

    @Override
    public String getDescription() {
        return """
            Load a Java project for analysis.
            MUST be called before using other analysis tools.

            USAGE: load_project(projectPath="/path/to/project")
            OUTPUT: Project structure summary including packages, source files, build system

            Supports:
            - Maven projects (pom.xml), including <sourceDirectory> / <testSourceDirectory> overrides
            - Gradle projects (build.gradle or build.gradle.kts)
            - Eclipse projects (.classpath src/lib entries honored when present)
            - Plain Java projects with src/ directory

            WORKFLOW:
            1. Call load_project with absolute path to project root
            2. Wait for project to load (may take a few seconds for large projects)
            3. Use health_check to verify project is loaded
            4. Begin using analysis tools (search_symbols, find_references, etc.)
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "projectPath", Map.of(
                "type", "string",
                "description", "Absolute path to the project root directory containing pom.xml or build.gradle"
            )
        ));
        schema.put("required", List.of("projectPath"));
        return schema;
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        if (!workspace.ownsWorkspace()) {
            return ToolResponse.hostOwnsWorkspace("load_project");
        }
        if (arguments == null || !arguments.has("projectPath")) {
            return ToolResponse.invalidParameter("projectPath", "Required parameter missing");
        }

        String projectPath = arguments.get("projectPath").asText();

        try {
            Path path = Path.of(projectPath).toAbsolutePath().normalize();

            // Validate path
            if (!Files.exists(path)) {
                return ToolResponse.error("FILE_NOT_FOUND",
                    "Project path not found: " + projectPath,
                    "Verify the path exists and is accessible");
            }

            if (!Files.isDirectory(path)) {
                return ToolResponse.error("INVALID_PARAMETER",
                    "Project path is not a directory: " + projectPath,
                    "Provide path to project root directory");
            }

            log.info("Loading project: {}", path);

            // The model reuses the service already in place, so add_project / remove_project / list_projects (and any
            // already-running analysis tools) keep operating on the same workspace; its load wipes what was loaded and
            // installs the new project as the default. Sprint 10 ADR Q6.
            IJdtService service = model.open(path);

            // mcp#35: the load succeeded, so whatever the BOOT concluded about the
            // workspace is now false. This path never wrote the state, so a failed boot
            // followed by a successful load left health_check reporting the boot's error
            // for the rest of the process — an alarm that outlived its cause and that
            // nothing could clear.
            EngineState.current().workspaceLoaded();

            // Build response from the project the load installed as the default
            LoadedProject loaded = service.defaultProjectKey().flatMap(service::getProject)
                .orElseThrow(() -> new IllegalStateException("the load installed no default project"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("loaded", true);
            result.put("projectPath", service.getPathUtils().formatPath(path));
            result.put("buildSystem", loaded.buildSystem().name().toLowerCase());
            result.put("sourceFileCount", loaded.sourceFileCount());
            result.put("packageCount", loaded.packageCount());

            // Include first 20 packages
            List<String> packages = loaded.packages();
            result.put("packages", packages.size() <= 20 ? packages : packages.subList(0, 20));

            result.put("classpathEntryCount", loaded.classpathEntryCount());
            result.put("loadedAt", loaded.loadedAt().toString());

            log.info("Project loaded successfully: {} files, {} packages",
                loaded.sourceFileCount(), loaded.packageCount());

            return ToolResponse.success(result);

        } catch (Exception e) {
            log.error("Failed to load project", e);
            return ToolResponse.internalError(e);
        }
    }
}
