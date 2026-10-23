package org.jawata.mcp.refactoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
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
 * 28k Stage 2b (E7c, his word: "only compile what is touched"): the plan gate rebuilds the projects the language says the
 * step's files reach, the way it says — and every project, in full, when the language has nothing to go by. The reconciler
 * and the verdict are fakes, so what is asserted is which builds were asked for, not what any compiler did.
 */
class ParityGateBuildsOnlyWhatIsTouchedTest {

    @Test
    @DisplayName("a step is built over the projects the verdict names, incrementally, and no other project is built")
    void onlyTheNamedProjectsAreBuilt() {
        Recording builds = new Recording();
        List<String> seenByVerdict = new ArrayList<>();
        BuildVerdict verdict = new Verdict(List.of("a", "b", "c")) {
            @Override
            public Rebuild rebuildAfter(List<String> changedFiles) {
                seenByVerdict.addAll(changedFiles);
                return new Rebuild(BuildScope.of("b", "c"), BuildKind.INCREMENTAL);
            }
        };

        ParityGate.Result result = ParityGate.compile(new ChangeContext(null, builds, verdict, null), List.of("b/src/X.java"));

        assertTrue(result.clean(), () -> "nothing failed: " + result.errors());
        assertEquals(List.of("b:INCREMENTAL", "c:INCREMENTAL"), builds.calls, "exactly the named projects, the named way");
        assertEquals(List.of("b/src/X.java"), seenByVerdict, "the verdict was asked about the files the step changed");
    }

    @Test
    @DisplayName("with no files to go by the verdict's default answer builds every project it owns, in full")
    void withoutFilesEverythingIsBuiltInFull() {
        Recording builds = new Recording();

        ParityGate.compile(new ChangeContext(null, builds, new Verdict(List.of("a", "b", "c")), null), List.of());

        assertEquals(List.of("a:FULL", "b:FULL", "c:FULL"), builds.calls);
    }

    /** Remembers which project was built and how, in order. */
    private static final class Recording implements Reconciler {
        final List<String> calls = new ArrayList<>();

        @Override
        public SyncReport syncBeforeCall() {
            throw new UnsupportedOperationException("the gate never syncs");
        }

        @Override
        public Set<String> rebuild(BuildScope scope, BuildKind kind, IProgressMonitor monitor) throws CoreException {
            scope.projects().forEach(p -> calls.add(p + ":" + kind));
            return Set.copyOf(scope.projects());
        }
    }

    /** A verdict that owns the named projects, finds no errors, and keeps the interface's default rebuild answer. */
    private static class Verdict implements BuildVerdict {
        private final List<String> owned;

        Verdict(List<String> owned) {
            this.owned = owned;
        }

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
            return new BuildScope(owned);
        }

        @Override
        public List<Diagnostic> errors(BuildScope scope, IProgressMonitor monitor) {
            return List.of();
        }
    }
}
