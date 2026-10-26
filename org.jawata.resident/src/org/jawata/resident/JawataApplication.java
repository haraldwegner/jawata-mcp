package org.jawata.resident;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.jawata.mcp.engine.DefaultEngineDirectories;
import org.jawata.mcp.engine.DefaultEngineSettings;
import org.jawata.mcp.engine.EngineState;
import org.jawata.mcp.engine.JawataEngine;
import org.jawata.mcp.engine.SettingKeys;
import org.jawata.mcp.transport.HttpTransport;
import org.jawata.mcp.transport.ResolvedToken;
import org.jawata.mcp.transport.StdioTransport;
import org.jawata.mcp.transport.Transport;
import org.jawata.mcp.transport.TransportConfig;
import org.jawata.resident.workspace.HeadlessModelSource;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.resident.workspace.WorkspaceFileWatcher;
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
    private final DefaultEngineSettings settings = new DefaultEngineSettings();
    private final ResidentDirectories directories = new ResidentDirectories(settings);
    private volatile WorkspaceFileWatcher workspaceWatcher;
    private JawataEngine jawataEngine;

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
        jawataEngine = JawataEngine.assemble(
            new ResidentHost(hostWorkspace, lifecycle, endpointPolicy(transportConfig), settings, directories, javaBinding,
                new org.jawata.resident.workspace.OwnedReconciler(model::current), org.jawata.spi.host.WorkspaceWrites.DIRECT));

        // E19: the host the engine was built from is a service, so the Java provider component can bind to it, and the engine finds
        // what plugs into it (languages, faces) through its whiteboard. The engine reports "loading" until the Java provider arrives.
        org.osgi.framework.BundleContext framework =
            org.osgi.framework.FrameworkUtil.getBundle(JawataApplication.class).getBundleContext();
        org.osgi.framework.ServiceRegistration<org.jawata.spi.host.EngineHost> hostRegistration = framework.registerService(
            org.jawata.spi.host.EngineHost.class, jawataEngine.host(), null);
        org.jawata.mcp.engine.WhiteboardTracker whiteboardTracker =
            new org.jawata.mcp.engine.WhiteboardTracker(framework, jawataEngine.whiteboard());
        whiteboardTracker.open();

        // What was plugged in is let go whether the loop ends by itself or by an error, so everything after the tracker opens is
        // inside the try (E19).
        Object exit;
        try {
            // Sprint 28a (D11): the engine says who it is BEFORE the message loop, from what the directories declare, which is the
            // same workspace.json the async load reads — the initialize handshake must never race the project load. Faces attach once
            // the engine runs and detach before it stops (E19).
            jawataEngine.start();

            // Sprint 10 v1.4.0: prefer workspace.json in the JDT data dir as the
            // source of truth for what to load. The manager (jawata-studio)
            // writes this file. Fall back to the legacy JAVA_PROJECT_PATH env
            // var when workspace.json is absent (back-compat for direct manual
            // launches without the manager).
            CompletableFuture.runAsync(this::autoLoadProjects);

            // Run the main message loop (starts immediately, doesn't wait for project load).
            exit = runMessageLoop(transportConfig);
        } finally {
            jawataEngine.whiteboard().stopping();
            whiteboardTracker.close();
            hostRegistration.unregister();
        }

        log.info("JAWATA MCP Server stopped");
        return exit;
    }

    private void autoLoadProjects() {
        // The same question the workspace verbs ask: loading projects from workspace.json and watching it for changes is changing
        // the workspace, which a host that owns its own does not let the engine do.
        if (!jawataEngine.host().workspace().ownsWorkspace()) {
            log.info("The host owns the workspace; the engine does not load projects or watch workspace.json");
            return;
        }
        Path dataDir = directories.resolveDataDir();
        if (dataDir != null) {
            Path workspaceJson = DefaultEngineDirectories.findWorkspaceJson(dataDir);
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
            WorkspaceFileWatcher watcher = new WorkspaceFileWatcher(dataDir, service, jawataEngine.host().workspace());
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
        String projectPath = SettingKeys.PROJECT_PATH.value().orElse(null);
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
            jawataEngine.serve(transport);
        } catch (Exception e) {
            // A port that is taken is reported by the transport to the lifecycle (status 13, jawata-mcp#2); this is every other failure.
            log.error("Error in message loop", e);
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
        // What this process started is ended here; what the engine started (the reconciliation, the daily copy, the listener, the store)
        // is the engine's to end.
        WorkspaceFileWatcher watcher = workspaceWatcher;
        if (watcher != null) {
            watcher.close();
            workspaceWatcher = null;
        }
        jawataEngine.stop();
    }
}
