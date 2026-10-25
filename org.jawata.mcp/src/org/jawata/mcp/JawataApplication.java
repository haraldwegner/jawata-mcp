package org.jawata.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.jawata.core.HeadlessModelSource;
import org.jawata.core.IJdtService;
import org.jawata.core.JdtServiceImpl;
import org.jawata.core.workspace.WorkspaceFileWatcher;
import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.engine.EngineState;
import org.jawata.mcp.engine.JawataEngine;
import org.jawata.mcp.engine.ProjectLoadingState;
import org.jawata.mcp.engine.ResidentDirectories;
import org.jawata.mcp.engine.ResidentEndpointPolicy;
import org.jawata.mcp.engine.ResidentHost;
import org.jawata.mcp.engine.ResidentHostWorkspace;
import org.jawata.mcp.engine.ResidentJavaBinding;
import org.jawata.mcp.engine.ResidentLifecycle;
import org.jawata.mcp.engine.ResidentSettings;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.transport.HttpTransport;
import org.jawata.mcp.transport.ResolvedToken;
import org.jawata.mcp.transport.StdioTransport;
import org.jawata.mcp.transport.Transport;
import org.jawata.mcp.transport.TransportConfig;
import org.jawata.spi.host.EndpointPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGi application entry point for JAWATA MCP server.
 * Reads JSON-RPC messages from stdin and writes responses to stdout.
 *
 * <p>Session isolation is handled by the JawataLauncher wrapper which
 * injects a unique UUID into the workspace path before OSGi starts.
 */
public class JawataApplication implements IApplication {

    public static final Logger log = LoggerFactory.getLogger(JawataApplication.class);

    // E14: the workspace service is the Java binding's model; the binding and the host workspace are what the tools are given.
    private final HeadlessModelSource model = new HeadlessModelSource();
    private final ResidentJavaBinding javaBinding = new ResidentJavaBinding(model);
    private final ResidentHostWorkspace hostWorkspace = new ResidentHostWorkspace(model);
    // E16: what a listener failure means for this process (status 13 or 70); the engine's transport only reports it.
    private final ResidentLifecycle lifecycle = new ResidentLifecycle(this::closeActiveTransport);
    // E17/E18: this process is the host of the engine's settings and folders: its system properties, and the folders worked out from them.
    private final ResidentSettings settings = new ResidentSettings();
    private final ResidentDirectories directories = new ResidentDirectories(settings);
    private volatile WorkspaceFileWatcher workspaceWatcher;
    private JawataEngine jawataEngine;

	/**
     * Test hook — stand in a workspace-load state without booting Equinox.
     *
     * <p>The tool gate's answer depends on this application-wide state
     * (mcp#28), and no unit test can start a real application to produce a
     * failed load. Package-private on purpose: a public test-only member is
     * exactly what the hollow-wiring gate refuses.
     */
    static void setLoadingStateForTest(ProjectLoadingState state, String error) {
        EngineState.current().set(state, error);
    }

    /** Test hook — a state left behind poisons every later test. */
    static void clearLoadingStateForTest() {
        EngineState.current().reset();
    }

    @Override
    public Object start(IApplicationContext context) throws Exception {
        log.info("JAWATA MCP Server starting...");
        EngineState.current().booted();

        // Sprint 14a Stage 2: parse transport CLI flags from the application
        // argv. HTTP is the default; -transport stdio opts back to the
        // pre-Sprint-14a behaviour. Unknown flags (Eclipse -data / -clean /
        // etc.) pass through. The actual HttpTransport impl lands in Stage 3.
        String[] cliArgs = (String[]) context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
        TransportConfig transportConfig = TransportConfig.fromArgs(cliArgs);
        log.info("Transport selection: {}", transportConfig);

        // E18: everything only this process knows goes into one host, and the engine is built from it.
        jawataEngine = new JawataEngine(
            new ResidentHost(hostWorkspace, lifecycle, endpointPolicy(transportConfig), settings, directories, javaBinding),
            new RefactoringChangeCache(), new org.jawata.mcp.runtime.RuntimeSessionRegistry());

        // Initialize tool registry and register tools
        jawataEngine.assemble();

        // Sprint 28a (D11): install this server's workspace identity BEFORE the
        // message loop, from the same workspace.json the async load will read —
        // the initialize handshake must never race the project load. The live
        // supplier takes over once projects are actually in.
        installWorkspaceIdentity();

        // Sprint 10 v1.4.0: prefer workspace.json in the JDT data dir as the
        // source of truth for what to load. The manager (jawata-studio)
        // writes this file. Fall back to the legacy JAVA_PROJECT_PATH env
        // var when workspace.json is absent (back-compat for direct manual
        // launches without the manager).
        CompletableFuture.runAsync(this::autoLoadProjects);

        // Run the main message loop (starts immediately, doesn't wait for project load)
        Object exit = runMessageLoop(transportConfig);

        log.info("JAWATA MCP Server stopped");
        return exit;
    }

    /**
     * Sprint 10 v1.4.0: load projects from {@code workspace.json} in the
     * Eclipse {@code -data} directory if present, otherwise fall back to
     * {@code JAVA_PROJECT_PATH}. This runs asynchronously so the MCP server
     * can respond to {@code initialize} immediately while loading proceeds.
     *
     * <p>v1.7.1 (bug #5): also check the parent of the OSGi data dir. The
     * JawataLauncher wrapper injects a UUID subdir into {@code -data} for
     * session isolation, so OSGi's {@code osgi.instance.area} ends up at
     * {@code <workspace>/<uuid>/} while the manager writes workspace.json at
     * {@code <workspace>/workspace.json}. Without the parent-fallback every
     * non-manager-spawned JVM (Cursor, Claude Code, etc.) saw an empty
     * project list.</p>
     */
    /**
     * Sprint 28a (D11): who this server is, readable before anything loads. Boot
     * facts come from {@code workspace.json} (name + configured project roots);
     * the env-var fallback names its single root. The live supplier reads the
     * loaded model when it exists — it reflects later load_project / project-add
     * calls, and while the async load runs it returns empty so the boot list
     * keeps answering.
     */
    private void installWorkspaceIdentity() {
        try {
            Path dataDir = directories.resolveDataDir();
            Path workspaceJson = dataDir == null ? null : ResidentDirectories.findWorkspaceJson(dataDir);
            if (workspaceJson != null) {
                org.jawata.mcp.models.WorkspaceIdentity.install(
                    ResidentDirectories.readProvenance(workspaceJson)[0], ResidentDirectories.readProjects(workspaceJson));
            } else {
                String envRoot = System.getenv("JAVA_PROJECT_PATH");
                if (envRoot != null && !envRoot.isBlank()) {
                    Path root = Path.of(envRoot).toAbsolutePath().normalize();
                    org.jawata.mcp.models.WorkspaceIdentity.install(null, java.util.List.of(root));
                }
            }
            // mcp#27 stage 1: the other residents on this machine, read from the registry
            // studio publishes beside the workspace directories. A SUPPLIER rather than a
            // snapshot, because residents start and stop while this one runs — a list read
            // once at boot would name servers that have since gone and miss ones that arrived.
            final Path identityDataDir = dataDir;
            org.jawata.mcp.models.WorkspaceIdentity.installSiblings(() ->
                org.jawata.mcp.models.SiblingRegistry.around(
                    identityDataDir, org.jawata.mcp.models.WorkspaceIdentity.name()));
            // mcp#27 stage 1: and ASK them. One HttpClient for the process — building one per
            // miss would create a connection pool and two threads each time, on the path that
            // is already the slow one. The client's own connect timeout bounds the handshake;
            // SiblingPeek bounds the request and the whole walk.
            final java.net.http.HttpClient peekClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(500))
                .build();
            org.jawata.mcp.models.WorkspaceIdentity.installPeek(fqn ->
                org.jawata.mcp.models.SiblingPeek.sweep(
                    fqn,
                    org.jawata.mcp.models.SiblingRegistry.around(
                        identityDataDir, org.jawata.mcp.models.WorkspaceIdentity.name()),
                    peekClient));
            org.jawata.mcp.models.WorkspaceIdentity.installLiveKeys(() -> {
                IJdtService s = this.model.current();
                if (s == null) {
                    return java.util.List.of();
                }
                return s.allProjects().stream()
                    .map(org.jawata.core.LoadedProject::projectKey).toList();
            });
            // mcp#32: the boot list covers the LOADING window only. Once the
            // load has terminally failed, the identity says so instead of
            // naming projects this server does not have.
            org.jawata.mcp.models.WorkspaceIdentity.installLoadFailure(() ->
                EngineState.current().loading() == ProjectLoadingState.FAILED ? EngineState.current().loadingError() : null);
            // mcp#65: and the other end of the same window. The load is asynchronous and on
            // a 194-module workspace takes minutes; until this says false, a miss is "not
            // yet" rather than "no".
            //
            // NOT_LOADED COUNTS, and the first version of this omitted it — found by the
            // architect watch and confirmed by reading start(): the identity is installed,
            // autoLoadProjects is DISPATCHED, and runMessageLoop begins serving, all before
            // the async task reaches the line that sets LOADING. So the handshake window —
            // the first message every client sends — ran with the state still NOT_LOADED and
            // took the fully-loaded path, which is the very defect mcp#65 is about, one state
            // earlier.
            //
            // The question is therefore "has the load FINISHED", not "is it running".
            // NOT_LOADED is terminal only when neither workspace.json nor JAVA_PROJECT_PATH
            // exists — and in exactly that case installWorkspaceIdentity installed nothing,
            // so installed() is false and every sentence below is unreachable.
            org.jawata.mcp.models.WorkspaceIdentity.installLoadPending(() ->
                EngineState.current().loading().loadPending());
        } catch (Exception e) {
            // Identity is a courtesy layer — a failure here must never stop the server.
            log.warn("Workspace identity not installed: {}", e.getMessage());
        }
    }

    private void autoLoadProjects() {
        Path dataDir = directories.resolveDataDir();
        if (dataDir != null) {
            Path workspaceJson = ResidentDirectories.findWorkspaceJson(dataDir);
            if (workspaceJson != null) {
                loadFromWorkspaceJson(workspaceJson.getParent());
                jawataEngine.refreshExperienceAfterProjectLoad();
                return;
            }
        }
        // Fall back to single-project env-var path.
        autoLoadProjectFromEnv();
        jawataEngine.refreshExperienceAfterProjectLoad();
    }

    

    

    

    

    // --- Sprint 21a (item C): default memory roots for load/reseed ------------------------

    private void loadFromWorkspaceJson(Path dataDir) {
        log.info("Loading workspace from {}", dataDir.resolve("workspace.json"));
        EngineState.current().loadStarted();
        try {
            JdtServiceImpl service = new JdtServiceImpl();
            WorkspaceFileWatcher watcher = new WorkspaceFileWatcher(dataDir, service);
            watcher.start();  // synchronous initial load + arm watcher thread
            this.model.install(service);
            this.workspaceWatcher = watcher;
            // v3.2.1 (dogfood #2): a workspace whose EVERY project failed to
            // load is a FAILED load, not a healthy empty one — the 2026-07-19
            // flip served 'Ready/healthy' with 0 projects while the real error
            // (an uninstalled bumped dependency) sat in the log. Health must
            // carry the diagnosis + remedy.
            Map<String, String> failures = watcher.loadFailures();
            if (service.allProjects().isEmpty() && !failures.isEmpty()) {
                var first = failures.entrySet().iterator().next();
                String reason = "all " + failures.size() + " workspace project(s) FAILED to "
                    + "load — first: " + first.getKey() + ": " + first.getValue()
                    + ". Remedy: fix the cause (e.g. run the project's build once so "
                    + "dependencies resolve), then reload via load_project or restart.";
                EngineState.current().loadFailed(reason);
                log.error("Workspace auto-load: {}", reason);
            } else {
                EngineState.current().loadSucceeded();
                if (!failures.isEmpty()) {
                    log.warn("Workspace loaded PARTIALLY — {} project(s) failed: {}",
                        failures.size(), failures);
                }
                log.info("Workspace loaded; watcher armed for live updates");
            }
        } catch (Exception e) {
            log.error("Failed to load workspace from workspace.json: {}", e.getMessage(), e);
            EngineState.current().loadFailed(e.getMessage());
        }
    }

    /**
     * Auto-load project from JAVA_PROJECT_PATH environment variable.
     * This runs asynchronously to allow the MCP server to respond immediately.
     * The loading state is tracked and can be queried via health_check.
     */
    private void autoLoadProjectFromEnv() {
        String projectPath = System.getenv("JAVA_PROJECT_PATH");
        if (projectPath == null || projectPath.isBlank()) {
            log.debug("JAVA_PROJECT_PATH not set, waiting for load_project call");
            // State remains NOT_LOADED
            return;
        }

        Path path = Path.of(projectPath).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            log.warn("JAVA_PROJECT_PATH points to non-existent path: {}", projectPath);
            EngineState.current().loadFailed("JAVA_PROJECT_PATH points to non-existent path: " + projectPath);
            return;
        }

        if (!Files.isDirectory(path)) {
            log.warn("JAVA_PROJECT_PATH is not a directory: {}", projectPath);
            EngineState.current().loadFailed("JAVA_PROJECT_PATH is not a directory: " + projectPath);
            return;
        }

        log.info("Auto-loading project from JAVA_PROJECT_PATH: {}", path);
        EngineState.current().loadStarted();

        try {
            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(path);
            this.model.install(service);
            EngineState.current().loadSucceeded();
            log.info("Project auto-loaded successfully: {} files, {} packages",
                service.getSourceFileCount(), service.getPackageCount());
        } catch (Exception e) {
            log.error("Failed to auto-load project from JAVA_PROJECT_PATH: {}", e.getMessage(), e);
            EngineState.current().loadFailed(e.getMessage());
        }
    }

    private Object runMessageLoop(TransportConfig config) {
        // Sprint 14a Stage 3: transport.run(handler) drives the I/O loop
        // internally. Stdio loops on its read/write pair; HTTP runs the
        // JDK HttpServer until close() signals shutdown. activeTransport
        // exposes the running transport so JawataApplication.stop() can
        // unblock it on OSGi shutdown.
        try (Transport transport = openTransport(config)) {
            this.jawataEngine.setActiveTransport(transport);
            transport.run(jawataEngine.getProtocolHandler()::processMessage);
        } catch (Exception e) {
            // A port that is taken is reported by the transport to the lifecycle (status 13, jawata-mcp#2); this is every other failure.
            log.error("Error in message loop", e);
        } finally {
            this.jawataEngine.setActiveTransport(null);
        }
        // 0 unless the listener failed: 13 for a port that is taken, 70 for a listener that went deaf (REL44-32).
        return lifecycle.exitCode();
    }

    /** Ends the message loop of the running transport, if there is one. */
    private void closeActiveTransport() {
        Transport t = jawataEngine.getActiveTransport();
        if (t != null) {
            t.close();
        }
    }

    /**
     * Sprint 14a Stage 2: construct the Transport per the parsed CLI config.
     * STDIO wraps System.in/out (current behaviour, opt-in only). HTTP
     * resolves the token (generate if absent) and hands off to HttpTransport
     * which throws until Stage 3 implements it.
     */
    private Transport openTransport(TransportConfig config) {
        if (config.getKind() == TransportConfig.Kind.STDIO) {
            log.info("Transport: stdio (opt-in via -transport stdio)");
            return new StdioTransport(System.in, System.out);
        }
        return new HttpTransport(jawataEngine.host().endpoint(), lifecycle);
    }

    /** Where this process listens, or nothing when it talks on stdio. */
    private static EndpointPolicy endpointPolicy(TransportConfig config) {
        if (config.getKind() == TransportConfig.Kind.STDIO) {
            return null;
        }
        // studio#14: the token is resolved through its file when one is named,
        // so the secret need never appear in argv (world-readable via
        // /proc/<pid>/cmdline) nor in the READY line the manager logs.
        ResolvedToken resolved = ResolvedToken.resolve(config);
        log.info("Transport: http (default) — port={}, bind={}, token={}",
            config.getPort(), config.getBindAddress(),
            resolved.tokenFile() != null ? "from " + resolved.tokenFile()
                : resolved.supplied() ? "supplied" : "generated");
        return new ResidentEndpointPolicy(config, resolved);
    }

    @Override
    public void stop() {
        log.info("Stop requested");
        // Sprint 27a Stage 3b: end the reconciliation loop.
        //
        // The interrupt is observed BETWEEN passes, not mid-pass — a pass embeds
        // up to a full batch (minutes at batch 1000), and this does not wait it
        // out. So a bounded join gives an in-flight pass a moment to notice and
        // return, but if it does not, the store closes under it and that pass's
        // final write fails with a caught SQLException. That is safe, not clean:
        // every row committed before the close persists (H2 autocommit), so the
        // next start resumes from a smaller delta and loses nothing. The loop
        // itself always exits — on the interrupt at the next pass boundary.
        Thread backfill = jawataEngine.getEmbeddingBackfillThread();
        if (backfill != null) {
            backfill.interrupt();
            try {
                backfill.join(2000);          // a moment, not the whole pass
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            jawataEngine.setEmbeddingBackfillThread(null);
        }
        // The schedule is interrupted, not joined: a copy in progress is the store's own
        // BACKUP TO, which finishes or fails on its own; the daemon thread never holds the
        // process open, and a partial zip fails its read-back at the next start and is removed.
        Thread backupSchedule = jawataEngine.getScheduledBackupThread();
        if (backupSchedule != null) {
            backupSchedule.interrupt();
            jawataEngine.setScheduledBackupThread(null);
        }
        Transport t = jawataEngine.getActiveTransport();
        if (t != null) {
            t.close();
        }
        WorkspaceFileWatcher watcher = workspaceWatcher;
        if (watcher != null) {
            watcher.close();
            workspaceWatcher = null;
        }
        ExperienceStore store = jawataEngine.getExperienceStore();
        if (store != null) {
            store.close();
            jawataEngine.setExperienceStore(null);
        }
    }
}
