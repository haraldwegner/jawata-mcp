package org.jawata.spi.host;

import java.util.List;

/**
 * Per-call outcome of reconciling the workspace with the disk, for the dispatch log + the
 * workspace-scope benchmark. Sprint 26: {@code refreshedPaths} carries WHICH source files
 * changed (bounded by the reconciler that writes it) — the watch engine's delta.
 * Sprint 28k (E3): moved out of {@code StrictDiskSync} unchanged, so a host can report it.
 * Sprint 28h S14: {@code changedBuildFiles} names the build files (the absolute paths of a {@code pom.xml}, {@code build.gradle},
 * {@code .classpath} or {@code MANIFEST.MF}) that changed, appeared or went since the last call; they are named and never reconciled,
 * because re-importing a project is the caller's decision.
 */
public record SyncReport(boolean skipped, int newProjects, int refreshedFiles,
                         int builtProjects, long scanNanos, long totalNanos,
                         boolean fullBuild, List<String> refreshedPaths, List<String> changedBuildFiles) {
    public SyncReport(boolean skipped, int newProjects, int refreshedFiles,
                      int builtProjects, long scanNanos, long totalNanos,
                      boolean fullBuild) {
        this(skipped, newProjects, refreshedFiles, builtProjects, scanNanos,
            totalNanos, fullBuild, List.of());
    }

    public boolean reconciled() {
        return newProjects > 0 || refreshedFiles > 0;
    }

    public SyncReport(boolean skipped, int newProjects, int refreshedFiles, int builtProjects, long scanNanos, long totalNanos, boolean fullBuild, List<String> refreshedPaths) {
        this(skipped, newProjects, refreshedFiles, builtProjects, scanNanos, totalNanos, fullBuild, refreshedPaths, List.of());
    }
}
