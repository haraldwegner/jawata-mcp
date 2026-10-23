package org.jawata.mcp.refactoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** The build step of a one-project context: what the step does is what {@code rebuild} does. */
    private static ChangeContext contextBuilding(BuildStep step) {
        Reconciler reconciler = new Reconciler() {
            @Override
            public SyncReport syncBeforeCall() {
                throw new UnsupportedOperationException("the gate never syncs");
            }

            @Override
            public Set<String> rebuild(BuildScope scope, BuildKind kind, IProgressMonitor monitor) throws CoreException {
                return step.build();
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
                return BuildScope.of(PROJECT);
            }

            @Override
            public List<Diagnostic> errors(BuildScope scope, IProgressMonitor monitor) {
                return List.of();
            }
        };
        return new ChangeContext(null, reconciler, verdict, null);
    }

    /** What a rebuild does in one test: answer the built names, or throw. */
    @FunctionalInterface
    private interface BuildStep {
        Set<String> build() throws CoreException;
    }
}
