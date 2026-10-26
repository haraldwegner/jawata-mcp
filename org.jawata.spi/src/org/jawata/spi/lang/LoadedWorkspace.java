package org.jawata.spi.lang;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What a language holds of the workspace's loaded projects, in plain types (Sprint 28k, Stage 9; contract §4.5): which projects are
 * loaded and how big they are, whether each can be read, how a path is shown to the person, and the way projects come and go. The tools
 * that name no language ask this and nothing language-specific: a workspace with no language loaded answers no projects, which is the
 * answer those tools turn into "nothing is loaded".
 *
 * <p>A language answers it from its own model: Java's service implements it as a role, so the same object that answers type queries
 * answers this one. The project key is the name a caller passes to scope a call to one project.</p>
 */
public interface LoadedWorkspace {

    /** The answer of a workspace with no language loaded: no projects, and nothing to add one to. */
    LoadedWorkspace NONE = new LoadedWorkspace() {
        @Override
        public List<ProjectInfo> projects() {
            return List.of();
        }

        @Override
        public Optional<ProjectInfo> project(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<String> defaultProjectKey() {
            return Optional.empty();
        }

        @Override
        public Optional<Long> wasRecentlyDropped(String key) {
            return Optional.empty();
        }

        @Override
        public ProjectInfo addProjectAt(Path root) {
            throw new IllegalStateException("no language is loaded, so there is nothing to add a project to");
        }

        @Override
        public boolean removeProject(String key) {
            return false;
        }

        @Override
        public void reresolveWorkspace() {
            // nothing is loaded, so nothing to resolve again
        }

        @Override
        public String display(Path path) {
            return path.toString();
        }

        @Override
        public Path resolvePath(String path) {
            return Path.of(path);
        }

        @Override
        public List<ProblemInfo> problems() {
            return List.of();
        }

        @Override
        public LoadedWorkspace scoped(String key) {
            return null;
        }
    };

    /** @return the loaded projects, in the order they were loaded; empty when none is */
    List<ProjectInfo> projects();

    /**
     * @param key a project key
     * @return that project, or empty when it is not loaded
     */
    Optional<ProjectInfo> project(String key);

    /** @return the key of the project a call without a key is about, or empty when none is loaded */
    Optional<String> defaultProjectKey();

    /**
     * @param key a project key
     * @return when the project was dropped from the workspace, in epoch milliseconds, if it was dropped recently, so a caller who held
     *         its key can be told the key went stale and not that it never existed
     */
    Optional<Long> wasRecentlyDropped(String key);

    /**
     * Brings a project into the workspace beside the ones already loaded.
     *
     * @param root the project's folder
     * @return the project as loaded
     * @throws Exception when the project cannot be loaded
     */
    ProjectInfo addProjectAt(Path root) throws Exception;

    /**
     * @param key a project key
     * @return whether a project was loaded under that key and has been dropped
     */
    boolean removeProject(String key);

    /** Works out again what each loaded project requires, after a build file changed. */
    void reresolveWorkspace();

    /**
     * @param path a path inside the workspace
     * @return the path as the person is shown it: relative to its project, or absolute when the host asked for absolute paths
     */
    String display(Path path);

    /**
     * @param path a path as a caller wrote it, absolute or relative to the project
     * @return the absolute path it names
     */
    Path resolvePath(String path);

    /**
     * @return every loaded project that cannot be read (closed, gone, or with a broken classpath), each with the way to fix it; empty
     *         when every one can be read. A workspace with a project in this list refuses refactorings.
     */
    List<ProblemInfo> problems();

    /**
     * @param key a project key
     * @return a view of this workspace holding only that project, or null when it is not loaded
     */
    LoadedWorkspace scoped(String key);

    /**
     * One loaded project.
     *
     * @param key                 the name a caller passes to scope a call to this project
     * @param root                the project's folder
     * @param buildSystem         the build system the project was read from, lower case, or {@code unknown}
     * @param sourceFileCount     how many source files it holds
     * @param packageCount        how many packages those files declare
     * @param classpathEntryCount how many entries its classpath has
     * @param unresolvedCount     how many requirements its import asked for and could not find; zero means asked and satisfied
     * @param loadedAt            when it was loaded
     * @param buildName           the name the host knows the project by when it builds it
     */
    record ProjectInfo(String key, Path root, String buildSystem, int sourceFileCount, int packageCount, int classpathEntryCount,
            int unresolvedCount, Instant loadedAt, String buildName) {
    }

    /**
     * A loaded project that cannot be read.
     *
     * @param projectKey  the project's key
     * @param projectPath the project's folder, as text
     * @param problem     what is wrong
     * @param remedy      what the person can do about it
     */
    record ProblemInfo(String projectKey, String projectPath, String problem, String remedy) {
    }
}
