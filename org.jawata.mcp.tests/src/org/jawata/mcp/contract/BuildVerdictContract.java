package org.jawata.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.jawata.spi.lang.BuildVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What every language's build verdict promises the change lifecycle (Sprint 28k, Stage 8; contract §18 ring 1): it says which of the files
 * it is asked about do not build, says why for a file that was just created, and answers for the scope it owns. The lifecycle gates every
 * change on these answers, so they are the same shape for Java, for the stand-in language and for any language that comes.
 */
public abstract class BuildVerdictContract {

    /**
     * @return the verdict under test, over a state in which {@link #fileThatBuilds()} builds and {@link #fileThatDoesNotBuild()} does not
     * @throws Exception when the implementation cannot be built
     */
    protected abstract BuildVerdict verdict() throws Exception;

    /**
     * @return a file the language builds without a problem
     * @throws Exception when the fixture cannot be made
     */
    protected abstract String fileThatBuilds() throws Exception;

    /**
     * @return a file the language does not build because it does not PARSE; a file that parses and fails to type-check proves nothing about
     * the syntax category
     * @throws Exception when the fixture cannot be made
     */
    protected abstract String fileThatDoesNotBuild() throws Exception;

    @Test
    @DisplayName("no files asked about, no problems")
    void noFilesNoProblems() throws Exception {
        assertTrue(verdict().problemsIn(List.of()).isEmpty());
    }

    @Test
    @DisplayName("a file that builds has no problem")
    void aFileThatBuildsHasNoProblem() throws Exception {
        String file = fileThatBuilds();

        Set<BuildVerdict.SourceProblem> problems = verdict().problemsIn(List.of(file)).getOrDefault(file, Set.of());

        assertTrue(problems.isEmpty(), "got: " + problems);
    }

    @Test
    @DisplayName("a file that does not build has a problem, and the verdict says why when it was just created")
    void aFileThatDoesNotBuildHasAProblem() throws Exception {
        String file = fileThatDoesNotBuild();
        BuildVerdict verdict = verdict();

        Set<BuildVerdict.SourceProblem> problems = verdict.problemsIn(List.of(file)).getOrDefault(file, Set.of());

        assertFalse(problems.isEmpty(), "the file does not build and the verdict did not say so");
        assertTrue(verdict.describeCreated(file).isPresent(), "a created file that does not build is described");
    }

    @Test
    @DisplayName("a file that does not parse is a SYNTAX problem: the lifecycle counts only those against a file a change created, so any other category lets a broken new file stand")
    void anUnparseableFileIsASyntaxProblem() throws Exception {
        String file = fileThatDoesNotBuild();

        Set<BuildVerdict.SourceProblem> problems = verdict().problemsIn(List.of(file)).getOrDefault(file, Set.of());

        assertTrue(problems.stream().anyMatch(BuildVerdict.SourceProblem::syntax),
            "the file is not buildable because it does not parse, and no problem says syntax: " + problems);
    }

    @Test
    @DisplayName("the verdict answers for the scope it owns, and settling any files never fails")
    void theOwnedScopeIsAnswered() throws Exception {
        BuildVerdict verdict = verdict();

        assertNotNull(verdict.owned());
        assertNotNull(verdict.errors(verdict.owned(), new NullProgressMonitor()));
        assertDoesNotThrow(() -> verdict.settle(List.of(fileThatBuilds(), fileThatDoesNotBuild())));
    }
}
