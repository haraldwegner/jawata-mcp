package org.jawata.core;

import org.eclipse.jdt.core.IJavaProject;
import org.jawata.core.host.HostPaths;
import org.jawata.core.project.BuildSystem;
import org.jawata.core.search.SearchService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Per-project state for a project loaded into the workspace.
 *
 * <p>Sprint 10 introduces multi-project workspaces: a single jawata MCP
 * server can hold N projects loaded simultaneously, all sharing one
 * Eclipse workspace. Each project's per-instance state (project root,
 * IJavaProject handle, search service, indexer summary) is captured in
 * one of these records, keyed by {@code projectKey} in the service's
 * project map.
 *
 * @param projectKey         Stable short id derived from project path. See
 *                           {@link ProjectKeys#derive(Path)}. Used as the
 *                           addressable identifier in MCP tool input.
 * @param projectRoot        Absolute, normalized path to the project's
 *                           root directory on disk.
 * @param javaProject        Eclipse JDT {@link IJavaProject} handle.
 * @param searchService      Per-project search service wrapping JDT's
 *                           SearchEngine against this project's classpath.
 * @param pathUtils          Path utilities scoped to this project root.
 * @param loadedAt           Wall-clock timestamp when the project was
 *                           registered with the service.
 * @param sourceFileCount    Number of .java files discovered (for
 *                           health_check / load_project response).
 * @param packageCount       Number of distinct packages.
 * @param packages           Package list (for health_check response).
 * @param buildSystem        Detected build system (Maven / Gradle / Bazel /
 *                           UNKNOWN).
 */
public record LoadedProject(
    String projectKey,
    Path projectRoot,
    IJavaProject javaProject,
    SearchService searchService,
    HostPaths pathUtils,
    Instant loadedAt,
    int sourceFileCount,
    int packageCount,
    List<String> packages,
    BuildSystem buildSystem,
    List<org.jawata.core.project.UnresolvedRequirement> unresolved
) {
    /**
     * EMPTY, never null. A project that resolved everything and a project
     * nobody asked about must not look the same — that ambiguity is the whole
     * reason this component exists.
     */
    public LoadedProject {
        unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
    }

    /**
     * The one answer to "which loaded project holds this file": the project whose root contains it, the deepest root when roots
     * nest (a module inside an aggregator), or null when none does. The per-call sync and the build planning both ask it.
     */
    public static LoadedProject owning(java.util.Collection<LoadedProject> projects, Path file) {
        LoadedProject best = null;
        int bestLength = -1;
        for (LoadedProject candidate : projects) {
            Path root = candidate.projectRoot().toAbsolutePath().normalize();
            if (file.startsWith(root) && root.getNameCount() > bestLength) {
                best = candidate;
                bestLength = root.getNameCount();
            }
        }
        return best;
    }

    public int classpathEntryCount() {
        try {
            return javaProject != null ? javaProject.getRawClasspath().length : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
