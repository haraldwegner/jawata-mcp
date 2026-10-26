package org.jawata.mcp.contract;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.jawata.spi.host.HostWorkspace;
import org.jawata.spi.host.Reconciler;
import org.jawata.spi.host.WorkspaceWrites;
import org.junit.jupiter.api.Nested;

/**
 * The host-side contracts (Sprint 28k, Stage 8; contract §18 ring 1) run against the ports of a host that is nothing but memory. The
 * headless host's ports run the same contracts in {@code org.jawata.resident}, and the real workspace writes run them below.
 */
class FakeHostPortsContractTest {

    private static FakeHost host(Path scratch) {
        return new FakeHost(scratch, Map.of());
    }

    @Nested
    class Workspace extends HostWorkspaceContract {
        @org.junit.jupiter.api.io.TempDir
        Path scratch;

        @Override
        protected HostWorkspace workspace() {
            FakeHost host = host(scratch);
            host.fakeWorkspace().open("alpha", scratch.resolve("alpha"));
            host.fakeWorkspace().open("beta", scratch.resolve("beta"));
            return host.workspace();
        }

        @Override
        protected Set<String> expectedFolders() {
            return Set.of("alpha", "beta");
        }
    }

    @Nested
    class EmptyWorkspace extends HostWorkspaceContract {
        @org.junit.jupiter.api.io.TempDir
        Path scratch;

        @Override
        protected HostWorkspace workspace() {
            return host(scratch).workspace();
        }

        @Override
        protected Set<String> expectedFolders() {
            return Set.of();
        }
    }

    @Nested
    class Reconciliation extends ReconcilerContract {
        @org.junit.jupiter.api.io.TempDir
        Path scratch;

        @Override
        protected Reconciler reconciler() {
            return host(scratch).reconciler();
        }
    }

    @Nested
    class Writes extends WorkspaceWritesContract {
        @org.junit.jupiter.api.io.TempDir
        Path scratch;

        @Override
        protected WorkspaceWrites writes() {
            return host(scratch).writes();
        }
    }

    @Nested
    class Lifecycle extends EngineLifecycleContract {
        @org.junit.jupiter.api.io.TempDir
        Path scratch;

        private FakeHost host;

        @Override
        protected org.jawata.spi.host.EngineLifecycle lifecycle() {
            host = host(scratch);
            return host.lifecycle();
        }

        @Override
        protected java.util.List<String> reportsSeen() {
            return host.lifecycleEvents();
        }
    }

    /** The real writes every host without its own uses, over the platform's own change machinery. */
    @Nested
    class DirectWrites extends WorkspaceWritesContract {
        @Override
        protected WorkspaceWrites writes() {
            return WorkspaceWrites.DIRECT;
        }
    }
}
