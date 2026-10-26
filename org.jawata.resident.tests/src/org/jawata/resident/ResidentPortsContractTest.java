package org.jawata.resident;

import java.util.Set;
import java.util.stream.Collectors;

import org.jawata.mcp.contract.HostWorkspaceContract;
import org.jawata.mcp.contract.ReconcilerContract;
import org.jawata.resident.fixtures.TestProjectHelper;
import org.jawata.resident.workspace.HeadlessModelSource;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.resident.workspace.OwnedReconciler;
import org.jawata.spi.host.HostWorkspace;
import org.jawata.spi.host.Reconciler;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The host-side contracts (Sprint 28k, Stage 8; contract §18 ring 1) run against the headless host's ports, over a project loaded from a
 * fixture copy. The same assertions run against a host that is nothing but memory in {@code FakeHostPortsContractTest}.
 */
class ResidentPortsContractTest {

    /** A workspace with one project loaded into the headless model. */
    abstract static class OverALoadedProject {
        @RegisterExtension
        TestProjectHelper helper = new TestProjectHelper();

        JdtServiceImpl service;
        final HeadlessModelSource model = new HeadlessModelSource();

        void load() throws Exception {
            if (service == null) {
                service = helper.loadProjectCopy("simple-maven");
                model.install(service);
            }
        }
    }

    @Nested
    class Workspace extends HostWorkspaceContract {
        final OverALoadedProject project = new OverALoadedProject() { };

        @RegisterExtension
        TestProjectHelper helper = project.helper;

        @Override
        protected HostWorkspace workspace() throws Exception {
            project.load();
            return new ResidentHostWorkspace(project.model);
        }

        @Override
        protected Set<String> expectedFolders() {
            return project.service.allProjects().stream().map(p -> p.projectKey()).collect(Collectors.toSet());
        }
    }

    @Nested
    class Reconciliation extends ReconcilerContract {
        final OverALoadedProject project = new OverALoadedProject() { };

        @RegisterExtension
        TestProjectHelper helper = project.helper;

        @Override
        protected Reconciler reconciler() throws Exception {
            project.load();
            return new OwnedReconciler(project.model::current);
        }
    }
}
