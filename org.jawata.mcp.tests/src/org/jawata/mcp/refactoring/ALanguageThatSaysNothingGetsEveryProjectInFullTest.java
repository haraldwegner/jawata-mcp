package org.jawata.mcp.refactoring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.jawata.spi.host.BuildKind;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 28k ledger C2b-11: the plan gate asks the language which projects a change reaches. A language that does not answer (any
 * provider that has not written its own reach yet, C++ included) must get the safe answer: every project it owns, in full.
 */
class ALanguageThatSaysNothingGetsEveryProjectInFullTest {

    /** A verdict that implements only what is required of it. */
    private static final class SilentVerdict implements BuildVerdict {
        @Override
        public Map<String, Set<SourceProblem>> problemsIn(List<String> files) {
            return Map.of();
        }

        @Override
        public void settle(List<String> files) {
        }

        @Override
        public Optional<String> describeCreated(String file) {
            return Optional.empty();
        }

        @Override
        public BuildScope owned() {
            return BuildScope.of("alpha", "beta");
        }

        @Override
        public List<Diagnostic> errors(BuildScope scope, IProgressMonitor monitor) {
            return List.of();
        }
    }

    @Test
    @DisplayName("a language that does not say what a change reaches gets every project it owns, built in full")
    void aLanguageThatSaysNothingGetsEveryProjectInFull() {
        BuildVerdict.Rebuild rebuild = new SilentVerdict().rebuildAfter(List.of("/anywhere/Changed.cpp"));

        assertEquals(BuildScope.of("alpha", "beta"), rebuild.scope());
        assertEquals(BuildKind.FULL, rebuild.kind());
    }
}
