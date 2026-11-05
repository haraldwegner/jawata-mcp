package org.jawata.mcp.change;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ltk.core.refactoring.Change;
import org.jawata.mcp.contract.FakeHost;
import org.jawata.mcp.contract.PlainTextChange;
import org.jawata.spi.host.WorkspaceWrites;
import org.jawata.spi.lang.ProviderContext;
import org.jawata.standin.FakeBuildVerdict;
import org.jawata.standin.FakeLanguageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28h S18 (28k list 9a B, ledger 28k-A18-1 and -6; contract §16): what the apply step asks of the host. Before a change is written the
 * host is asked whether it will write over these files, and a refusal is the answer with nothing written; the gate takes back a change it
 * refuses as a TRIAL, so a host with an undo history of its own does not list the rollback as a step the person made; a change that
 * stands is performed as ACCEPTED, as it was before.
 */
class GatedApplyHostWritesTest {

    @TempDir
    Path scratch;

    /** Writes that apply as the host-less default does and write down how and over what they were asked. */
    private static final class Recording implements WorkspaceWrites {
        private final WorkspaceWrites direct = new FakeHost.Applying();
        final List<WriteMode> modes = new CopyOnWriteArrayList<>();
        final List<String> asked = new CopyOnWriteArrayList<>();
        volatile String refuseWith;

        @Override
        public Applied perform(Change change, IProgressMonitor monitor) throws CoreException {
            return perform(change, WriteMode.ACCEPTED, monitor);
        }

        @Override
        public Applied perform(Change change, WriteMode mode, IProgressMonitor monitor) throws CoreException {
            modes.add(mode);
            return direct.perform(change, monitor);
        }

        @Override
        public Optional<String> refusal(Collection<IFile> targets) {
            targets.forEach(file -> asked.add(file.getName()));
            return Optional.ofNullable(refuseWith);
        }
    }

    private void inAProject(Recording writes, Body body) throws Exception {
        FakeHost host = new FakeHost(scratch.resolve("engine"), java.util.Map.of());
        FakeLanguageProvider language = new FakeLanguageProvider();
        ProviderContext ports = new ProviderContext() {
            @Override
            public org.jawata.spi.host.Reconciler reconciler() {
                return host.reconciler();
            }

            @Override
            public WorkspaceWrites writes() {
                return writes;
            }
        };
        ChangeContext context = new ChangeContext(writes, host.reconciler(), language.verdict(ports), language.paths(ports));
        Path folder = Files.createDirectories(scratch.toRealPath().resolve("project"));
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject project = workspace.getRoot().getProject("host-writes-" + System.nanoTime());
        IProjectDescription description = workspace.newProjectDescription(project.getName());
        description.setLocationURI(folder.toUri());
        project.create(description, null);
        project.open(null);
        try {
            body.run(context, folder);
        } finally {
            project.delete(false, true, null);
        }
    }

    /** The part of a test that runs inside the project. */
    private interface Body {
        void run(ChangeContext context, Path folder) throws Exception;
    }

    @Test
    @DisplayName("a change that builds stands, is performed as ACCEPTED, and the host was asked about its file first")
    void aChangeThatStandsIsAcceptedAfterTheHostWasAsked() throws Exception {
        Recording writes = new Recording();
        inAProject(writes, (context, folder) -> {
            Path good = folder.resolve("good.txt");

            GatedApply.Result result = GatedApply.perform(new PlainTextChange(good, "nothing wrong here"), context, GatedApply.Mode.UNDO);

            assertAll(
                () -> assertFalse(result.refused(), "got: " + result.failure()),
                () -> assertEquals(List.of(WorkspaceWrites.WriteMode.ACCEPTED), writes.modes, "kept by the person: not a trial"),
                () -> assertEquals(List.of("good.txt"), writes.asked, "the host was asked about the file the change acts on"));
        });
    }

    @Test
    @DisplayName("a host that refuses is the answer: nothing is written and the host is never asked to perform")
    void aRefusalIsAnsweredBeforeAnythingIsWritten() throws Exception {
        Recording writes = new Recording();
        writes.refuseWith = "UNSAVED_IN_EDITOR: good.txt has text in an editor that is not saved. Nothing was changed.";
        inAProject(writes, (context, folder) -> {
            Path good = folder.resolve("good.txt");

            GatedApply.Result result = GatedApply.perform(new PlainTextChange(good, "nothing wrong here"), context, GatedApply.Mode.UNDO);

            assertAll(
                () -> assertTrue(result.refused()),
                () -> assertTrue(result.failure().startsWith("UNSAVED_IN_EDITOR"), "the host's own words reach the caller: " + result.failure()),
                () -> assertTrue(writes.modes.isEmpty(), "nothing was performed: " + writes.modes),
                () -> assertFalse(Files.exists(good), "and nothing was written"));
        });
    }

    @Test
    @DisplayName("a change the gate refuses is written as ACCEPTED and taken back as a TRIAL, so the rollback is not a step the person made")
    void theGatesRollbackIsATrial() throws Exception {
        Recording writes = new Recording();
        inAProject(writes, (context, folder) -> {
            Path bad = folder.resolve("bad.txt");

            GatedApply.Result result = GatedApply.perform(new PlainTextChange(bad, "this one is " + FakeBuildVerdict.BROKEN), context,
                GatedApply.Mode.UNDO);

            assertAll(
                () -> assertTrue(result.refused() && result.undone(), "the verdict refused it and it was undone: " + result.failure()),
                () -> assertFalse(Files.exists(bad)),
                () -> assertEquals(List.of(WorkspaceWrites.WriteMode.ACCEPTED, WorkspaceWrites.WriteMode.TRIAL), writes.modes,
                    "the change, then its rollback"));
        });
    }
}
