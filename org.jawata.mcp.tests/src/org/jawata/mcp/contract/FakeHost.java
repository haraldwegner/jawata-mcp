package org.jawata.mcp.contract;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.jawata.spi.host.BuildKind;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.host.EndpointPolicy;
import org.jawata.spi.host.EngineDirectories;
import org.jawata.spi.host.EngineHost;
import org.jawata.spi.host.EngineLifecycle;
import org.jawata.spi.host.EngineSettings;
import org.jawata.spi.host.HostWorkspace;
import org.jawata.spi.host.LanguageBinding;
import org.jawata.spi.host.Reconciler;
import org.jawata.spi.host.SyncReport;
import org.jawata.spi.host.WorkspaceWrites;

/**
 * A host that is nothing but memory (Sprint 28k, Stage 8; contract §18 ring 1): its workspace is a list it is told about, its reconciler
 * counts the calls it gets, its writes apply a change and hand back the undo, its folders are inside one scratch folder and its settings
 * are a map. It serves no language, so it has no Java binding, and listens nowhere.
 *
 * <p>The engine assembles through the same door as any host ({@code JawataEngine.assemble(host)}); what this host proves is that the door
 * asks nothing the Eclipse host or the headless one alone could answer. The contract tests run the same assertions against it and against
 * the real hosts' ports, so a port behaves the same wherever it comes from.</p>
 */
public final class FakeHost implements EngineHost {

    private final Workspace workspace = new Workspace();
    private final Counting reconciler = new Counting();
    private final Applying writes = new Applying();
    private final Events lifecycle = new Events();
    private final Map<String, String> settings;
    private final Folders directories;

    /**
     * @param scratch  the one folder everything the host keeps is under
     * @param settings the settings the engine can read, by name
     */
    public FakeHost(Path scratch, Map<String, String> settings) {
        this.directories = new Folders(scratch);
        this.settings = Map.copyOf(settings);
    }

    /** The in-memory workspace, so a test can open and close projects in it. */
    public Workspace fakeWorkspace() {
        return workspace;
    }

    /** The reconciler, so a test can read how often it was asked. */
    public Counting fakeReconciler() {
        return reconciler;
    }

    /** What the listener told the host, in order. */
    public List<String> lifecycleEvents() {
        return lifecycle.events;
    }

    @Override
    public HostWorkspace workspace() {
        return workspace;
    }

    @Override
    public EngineLifecycle lifecycle() {
        return lifecycle;
    }

    @Override
    public EndpointPolicy endpoint() {
        // Listens nowhere: the same answer a host on stdio gives.
        return null;
    }

    @Override
    public EngineSettings settings() {
        return key -> Optional.ofNullable(settings.get(key));
    }

    @Override
    public EngineDirectories directories() {
        return directories;
    }

    @Override
    public Reconciler reconciler() {
        return reconciler;
    }

    @Override
    public WorkspaceWrites writes() {
        return writes;
    }

    @Override
    public <B extends LanguageBinding> Optional<B> binding(Class<B> kind) {
        return Optional.empty();
    }

    /** A workspace that holds what it has been told to hold. */
    public static final class Workspace implements HostWorkspace {
        private final List<ProjectFolder> folders = new CopyOnWriteArrayList<>();
        private volatile boolean owns = true;

        /**
         * Opens a project in the workspace.
         *
         * @param name the project's name
         * @param path the project's folder
         */
        public void open(String name, Path path) {
            folders.add(new ProjectFolder(name, path, true));
        }

        /**
         * Says whether the engine may change this workspace.
         *
         * @param owns true when the engine may
         */
        public void owns(boolean owns) {
            this.owns = owns;
        }

        @Override
        public List<ProjectFolder> folders() {
            return List.copyOf(folders);
        }

        @Override
        public boolean ownsWorkspace() {
            return owns;
        }
    }

    /** A reconciler that finds nothing to reconcile and counts that it was asked. */
    public static final class Counting implements Reconciler {
        private int syncs;
        private int rebuilds;

        @Override
        public synchronized SyncReport syncBeforeCall() {
            syncs++;
            return new SyncReport(true, 0, 0, 0, 0L, 0L, false);
        }

        @Override
        public synchronized Set<String> rebuild(BuildScope scope, BuildKind kind, IProgressMonitor monitor) {
            rebuilds++;
            return Set.of();
        }

        /** @return how many times a call asked the workspace to catch up with the disk */
        public synchronized int syncs() {
            return syncs;
        }

        /** @return how many times a build was asked for */
        public synchronized int rebuilds() {
            return rebuilds;
        }
    }

    /** Writes that apply a change as it is and hand back its undo. */
    public static final class Applying implements WorkspaceWrites {
        @Override
        public Applied perform(Change change, IProgressMonitor monitor) throws CoreException {
            change.initializeValidationData(monitor);
            RefactoringStatus validation = change.isValid(monitor);
            Change undo = validation.hasFatalError() ? null : change.perform(monitor);
            return new Applied(undo, validation);
        }

        @Override
        public Optional<String> refusal(Collection<IFile> targets) {
            return Optional.empty();
        }
    }

    /** A lifecycle that remembers what happened to the listener. */
    public static final class Events implements EngineLifecycle {
        private final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void listenerDeaf() {
            events.add("listenerDeaf");
        }

        @Override
        public void cannotListen(int port, Exception cause) {
            events.add("cannotListen:" + port);
        }
    }

    /** Folders that are all inside the scratch folder; no workspace file, so nothing is declared. */
    public static final class Folders implements EngineDirectories {
        private final Path root;

        Folders(Path root) {
            this.root = root;
        }

        @Override
        public Path runtimeDir() {
            return root.resolve("runtime");
        }

        @Override
        public Path coverageDir() {
            return root.resolve("coverage");
        }

        @Override
        public Path toolsDir() {
            throw new IllegalStateException("this host has no tools folder: it runs no forked tests");
        }

        @Override
        public Path sharedStoreDir() {
            return root.resolve("store");
        }

        @Override
        public Optional<Path> dataDir() {
            return Optional.empty();
        }

        @Override
        public Optional<Path> workspaceRoot() {
            return Optional.of(root);
        }

        @Override
        public Path fieldDir() {
            return root.resolve("field");
        }

        @Override
        public List<Path> memoryRoots() {
            return new ArrayList<>();
        }
    }
}
