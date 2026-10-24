package org.jawata.mcp.refactoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.jawata.mcp.change.ChangeContext;
import org.jawata.spi.host.BuildKind;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.host.Reconciler;
import org.jawata.spi.host.SyncReport;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 28k C2 (auditor finding A): a build that throws — of any kind, not only {@link CoreException} — is a failed gate, never an
 * exception that escapes the plan loop with earlier steps applied and unrolled (the gate caught every {@code Exception} before
 * E7). The reconciler here is a fake, so the project's build is the only thing that can fail.
 */
class ParityGateBuildFailureTest {

    private static final String PROJECT = "p";

    private static final String OTHER = "q";

    @Test
    @DisplayName("a build that throws a RuntimeException fails the gate, naming the project and the cause")
    void runtimeFailureOfTheBuildFailsTheGate() {
        ParityGate.Result result = ParityGate.compile(contextBuilding(() -> {
            throw new IllegalStateException("the build blew up");
        }), java.util.List.of());

        assertFalse(result.clean(), "a build that threw is not a clean project");
        assertEquals(1, result.errorCount());
        String text = result.errors().get(0).message();
        assertTrue(text.contains("build failed on " + PROJECT) && text.contains("the build blew up"), text);
    }

    @Test
    @DisplayName("a build that throws a CoreException fails the gate, naming the project and the cause")
    void coreFailureOfTheBuildFailsTheGate() {
        ParityGate.Result result = ParityGate.compile(contextBuilding(() -> {
            throw new CoreException(Status.error("the workspace refused"));
        }), java.util.List.of());

        assertFalse(result.clean());
        String text = result.errors().get(0).message();
        assertTrue(text.contains("build failed on " + PROJECT) && text.contains("the workspace refused"), text);
    }

    @Test
    @DisplayName("a project that built and has no errors is clean")
    void aBuiltProjectWithoutErrorsIsClean() {
        ParityGate.Result result = ParityGate.compile(contextBuilding(() -> Set.of(PROJECT)), java.util.List.of());

        assertTrue(result.clean(), () -> "built and error-free: " + result.errors());
    }

    @Test
    @DisplayName("a project whose build threw does not stop the next project from being built and read")
    void aFailedBuildOfOneProjectDoesNotHideTheNext() {
        List<String> built = new ArrayList<>();
        ParityGate.Result result = ParityGate.compile(context(BuildScope.of(PROJECT, OTHER),
            scope -> {
                String name = scope.projects().get(0);
                built.add(name);
                if (name.equals(PROJECT)) {
                    throw new IllegalStateException("the build blew up");
                }
                return Set.of(name);
            },
            scope -> List.of(new Diagnostic("Q.java", 3, "cannot find symbol")), null), List.of());

        assertEquals(List.of(PROJECT, OTHER), built, "the second project is still built after the first one failed");
        assertEquals(2, result.errorCount());
        assertTrue(result.errors().get(0).message().contains("build failed on " + PROJECT), result.errors().get(0).message());
        assertEquals("cannot find symbol", result.errors().get(1).message());
    }

    @Test
    @DisplayName("a language that cannot say what to build fails the gate, naming the cause, instead of throwing")
    void aVerdictThatCannotSayWhatToBuildFailsTheGate() {
        ParityGate.Result result = ParityGate.compile(context(BuildScope.of(PROJECT), scope -> Set.of(PROJECT),
            scope -> List.of(), files -> {
                throw new IllegalStateException("no model to ask");
            }), List.of());

        assertFalse(result.clean(), "nothing was checked, and nothing checked is not clean");
        String text = result.errors().get(0).message();
        assertTrue(text.contains("could not work out what to build") && text.contains("no model to ask"), text);
    }

    @Test
    @DisplayName("a language that cannot read the errors a build left fails the gate for that project, and the next is still read")
    void aVerdictThatCannotReadTheErrorsFailsTheGate() {
        ParityGate.Result result = ParityGate.compile(context(BuildScope.of(PROJECT, OTHER),
            scope -> Set.of(scope.projects().get(0)),
            scope -> {
                if (scope.projects().get(0).equals(PROJECT)) {
                    throw new IllegalStateException("markers unreadable");
                }
                return List.of(new Diagnostic("Q.java", 3, "cannot find symbol"));
            }, null), List.of());

        assertEquals(2, result.errorCount());
        String first = result.errors().get(0).message();
        assertTrue(first.contains("could not read the errors of " + PROJECT) && first.contains("markers unreadable"), first);
        assertEquals("cannot find symbol", result.errors().get(1).message());
    }

    /** The build step of a one-project context: what the step does is what {@code rebuild} does. */
    private static ChangeContext contextBuilding(BuildStep step) {
        return context(BuildScope.of(PROJECT), scope -> step.build(), scope -> List.of(), null);
    }

    private static ChangeContext context(BuildScope owned, ScopedBuild build, ScopedErrors errors, RebuildAnswer rebuildAnswer) {
        Reconciler reconciler = new Reconciler() {
            @Override
            public SyncReport syncBeforeCall() {
                throw new UnsupportedOperationException("the gate never syncs");
            }

            @Override
            public Set<String> rebuild(BuildScope scope, BuildKind kind, IProgressMonitor monitor) throws CoreException {
                return build.build(scope);
            }
        };
        BuildVerdict verdict = new BuildVerdict() {
            @Override
            public Map<String, Set<SourceProblem>> problemsIn(List<String> files) {
                throw new UnsupportedOperationException("the gate reads project errors only");
            }

            @Override
            public void settle(List<String> files) {
                throw new UnsupportedOperationException("the gate settles nothing");
            }

            @Override
            public Optional<String> describeCreated(String file) {
                return Optional.empty();
            }

            @Override
            public BuildScope owned() {
                return owned;
            }

            @Override
            public Rebuild rebuildAfter(List<String> changedFiles) {
                return rebuildAnswer == null ? BuildVerdict.super.rebuildAfter(changedFiles) : rebuildAnswer.answer(changedFiles);
            }

            @Override
            public List<Diagnostic> errors(BuildScope scope, IProgressMonitor monitor) {
                return errors.of(scope);
            }
        };
        return new ChangeContext(null, reconciler, verdict, null);
    }

    /** What a rebuild does in one test: answer the built names, or throw. */
    @FunctionalInterface
    private interface BuildStep {
        Set<String> build() throws CoreException;
    }

    /** What the rebuild of one named project does in a many-project test. */
    @FunctionalInterface
    private interface ScopedBuild {
        Set<String> build(BuildScope scope) throws CoreException;
    }

    /** The error-severity problems the language reads for one named project. */
    @FunctionalInterface
    private interface ScopedErrors {
        List<Diagnostic> of(BuildScope scope);
    }

    /** What the language answers to "what must be rebuilt after these files changed". */
    @FunctionalInterface
    private interface RebuildAnswer {
        BuildVerdict.Rebuild answer(List<String> changedFiles);
    }
}
