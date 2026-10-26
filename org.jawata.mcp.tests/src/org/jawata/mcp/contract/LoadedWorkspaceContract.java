package org.jawata.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.jawata.spi.lang.LoadedWorkspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What every language promises about the projects it has loaded (Sprint 28k, Stage 9; contract §4.5): the projects it lists are the
 * ones it holds, each answers to its own key, a key it never held is simply absent, the default is one of the listed, the problems it
 * reports are about listed projects, and narrowing to one project shows exactly that one. A language that holds none answers all of this
 * with empty lists, never null.
 */
public abstract class LoadedWorkspaceContract {

    /**
     * @return the workspace under test, with the projects {@link #expectedKeys()} loaded
     * @throws Exception when the implementation cannot be built
     */
    protected abstract LoadedWorkspace workspace() throws Exception;

    /**
     * @return the keys of the projects the workspace was built with; empty for a language that holds none
     * @throws Exception when the implementation cannot be read
     */
    protected abstract Set<String> expectedKeys() throws Exception;

    @Test
    @DisplayName("the projects it lists are the ones it holds, and each answers to its own key")
    void itListsWhatItHoldsAndEachAnswersToItsKey() throws Exception {
        LoadedWorkspace workspace = workspace();

        Set<String> listed = workspace.projects().stream().map(LoadedWorkspace.ProjectInfo::key).collect(Collectors.toSet());

        assertEquals(expectedKeys(), listed);
        for (LoadedWorkspace.ProjectInfo info : workspace.projects()) {
            assertEquals(info, workspace.project(info.key()).orElseThrow(), "asked by its key, a project is the one that was listed");
            assertNotNull(info.root(), "a project has a folder: " + info);
            assertTrue(info.sourceFileCount() >= 0 && info.packageCount() >= 0 && info.unresolvedCount() >= 0, "counts are counts: " + info);
        }
    }

    @Test
    @DisplayName("a key it never held is absent, was not dropped, and cannot be narrowed to")
    void aKeyItNeverHeldIsAbsent() throws Exception {
        LoadedWorkspace workspace = workspace();

        assertTrue(workspace.project("no-such-project").isEmpty());
        assertTrue(workspace.wasRecentlyDropped("no-such-project").isEmpty(), "never existed is not the same as dropped");
        assertNull(workspace.scoped("no-such-project"));
        assertFalse(workspace.removeProject("no-such-project"), "removing what is not there removes nothing");
    }

    @Test
    @DisplayName("the default project is one of the listed, or there is none when none is loaded")
    void theDefaultIsOneOfTheListed() throws Exception {
        LoadedWorkspace workspace = workspace();

        Set<String> expected = expectedKeys();
        java.util.Optional<String> key = workspace.defaultProjectKey();

        assertEquals(expected.isEmpty(), key.isEmpty(), "there is a default exactly when a project is loaded: " + key);
        assertTrue(key.isEmpty() || expected.contains(key.get()), "the default is one of the listed: " + key);
    }

    @Test
    @DisplayName("the problems it reports are about projects it lists, and it never answers null")
    void theProblemsAreAboutListedProjects() throws Exception {
        LoadedWorkspace workspace = workspace();

        List<LoadedWorkspace.ProblemInfo> problems = workspace.problems();
        Set<String> expected = expectedKeys();

        assertNotNull(problems);
        assertTrue(problems.stream().allMatch(p -> expected.contains(p.projectKey())), "got: " + problems);
        assertNotNull(workspace.display(java.nio.file.Path.of("a", "b.txt")), "a path can always be shown");
    }

    @Test
    @DisplayName("narrowed to one of its projects it shows exactly that one")
    void narrowedToOneItShowsThatOne() throws Exception {
        LoadedWorkspace workspace = workspace();

        for (String key : expectedKeys()) {
            LoadedWorkspace narrowed = workspace.scoped(key);

            assertNotNull(narrowed, key);
            assertEquals(List.of(key), narrowed.projects().stream().map(LoadedWorkspace.ProjectInfo::key).toList());
        }
    }
}
