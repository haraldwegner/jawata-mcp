package org.jawata.mcp.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.jawata.mcp.java.JavaBuildVerdict;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.spi.host.BuildKind;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.host.Reconciler;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.Diagnostic;
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
public class RefreshWorkspaceTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(RefreshWorkspaceTool.class);

    /** The host's reconciler, which refreshes and builds; read at call time (28k E6). */
    private final Supplier<Reconciler> reconciler;

    public RefreshWorkspaceTool(Supplier<IJdtService> serviceSupplier, Supplier<Reconciler> reconciler) {
        super(serviceSupplier);
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
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        // We deliberately handle projectKey here (not via AbstractTool's
        // scoping wrapper) because the no-projectKey case needs the
        // full multi-project view via service.allProjects().
        String projectKey = getStringParam(arguments, "projectKey");
        Collection<LoadedProject> projects;
        if (projectKey != null && !projectKey.isBlank()) {
            Optional<LoadedProject> scoped = service.getProject(projectKey);
            if (scoped.isEmpty()) {
                Optional<Long> dropped = service.wasRecentlyDropped(projectKey);
                if (dropped.isPresent()) {
                    return ToolResponse.projectKeyDropped(projectKey, dropped.get());
                }
                return ToolResponse.invalidParameter("projectKey",
                    "Unknown projectKey '" + projectKey + "'. Use project(action=list).");
            }
            projects = List.of(scoped.get());
        } else {
            projects = service.allProjects();
        }

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
            List<String> names = projects.stream().map(p -> p.javaProject().getProject().getName()).toList();
            Set<String> built;
            try {
                built = reconciler.get().rebuild(new BuildScope(names), BuildKind.CLEAN_FULL, new NullProgressMonitor());
            } catch (Exception e) {
                log.warn("Refresh + rebuild failed: {}", e.getMessage(), e);
                built = Set.of();
            }
            List<String> notRebuilt = new ArrayList<>();
            // Sprint 28k (E10b): the problem rows come from the language's verdict, read after the build.
            BuildVerdict verdict = new JavaBuildVerdict(service);
            Map<String, List<Diagnostic>> problemRows = CompileWorkspaceTool.byProject(
                verdict.diagnostics(new BuildScope(names), new NullProgressMonitor()));

            for (LoadedProject loaded : projects) {
                IJavaProject jp = loaded.javaProject();
                IProject project = jp.getProject();
                refreshedProjects.add(loaded.projectKey());

                // Approximate per-project file count (sum of compilation units
                // across source roots). Reported as filesRefreshed + as
                // classFilesInvalidated since CLEAN_BUILD invalidates every
                // class file for that project's sources.
                int projectFileCount = countCompilationUnits(jp);
                filesRefreshed += projectFileCount;
                classFilesInvalidated += projectFileCount;

                if (!built.contains(project.getName())) {
                    notRebuilt.add(loaded.projectKey());   // markers are still collected: partial results are useful
                }

                for (Diagnostic row : problemRows.getOrDefault(project.getName(), List.of())) {
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

    /**
     * Cheap approximation: walk source roots once, count
     * {@code IPackageFragment}s' compilation units. JDT's
     * {@code IJavaProject.getPackageFragmentRoots()} is in-memory and
     * doesn't trigger I/O after the project is loaded.
     */
    private static int countCompilationUnits(IJavaProject jp) {
        int count = 0;
        try {
            for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
                if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;
                for (var element : root.getChildren()) {
                    if (element instanceof IPackageFragment frag) {
                        count += frag.getCompilationUnits().length;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to count CUs for project '{}': {}",
                jp.getElementName(), e.getMessage());
        }
        return count;
    }

    @SuppressWarnings("unused")
    private static boolean isSourceClasspath(IClasspathEntry entry) {
        return entry.getEntryKind() == IClasspathEntry.CPE_SOURCE;
    }
}
