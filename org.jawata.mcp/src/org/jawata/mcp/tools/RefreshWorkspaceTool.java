package org.jawata.mcp.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.spi.host.BuildKind;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.host.Reconciler;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.Diagnostic;
import org.jawata.spi.lang.LoadedWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sprint 14 Phase B.1 (v1.8.0) — {@code refresh_workspace}: consolidated
 * lifecycle tool that does ALL THREE of the steps the EXECSIM-Java sessions
 * surfaced repeatedly:
 *
 * <ol>
 *   <li><b>Refresh from disk</b> — {@code IResource.refreshLocal(DEPTH_INFINITE)}
 *       per loaded project so files created or edited externally
 *       (Write/Edit, manual touch, manager-side mutation) are visible to
 *       JDT. Sidesteps the WorkspaceFileWatcher gap from bugs.md #6.</li>
 *   <li><b>Invalidate JDT's incremental compile cache</b> —
 *       {@code IncrementalProjectBuilder.CLEAN_BUILD} then
 *       {@code FULL_BUILD}. Same path as {@code compile_workspace(clean=true)}
 *       (bugs.md #8) but always-clean by contract.</li>
 *   <li><b>Preserve {@code projectKey} state</b> — does NOT drop or rotate
 *       keys. Closes the bugs.md #11 trap where a caller's projectKey went
 *       stale after the only available "refresh" knob
 *       ({@code remove_project} + {@code add_project}) re-derived a new
 *       key.</li>
 * </ol>
 *
 * <p>Optional {@code projectKey} narrows scope to one loaded project. Omit
 * for full-workspace refresh.</p>
 *
 * <p>Returns the post-rebuild diagnostics in the same shape as
 * {@code compile_workspace}'s output, plus {@code refreshedProjects} and a
 * {@code summary} block with {@code filesRefreshed},
 * {@code classFilesInvalidated}, {@code errorCount}, {@code warningCount}.</p>
 */
public class RefreshWorkspaceTool extends WorkspaceTool<LoadedWorkspace> {

    private static final Logger log = LoggerFactory.getLogger(RefreshWorkspaceTool.class);

    /** The host's reconciler, which refreshes and builds; read at call time (28k E6). */
    private final Supplier<Reconciler> reconciler;

    /** The language's compile check, read at call time (28k E10b). */
    private final Supplier<BuildVerdict> verdict;

    /**
     * @param workspace  the language's loaded workspace
     * @param verdict    the language's compile check
     * @param reconciler the host's way to refresh and build
     */
    public RefreshWorkspaceTool(Supplier<? extends LoadedWorkspace> workspace, Supplier<BuildVerdict> verdict,
            Supplier<Reconciler> reconciler) {
        super(workspace);
        this.verdict = verdict;
        this.reconciler = reconciler;
    }

    @Override
    public String getName() {
        return "refresh_workspace";
    }

    @Override
    public String getDescription() {
        return """
            Reconcile the JDT workspace state against disk: refresh resources,
            invalidate the incremental compile cache, full-rebuild, return
            aggregated diagnostics. Preserves projectKey state across calls.

            USAGE:
              refresh_workspace()
              refresh_workspace(projectKey="core")

            Inputs:
            - projectKey — optional. Refresh just that project; default is the
              full workspace.

            Steps performed per project:
            1. IResource.refreshLocal(DEPTH_INFINITE) — pick up files
               created/edited externally (Write/Edit/touch/manager mutation)
               that the file watcher may have missed (bugs.md #6).
            2. CLEAN_BUILD then FULL_BUILD — invalidate JDT's incremental
               cache so signature-shape changes surface as real errors
               (bugs.md #8).
            3. Collect IMarker.PROBLEM markers from the rebuilt projects.

            Result:
              { operation, wiringRecomputed (the bundle-pool caches were
                cleared and the workspace resolve phase re-ran),
                refreshedProjects: [...],
                summary: { filesRefreshed, classFilesInvalidated,
                           errorCount, warningCount },
                diagnostics: [{filePath, line, column, severity, message,
                              sourceProject}] }

            projectKey-state contract: this tool does NOT call addProject /
            removeProject internally. Any projectKey held by the caller
            remains valid across the refresh (bugs.md #11 corollary).
            Compilation errors are a normal RESULT, not a tool failure.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithWorkspace(LoadedWorkspace service, JsonNode arguments) {
        // WorkspaceTool validated `projectKey` if present and narrowed the workspace to that one project; absent, it is the whole
        // multi-project view. Either way the projects it lists are the ones to refresh.
        Collection<LoadedWorkspace.ProjectInfo> projects = service.projects();

        try {
            // Stage 12.2 (audit N2): a reconcile that cleared the bundle-pool
            // cache but kept the old wiring would silently contradict this
            // tool's contract — so the resolve phase re-runs, and the
            // response SAYS so.
            service.reresolveWorkspace();
            int errorCount = 0;
            int warningCount = 0;
            int filesRefreshed = 0;
            int classFilesInvalidated = 0;
            List<Map<String, Object>> diagnostics = new ArrayList<>();
            List<String> refreshedProjects = new ArrayList<>();
            // Sprint 28k (E6, C1 architect F1): ONE rebuild over the selected projects; a project the host's
            // reconciler did not answer as built is named in notRebuilt and counted as an error.
            List<String> names = projects.stream().map(LoadedWorkspace.ProjectInfo::buildName).toList();
            Set<String> built;
            try {
                built = reconciler.get().rebuild(new BuildScope(names), BuildKind.CLEAN_FULL, new NullProgressMonitor());
            } catch (Exception e) {
                log.warn("Refresh + rebuild failed: {}", e.getMessage(), e);
                built = Set.of();
            }
            List<String> notRebuilt = new ArrayList<>();
            // Sprint 28k (E10b): the problem rows come from the language's verdict, read after the build.
            BuildVerdict language = verdict.get();
            Map<String, List<Diagnostic>> problemRows = CompileWorkspaceTool.byProject(
                language.diagnostics(new BuildScope(names), new NullProgressMonitor()));

            for (LoadedWorkspace.ProjectInfo loaded : projects) {
                String buildName = loaded.buildName();
                refreshedProjects.add(loaded.key());

                // Approximate per-project file count (sum of compilation units
                // across source roots). Reported as filesRefreshed + as
                // classFilesInvalidated since CLEAN_BUILD invalidates every
                // class file for that project's sources.
                int projectFileCount = service.unitCount(loaded.key());
                filesRefreshed += projectFileCount;
                classFilesInvalidated += projectFileCount;

                if (!built.contains(buildName)) {
                    notRebuilt.add(loaded.key());   // markers are still collected: partial results are useful
                }

                for (Diagnostic row : problemRows.getOrDefault(buildName, List.of())) {
                    if (row.severity().compareTo(Diagnostic.Severity.WARNING) < 0) continue;
                    diagnostics.add(CompileWorkspaceTool.describe(row, loaded, service));
                    if (row.severity() == Diagnostic.Severity.ERROR) errorCount++;
                    else if (row.severity() == Diagnostic.Severity.WARNING) warningCount++;
                }
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("filesRefreshed", filesRefreshed);
            summary.put("classFilesInvalidated", classFilesInvalidated);
            summary.put("errorCount", errorCount + notRebuilt.size());
            summary.put("warningCount", warningCount);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "refresh_workspace");
            data.put("wiringRecomputed", true);
            data.put("refreshedProjects", refreshedProjects);
            if (!notRebuilt.isEmpty()) {
                data.put("notRebuilt", notRebuilt);
            }
            data.put("summary", summary);
            data.put("diagnostics", diagnostics);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(diagnostics.size())
                .returnedCount(diagnostics.size())
                .build());
        } catch (Exception e) {
            log.warn("refresh_workspace threw unexpectedly: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

}
