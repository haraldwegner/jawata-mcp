package org.jawata.mcp.tools;

import java.util.Optional;
import java.util.function.Supplier;

import org.jawata.mcp.engine.EngineState;
import org.jawata.mcp.engine.ProjectLoadingState;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.spi.lang.LoadedWorkspace;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A tool that answers about the projects a language has loaded (Sprint 28k, Stage 9): the one place the gate "is there a project to
 * answer from" and the optional {@code projectKey} scoping are done, over whatever workspace the language holds. The tools that name no
 * language extend it over {@link LoadedWorkspace}; Java's tools reach it through {@link AbstractTool}, whose workspace is Java's own
 * service.
 *
 * <p><b>The gate is "are there projects to answer from", not "is the workspace object present" (mcp#28).</b> A workspace whose every
 * project FAILED to load still has a workspace, empty, and a tool that ran against nothing answered INTERNAL_ERROR "this may be a bug"
 * on exactly the path a redirected agent lands on. Reading emptiness rather than the loading enum also heals itself, because a later
 * successful load makes the predicate false without anyone clearing a flag. The loading state still chooses the WORDS: loading,
 * failed-with-reason, or nothing-loaded.</p>
 *
 * @param <W> the kind of workspace the tool is given
 */
public abstract class WorkspaceTool<W extends LoadedWorkspace> extends NeutralTool {

    private final Supplier<? extends W> workspaceSupplier;

    /** @param workspaceSupplier the language's loaded workspace, read at call time because a load replaces it */
    protected WorkspaceTool(Supplier<? extends W> workspaceSupplier) {
        this.workspaceSupplier = workspaceSupplier;
    }

    /**
     * Whether this tool needs at least one readable project. Nearly every tool does: it answers about code. The exceptions are the
     * RUNTIME tools, which discover and attach to JVMs or read a crash log, both meaningful with no project loaded at all.
     */
    protected boolean requiresLoadedProject() {
        return true;
    }

    /**
     * What the tool does once the gate has passed.
     *
     * @param workspace the workspace, narrowed to one project when the call named one; null only for a tool that declared it needs no
     *                  project and found none
     * @param arguments the call's arguments
     * @return the answer
     */
    protected abstract ToolResponse executeWithWorkspace(W workspace, JsonNode arguments);

    /**
     * Narrows a workspace to one project.
     *
     * @param workspace the whole workspace
     * @param key       a project that is loaded
     * @return a view of the workspace holding only that project
     */
    @SuppressWarnings("unchecked")
    protected W narrowedTo(W workspace, String key) {
        return (W) workspace.scoped(key);
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        W workspace = workspaceSupplier.get();

        // Sprint 10: optional projectKey scoping. When the caller passes projectKey, the view narrows to just that project, so every
        // lookup operates in single-project scope. Absent: the cross-project default.
        String projectKey = null;
        if (arguments != null && arguments.has("projectKey") && !arguments.get("projectKey").isNull()) {
            String raw = arguments.get("projectKey").asText();
            if (raw != null && !raw.isBlank()) {
                projectKey = raw;
            }
        }

        // A DROPPED key is knowledge only the workspace holds, and it outranks "nothing is loaded" — the caller named a key that WAS
        // valid, and being told when it went away is what lets them recover (bugs.md #11). Answering the emptiness first would replace
        // that with a generic not-loaded.
        if (workspace != null && projectKey != null && workspace.project(projectKey).isEmpty()) {
            Optional<Long> dropped = workspace.wasRecentlyDropped(projectKey);
            if (dropped.isPresent()) {
                return ToolResponse.projectKeyDropped(projectKey, dropped.get());
            }
        }

        if (requiresLoadedProject() && (workspace == null || workspace.projects().isEmpty())) {
            ProjectLoadingState loadingState = EngineState.current().loading();
            return switch (loadingState) {
                case LOADING -> ToolResponse.projectLoading();
                case FAILED -> ToolResponse.projectLoadFailed(EngineState.current().loadingError());
                default -> ToolResponse.projectNotLoaded();
            };
        }
        if (workspace == null) {
            // Only reachable for a tool that DECLARED it needs no project: it runs, with a null workspace it never reads. Sprint 28b:
            // this used to answer PROJECT_NOT_LOADED anyway, which silently undid the override for debug, profile and field.
            return executeWithWorkspace(null, arguments);
        }

        if (projectKey != null) {
            if (workspace.project(projectKey).isEmpty()) {
                // Not dropped (that answered above) and not present: a key that never existed here. Name the recovery route rather than
                // the typo.
                return ToolResponse.invalidParameter("projectKey",
                    "Unknown projectKey '" + projectKey + "'. Use project(action=list) to see available keys.");
            }
            return executeWithWorkspace(narrowedTo(workspace, projectKey), arguments);
        }

        return executeWithWorkspace(workspace, arguments);
    }
}
