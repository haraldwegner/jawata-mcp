package org.jawata.core;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.jawata.core.host.HostPaths;
import org.jawata.core.host.HostPathsImpl;
import org.jawata.core.project.ProjectImporter;
import org.jawata.core.search.SearchService;
import org.jawata.core.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main JDT service implementation.
 * Ties together workspace management, project import, and search capabilities.
 */
public class JdtServiceImpl implements IJdtService {

    private static final Logger log = LoggerFactory.getLogger(JdtServiceImpl.class);

    private final WorkspaceManager workspaceManager;
    private final ProjectImporter projectImporter;
    private final int timeoutSeconds;

    private Path projectRoot;
    private HostPaths pathUtils;
    private IJavaProject javaProject;
    private SearchService searchService;
    private Instant loadedAt;

    // Project info for health_check
    private int sourceFileCount;
    private int packageCount;
    private List<String> packages;
    private ProjectImporter.BuildSystem buildSystem;

    // Sprint 10 multi-project state. Mirrors the legacy single-project fields
    // above for the default project, so single-project getters continue to
    // work unchanged. Additional projects loaded via addProject(Path) are
    // tracked here keyed by ProjectKeys.derive(path). The default key
    // points at the project returned by the legacy getJavaProject() etc.
    private final Map<String, LoadedProject> projectsByKey = new ConcurrentHashMap<>();
    private volatile String defaultProjectKey;

    /**
     * Sprint 14 (bugs.md #11): tracks recently-removed project keys so a
     * caller holding a stale key gets a distinct {@code PROJECT_KEY_DROPPED}
     * error (with the drop timestamp) instead of an ambiguous
     * {@code INVALID_PARAMETER}. Entries expire after {@link #DROP_TTL_MILLIS}
     * so an old typo eventually falls through to the regular not-found path.
     */
    private final Map<String, Long> droppedKeyTimestamps = new ConcurrentHashMap<>();

    /**
     * How long {@link #droppedKeyTimestamps} retains an entry. 5 minutes is
     * long enough for a multi-turn agent session to recognise the drop and
     * re-acquire via {@code list_projects}, short enough that a long-past
     * typo doesn't surface as "dropped" weeks later.
     */
    static final long DROP_TTL_MILLIS = 5L * 60 * 1000;

    /**
     * Stage 12.1 — the INVENTORY: every PDE project's manifest facts plus its
     * .classpath project references and JUnit-container names, parsed ONCE at
     * add and evicted at remove/wipe. The resolver reads this COMPLETE map,
     * which is what removed the order-dependence (a project used to see only
     * the siblings registered before it — the measured 431).
     */
    private final Map<String, PdeInputs> pdeInputsByKey = new ConcurrentHashMap<>();

    /** One PDE project's resolver inputs — pure data, parsed at add. */
    private record PdeInputs(org.jawata.core.resolve.BundleFacts facts,
                             List<String> projectRefs,
                             List<String> junitBundles) {
    }

    /**
     * Stage 12.1 — THE RESOLVE LOCK. Nothing used to serialize the watcher
     * thread against MCP request threads mutating the workspace (risk R1);
     * every load/add/remove and the re-resolve itself now runs under this.
     * Ordering is ENFORCED at acquisition, not documented: the lock is taken
     * BEFORE any workspace scheduling rule, never from a resource listener —
     * see {@link #assertNoWorkspaceRuleHeld()}.
     */
    private final Object resolveLock = new Object();

    // Workspace-scoped SearchService — searches across all loaded projects.
    // Lazily built on first access, invalidated whenever the project map
    // changes (addProject / removeProject / loadProject). With a single
    // project loaded the scope contains that one project; behavior is
    // identical to the per-project SearchService. With N projects, search
    // sees all N classpaths at once — the expected behavior under Sprint
    // 10's port-grouped service consolidation.
    private volatile SearchService workspaceSearchService;

    public JdtServiceImpl() {
        this.workspaceManager = new WorkspaceManager();
        this.projectImporter = new ProjectImporter();
        this.timeoutSeconds = parseTimeout();
    }

    private static int parseTimeout() {
        String timeout = System.getenv("JAWATA_TIMEOUT_SECONDS");
        if (timeout == null) return 30;
        try {
            int seconds = Integer.parseInt(timeout);
            return Math.max(5, Math.min(seconds, 300));
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    /**
     * Load a project for analysis as the sole / default project, replacing
     * any previously loaded projects.
     *
     * <p>Sprint 10 semantics: this method clears the multi-project map and
     * registers the loaded project as the default. Use {@link #addProject}
     * to append a project to a multi-project workspace without clearing.
     *
     * @param path Path to the project root
     * @throws CoreException if project loading fails
     */
    public void loadProject(Path path) throws CoreException {
        log.info("Loading project: {}", path);

        // Clear the multi-project map — load_project semantics are
        // "wipe and load one", per Sprint 10 ADR Q6.
        //
        // AND DELETE the replaced projects from the Eclipse workspace. They
        // used to be dropped from the map only, leaving live, indexed, linked
        // IProjects behind on EVERY load — an unbounded leak on a long-lived
        // resident, and in the test suite the churn factory behind the
        // load-dependent lookup failures (dozens of dead projects, all linking
        // the same fixture dirs, kept the indexer and delta broadcaster busy
        // while live tests queried the model).
        LoadedProject loaded;
        assertNoWorkspaceRuleHeld();
        synchronized (resolveLock) {
            for (LoadedProject stale : projectsByKey.values()) {
                deleteWorkspaceProject(stale);
            }
            projectsByKey.clear();
            // Audit B2: the WIPE evicts the whole inventory. Leaving 29 dead
            // projects' facts behind would wire the next resolve against
            // deleted IProjects — the old code survived this only through the
            // registry's read-time exists() check, which 12.1 deletes.
            pdeInputsByKey.clear();
            defaultProjectKey = null;
            workspaceSearchService = null;

            loaded = loadInternal(path);
            defaultProjectKey = loaded.projectKey();
            resolveWorkspaceLocked();
            loaded = projectsByKey.getOrDefault(loaded.projectKey(), loaded);
        }

        // Mirror the loaded project into the legacy single-project fields
        // so existing tools that read getJavaProject() / getProjectRoot()
        // / getSourceFileCount() / etc. continue to work without changes.
        applyToLegacyFields(loaded);

        log.info("Project loaded successfully at {} as default key '{}'",
            loaded.loadedAt(), defaultProjectKey);
    }

    /**
     * Append a project to the workspace without clearing existing projects.
     * The default project key is unchanged; the appended project is
     * accessible via {@link #getProject(String)} using its derived key.
     *
     * @param path Path to the project root
     * @return the registered LoadedProject (with its derived key)
     * @throws CoreException if project loading fails
     */
    public LoadedProject addProject(Path path) throws CoreException {
        log.info("Adding project to workspace: {}", path);
        LoadedProject loaded;
        assertNoWorkspaceRuleHeld();
        synchronized (resolveLock) {
            loaded = loadInternal(path);
            workspaceSearchService = null;  // scope changed — rebuild on next access
            // bugs.md #11 (Sprint 14): re-adding a project with the same key as a
            // previously-dropped one clears the dropped-key entry. Otherwise the
            // dropped marker would shadow the live project until TTL expiry.
            droppedKeyTimestamps.remove(loaded.projectKey());
            resolveWorkspaceLocked();
            // The POST-resolve record — the pre-resolve one snapshots an
            // unresolved list the re-resolve may have just changed (audit B2's
            // ordering-bug class).
            loaded = projectsByKey.getOrDefault(loaded.projectKey(), loaded);
            if (defaultProjectKey == null) {
                // First project ever — promote to default so single-project
                // tools have something to read.
                defaultProjectKey = loaded.projectKey();
                applyToLegacyFields(loaded);
            }
        }
        log.info("Project added at {} with key '{}'", loaded.loadedAt(), loaded.projectKey());
        return loaded;
    }

    /**
     * Sprint 14 (bugs.md #11): if a caller holds a {@code projectKey} that
     * USED to be valid but has been dropped, return the drop timestamp for
     * up to {@link #DROP_TTL_MILLIS} after the drop. Past the TTL, the entry
     * is evicted and this returns {@link Optional#empty()}.
     */
    @Override
    public Optional<Long> wasRecentlyDropped(String projectKey) {
        Long ts = droppedKeyTimestamps.get(projectKey);
        if (ts == null) return Optional.empty();
        if (System.currentTimeMillis() - ts > DROP_TTL_MILLIS) {
            droppedKeyTimestamps.remove(projectKey, ts);
            return Optional.empty();
        }
        return Optional.of(ts);
    }

    /**
     * Test-only: forcibly expire a dropped-key entry by setting its
     * timestamp far enough in the past that {@link #wasRecentlyDropped}
     * sees it as expired on the next call. Lets the dedicated TTL test run
     * without sleeping for {@link #DROP_TTL_MILLIS}.
     */
    void expireDroppedKeyForTest(String projectKey) {
        droppedKeyTimestamps.computeIfPresent(projectKey,
            (k, v) -> System.currentTimeMillis() - DROP_TTL_MILLIS - 1);
    }

    /**
     * Remove a project from the workspace. If the removed project was the
     * default, the next available project (if any) becomes the default.
     *
     * @param projectKey key of the project to remove
     * @return true if a project with that key was loaded and removed
     */
    public boolean removeProject(String projectKey) {
        assertNoWorkspaceRuleHeld();
        synchronized (resolveLock) {
            LoadedProject removed = projectsByKey.remove(projectKey);
            if (removed == null) {
                return false;
            }
            // bugs.md #11 (Sprint 14): record the drop so a stale caller gets a
            // distinct PROJECT_KEY_DROPPED instead of INVALID_PARAMETER.
            droppedKeyTimestamps.put(projectKey, System.currentTimeMillis());
            workspaceSearchService = null;  // scope changed — rebuild on next access
            pdeInputsByKey.remove(projectKey);
            // And actually delete it from the Eclipse workspace — "removed" used to
            // mean "forgotten by the service but still alive in the model".
            deleteWorkspaceProject(removed);
            log.info("Removed project '{}' from workspace", projectKey);
            // Stage 12.1 (R22): RETRO-UNWIRE the dependents — a project entry
            // pointing at a deleted project is a hard error. Since 12.2 the
            // re-resolve consults the pools, so a same-name pool jar FAILS
            // OVER; only a true miss becomes an honest row.
            resolveWorkspaceLocked();
            if (projectKey.equals(defaultProjectKey)) {
                // Pick a new default deterministically: the first remaining key
                // by natural string order, or null if no projects remain.
                defaultProjectKey = projectsByKey.keySet().stream().sorted().findFirst().orElse(null);
                if (defaultProjectKey != null) {
                    applyToLegacyFields(projectsByKey.get(defaultProjectKey));
                } else {
                    clearLegacyFields();
                }
            }
            return true;
        }
    }

    /**
     * Stage 12.1 — THE WORKSPACE RE-RESOLVE. One resolve over the COMPLETE
     * inventory, one {@code IWorkspace.run} applying the deltas, one
     * republication of the affected {@link LoadedProject} records.
     *
     * <p>Caller MUST hold {@link #resolveLock}. The whole body is the
     * behaviour change this plan exists for: order stops mattering because
     * the resolver sees everyone; the O(N²) initial-load storm is capped
     * because the quadratic part is the PURE resolve over cached facts while
     * classpath sets happen only for projects whose wiring actually CHANGED,
     * all inside one workspace operation (one resource delta — and with
     * autobuild measured ON, one deferred build pass, the recorded R10
     * ruling).</p>
     */
    @Override
    public void reresolveWorkspace() {
        assertNoWorkspaceRuleHeld();
        synchronized (resolveLock) {
            org.jawata.core.project.ExternalBundlePool.clearCaches();
            resolveWorkspaceLocked();
        }
    }

    private void resolveWorkspaceLocked() {
        if (pdeInputsByKey.isEmpty()) {
            return; // a workspace of plain build units has nothing to wire
        }
        // The resolver's world: full facts per symbolic name, plus .classpath
        // project references appended as Require-Bundle-equivalent
        // requirements (they name a sibling by its project name, which in a
        // PDE tree IS the symbolic name — and they used to wire only when the
        // sibling happened to load first).
        Map<String, org.jawata.core.resolve.BundleFacts> world = new LinkedHashMap<>();
        Map<String, String> keyBySymbolicName = new LinkedHashMap<>();
        for (Map.Entry<String, PdeInputs> e : pdeInputsByKey.entrySet()) {
            PdeInputs in = e.getValue();
            org.jawata.core.resolve.BundleFacts facts = in.facts();
            if (!in.projectRefs().isEmpty()) {
                List<org.jawata.core.resolve.OsgiHeaders.Requirement> merged =
                    new ArrayList<>(facts.requiredBundles());
                for (String ref : in.projectRefs()) {
                    merged.add(new org.jawata.core.resolve.OsgiHeaders.Requirement(
                        ref, Optional.empty(), false, false));
                }
                facts = new org.jawata.core.resolve.BundleFacts(facts.symbolicName(),
                    facts.version(), merged, facts.importedPackages(),
                    facts.exportedPackages(), facts.fragmentHost(),
                    facts.bundleClassPath(), facts.platformFilter());
            }
            org.jawata.core.resolve.BundleFacts previous =
                world.put(facts.symbolicName(), facts);
            if (previous != null) {
                log.warn("two loaded projects declare Bundle-SymbolicName '{}' — "
                    + "the later one wins for resolution", facts.symbolicName());
            }
            keyBySymbolicName.put(facts.symbolicName(), e.getKey());
        }

        // The pool is indexed ONCE per re-resolve (R2/R17) and handed to the
        // resolver as data — jar arbitration, fragments and re-export closure
        // are the resolver's job (12.2), the pool only reads manifests.
        org.jawata.core.project.ExternalBundlePool pool =
            org.jawata.core.project.ExternalBundlePool.index(
                org.jawata.core.project.ExternalBundlePool.defaultPoolDirs());
        Map<String, org.jawata.core.resolve.PlatformResolver.Wiring> wirings =
            new org.jawata.core.resolve.GraphWalkResolver()
                .resolve(world, pool.poolBundles(), currentPlatform());

        java.util.function.Function<String, Optional<IJavaProject>> projectLookup = name -> {
            String key = keyBySymbolicName.get(name);
            if (key == null) {
                return Optional.empty();
            }
            LoadedProject p = projectsByKey.get(key);
            return p == null ? Optional.empty() : Optional.of(p.javaProject());
        };

        try {
            ResourcesPlugin.getWorkspace().run(monitor -> {
                for (Map.Entry<String, PdeInputs> e : pdeInputsByKey.entrySet()) {
                    LoadedProject project = projectsByKey.get(e.getKey());
                    org.jawata.core.resolve.PlatformResolver.Wiring wiring =
                        wirings.get(e.getValue().facts().symbolicName());
                    if (project == null || wiring == null) {
                        continue;
                    }
                    applyWiring(project, e.getValue(), wiring, pool, projectLookup);
                }
            }, ResourcesPlugin.getWorkspace().getRoot(), 0, new NullProgressMonitor());
        } catch (CoreException e) {
            log.error("workspace re-resolve failed: {}", e.getMessage(), e);
        }
        workspaceSearchService = null;
    }

    /**
     * Delta-apply one project's wiring: preserve every non-wire entry
     * VERBATIM (source entries' linked folders are delete+create — risk R3),
     * replace the wire tail, skip the write entirely when nothing changed,
     * and REPUBLISH the {@link LoadedProject} record when its unresolved list
     * moved (the studio reads that record — risk R4).
     */
    private void applyWiring(LoadedProject project, PdeInputs inputs,
            org.jawata.core.resolve.PlatformResolver.Wiring wiring,
            org.jawata.core.project.ExternalBundlePool pool,
            java.util.function.Function<String, Optional<IJavaProject>> projectLookup)
            throws CoreException {
        IJavaProject jp = project.javaProject();
        IClasspathEntry[] current = jp.getRawClasspath();
        List<IClasspathEntry> preserved = new ArrayList<>();
        List<IClasspathEntry> currentWire = new ArrayList<>();
        Set<org.eclipse.core.runtime.IPath> occupiedLibs = new HashSet<>();
        Set<org.eclipse.core.runtime.IPath> occupiedProjects = new HashSet<>();
        for (IClasspathEntry entry : current) {
            if (org.jawata.core.project.ClasspathApplier.isWire(entry)) {
                currentWire.add(entry);
                continue;
            }
            preserved.add(entry);
            if (entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY) {
                occupiedLibs.add(entry.getPath());
            } else if (entry.getEntryKind() == IClasspathEntry.CPE_PROJECT) {
                occupiedProjects.add(entry.getPath());
            }
        }

        org.jawata.core.project.ClasspathApplier.WireResult wire =
            org.jawata.core.project.ClasspathApplier.computeWire(wiring, pool,
                inputs.junitBundles(), projectLookup, occupiedLibs, occupiedProjects);

        if (!sameEntries(currentWire, wire.entries())) {
            List<IClasspathEntry> next = new ArrayList<>(preserved);
            next.addAll(wire.entries());
            jp.setRawClasspath(next.toArray(new IClasspathEntry[0]),
                jp.getOutputLocation(), new NullProgressMonitor());
        }
        if (!project.unresolved().equals(wire.unresolved())) {
            // Republish: same key, same IJavaProject, SAME SearchService —
            // only the unresolved list moves. An immutable record nobody
            // republishes is how the studio reads stale rows forever.
            projectsByKey.put(project.projectKey(), new LoadedProject(
                project.projectKey(), project.projectRoot(), jp,
                project.searchService(), project.pathUtils(), project.loadedAt(),
                project.sourceFileCount(), project.packageCount(),
                project.packages(), project.buildSystem(), wire.unresolved()));
        }
    }

    /** Same wiring? Kind, path and exported flag — the three facts a wire entry carries. */
    private static boolean sameEntries(List<IClasspathEntry> a, List<IClasspathEntry> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            IClasspathEntry x = a.get(i);
            IClasspathEntry y = b.get(i);
            if (x.getEntryKind() != y.getEntryKind()
                    || !x.getPath().equals(y.getPath())
                    || x.isExported() != y.isExported()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The lock-ordering ASSERTION (audit N7, placement fixed at the C12.1
     * audit's B2): the resolve lock is acquired BEFORE any workspace
     * scheduling rule — and the check runs BEFORE monitor-enter, because a
     * rule-holding thread blocked AT the monitor would deadlock without the
     * assertion ever executing. Checked first, it fails loudly instead.
     */
    private static void assertNoWorkspaceRuleHeld() {
        org.eclipse.core.runtime.jobs.ISchedulingRule rule =
            org.eclipse.core.runtime.jobs.Job.getJobManager().currentRule();
        if (rule != null) {
            throw new IllegalStateException(
                "resolve lock acquired while holding workspace rule " + rule
                    + " — lock BEFORE rule, never from inside a workspace "
                    + "operation or resource listener");
        }
    }

    /**
     * The running machine's platform triple, injected ONCE — the resolver
     * never reads system properties. Through the HOST BOUNDARY, not a raw
     * os.name read (HostOS's own history: a contains(\"win\") predicate once
     * classified Darwin as Windows).
     */
    private static org.jawata.core.resolve.PlatformResolver.Platform currentPlatform() {
        String arch = "aarch64".equals(org.jawata.core.host.HostOS.osArch())
            ? "aarch64" : "x86_64";
        return switch (org.jawata.core.host.HostOS.current()) {
            case WINDOWS -> new org.jawata.core.resolve.PlatformResolver.Platform(
                "win32", "win32", arch);
            case MACOS -> new org.jawata.core.resolve.PlatformResolver.Platform(
                "macosx", "cocoa", arch);
            case LINUX -> new org.jawata.core.resolve.PlatformResolver.Platform(
                "linux", "gtk", arch);
        };
    }

    /**
     * Shared loader: detect, configure, register. Does not clear or
     * change the default key — callers decide that.
     */
    private LoadedProject loadInternal(Path path) throws CoreException {
        Path absRoot = path.toAbsolutePath().normalize();
        HostPaths utils = new HostPathsImpl(absRoot);

        workspaceManager.initialize();

        ProjectImporter.BuildSystem detected = projectImporter.detectBuildSystem(absRoot);
        int fileCount = projectImporter.countSourceFiles(absRoot);
        List<String> pkgList = projectImporter.findPackages(absRoot);

        log.info("Detected {} build system, {} source files, {} packages",
            detected, fileCount, pkgList.size());

        // Derive a project key, disambiguate if it collides with an
        // existing loaded project.
        String key = ProjectKeys.derive(absRoot);
        if (projectsByKey.containsKey(key)) {
            key = ProjectKeys.disambiguate(key, absRoot);
        }

        String projectName = "jawata-" + absRoot.getFileName();
        IProject project = workspaceManager.createLinkedProject(projectName, absRoot);

        org.jawata.core.project.ImportResult imported =
            projectImporter.configureJavaProject(project, absRoot, workspaceManager);
        IJavaProject jp = imported.javaProject();
        SearchService search = new SearchService(jp);

        LoadedProject loaded = new LoadedProject(
            key, absRoot, jp, search, utils, Instant.now(),
            fileCount, pkgList.size(), pkgList, detected, imported.unresolved()
        );
        projectsByKey.put(key, loaded);

        // Stage 12.1 — the inventory: parsed ONCE here, read by every
        // subsequent resolve. A non-bundle project (no manifest) simply has
        // no entry and bypasses the pipeline entirely.
        try {
            Optional<org.jawata.core.resolve.BundleFacts> facts =
                org.jawata.core.resolve.BundleFacts.of(absRoot);
            if (facts.isPresent()) {
                org.jawata.core.project.ProjectImporter.ClasspathInfo cp =
                    org.jawata.core.project.ProjectImporter.readEclipseClasspath(absRoot);
                pdeInputsByKey.put(key, new PdeInputs(facts.get(), cp.projectRefs(),
                    org.jawata.core.project.ProjectImporter.junitContainerBundles(cp.containers())));
            }
        } catch (java.io.IOException e) {
            log.warn("Cannot read bundle manifest at {}: {}", absRoot, e.getMessage());
        }
        return loaded;
    }

    /**
     * Delete a project this service created from the shared Eclipse workspace.
     * Content-preserving ({@code deleteContent=false}): the project is a thin
     * container of LINKED folders pointing at the user's real source tree —
     * only the workspace-side metadata goes, never the linked-to files.
     *
     * <p>A deletion failure is logged and swallowed deliberately: the project
     * is already gone from this service's map, and failing the caller's
     * load/remove over cleanup would trade a leak for an outage. The leak is
     * bounded (one project) and visible in the log.</p>
     */
    private void deleteWorkspaceProject(LoadedProject lp) {
        try {
            lp.javaProject().getProject().delete(false, true, new NullProgressMonitor());
            log.debug("Deleted workspace project for key '{}'", lp.projectKey());
        } catch (Exception e) {
            log.warn("Could not delete workspace project for key '{}': {}",
                lp.projectKey(), e.getMessage());
        }
    }

    /**
     * Dispose this service: delete every workspace project it created and
     * clear all state. Test harnesses MUST call this per test — without it,
     * every test leaks its linked projects into the JVM-shared workspace,
     * and the accumulated pile keeps the JDT indexer and delta broadcaster
     * churning underneath every later test in the same JVM.
     */
    public void dispose() {
        // Under the SAME lock as every other mutator (C12.1 audit M4): an
        // unlocked dispose racing a re-resolve tears the maps — and like the
        // loadProject wipe, it must evict the INVENTORY, or stale facts wire
        // the next service's resolve against deleted IProjects.
        assertNoWorkspaceRuleHeld();
        synchronized (resolveLock) {
            for (LoadedProject lp : projectsByKey.values()) {
                deleteWorkspaceProject(lp);
            }
            projectsByKey.clear();
            pdeInputsByKey.clear();
            droppedKeyTimestamps.clear();
            defaultProjectKey = null;
            workspaceSearchService = null;
            clearLegacyFields();
        }
    }

    /** Mirror a LoadedProject into the legacy single-project fields. */
    private void applyToLegacyFields(LoadedProject loaded) {
        this.projectRoot = loaded.projectRoot();
        this.pathUtils = loaded.pathUtils();
        this.javaProject = loaded.javaProject();
        this.searchService = loaded.searchService();
        this.loadedAt = loaded.loadedAt();
        this.sourceFileCount = loaded.sourceFileCount();
        this.packageCount = loaded.packageCount();
        this.packages = loaded.packages();
        this.buildSystem = loaded.buildSystem();
    }

    /** Reset the legacy single-project fields when no default exists. */
    private void clearLegacyFields() {
        this.projectRoot = null;
        this.pathUtils = null;
        this.javaProject = null;
        this.searchService = null;
        this.loadedAt = null;
        this.sourceFileCount = 0;
        this.packageCount = 0;
        this.packages = null;
        this.buildSystem = null;
    }

    // ========== Sprint 10 multi-project getters ==========

    @Override
    public Optional<String> defaultProjectKey() {
        return Optional.ofNullable(defaultProjectKey);
    }

    @Override
    public Optional<LoadedProject> getProject(String projectKey) {
        return Optional.ofNullable(projectsByKey.get(projectKey));
    }

    @Override
    public Collection<String> projectKeys() {
        return Collections.unmodifiableSet(projectsByKey.keySet());
    }

    @Override
    public Collection<LoadedProject> allProjects() {
        return Collections.unmodifiableCollection(projectsByKey.values());
    }

    @Override
    public HostPaths getPathUtils() {
        return pathUtils;
    }

    @Override
    public Path getProjectRoot() {
        return projectRoot;
    }

    @Override
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    @Override
    public <T> T executeWithTimeout(Callable<T> operation, String operationName) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<T> future = executor.submit(operation);

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException(
                operationName + " timed out after " + timeoutSeconds + " seconds"
            );
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(operationName + " failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(operationName + " was interrupted");
        } finally {
            executor.shutdownNow();
        }
    }

    // Getters for project info (used by LoadProjectTool response)

    @Override
    public IJavaProject getJavaProject() {
        return javaProject;
    }

    @Override
    public SearchService getSearchService() {
        // Sprint 10: prefer the workspace-scoped SearchService so queries
        // span all loaded projects. Falls back to the legacy per-project
        // service when no projects are loaded yet (e.g., between the
        // service constructor and the first loadProject() / addProject()).
        SearchService workspace = getOrBuildWorkspaceSearchService();
        return workspace != null ? workspace : searchService;
    }

    /**
     * Lazily build a SearchService whose scope spans every currently loaded
     * project. Recomputed only when the cache is null (set by addProject /
     * removeProject / loadProject mutations).
     */
    private SearchService getOrBuildWorkspaceSearchService() {
        SearchService cached = workspaceSearchService;
        if (cached != null || projectsByKey.isEmpty()) {
            return cached;
        }
        synchronized (this) {
            if (workspaceSearchService == null && !projectsByKey.isEmpty()) {
                IJavaProject[] all = projectsByKey.values().stream()
                    .map(LoadedProject::javaProject)
                    .toArray(IJavaProject[]::new);
                workspaceSearchService = new SearchService(all);
            }
        }
        return workspaceSearchService;
    }

    public Instant getLoadedAt() {
        return loadedAt;
    }

    public int getSourceFileCount() {
        return sourceFileCount;
    }

    public int getPackageCount() {
        return packageCount;
    }

    public List<String> getPackages() {
        return packages;
    }

    public ProjectImporter.BuildSystem getBuildSystem() {
        return buildSystem;
    }

    public int getClasspathEntryCount() {
        try {
            return javaProject != null ? javaProject.getRawClasspath().length : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ========== New Interface Methods for Tools ==========

    @Override
    public ICompilationUnit getCompilationUnit(Path filePath) {
        // Try the default project first (fast path, also the only path
        // pre-Sprint 10), then fan out across the rest of the workspace
        // so a tool call without projectKey can resolve files in any
        // loaded project — required for the single-workspace mode where
        // one jawata process holds N projects.
        if (javaProject != null) {
            ICompilationUnit cu = lookupCompilationUnit(javaProject, filePath);
            if (cu != null) return cu;
        }
        for (LoadedProject other : projectsByKey.values()) {
            if (other.javaProject() == javaProject) continue; // already tried
            ICompilationUnit cu = lookupCompilationUnit(other.javaProject(), filePath);
            if (cu != null) return cu;
        }
        log.debug("Compilation unit not found for: {}", filePath);
        return null;
    }

    /**
     * The part of a PROJECT-RELATIVE path that lies under a source root, or {@code null}
     * when it does not lie under this one.
     *
     * <p>Sprint 28 (v3.6.4): jawata emitted a path it would not accept. Responses format
     * paths through {@code PathUtils.formatPath}, which returns them RELATIVE to the
     * project root — {@code test/com/example/FooTest.java}. Handing that exact string back
     * to any tool that resolves a file returned {@code FILE_NOT_FOUND}, because the lookup
     * only ever matched ABSOLUTE paths against the source roots and gave up on anything
     * else. Measured on a live project: the absolute form resolved and reported its own
     * path back in the relative form, which then did not resolve — with or without
     * {@code projectKey}. An agent that passes a path jawata just gave it was told the file
     * does not exist, which is the same lie as an empty result on a failed lookup.</p>
     *
     * <p>Matching is by root TAIL: a source root {@code …/com.example/test} ends with the
     * relative path's first segment {@code test}, and {@code …/proj/src/main/java} ends
     * with its first three. The longest match wins, so a root nested inside another is
     * preferred, and the caller still verifies the compilation unit exists — a coincidental
     * tail match resolves to nothing and is discarded rather than returned.</p>
     *
     * <p>Output is unchanged. This only widens what the lookup ACCEPTS, so no response
     * shape, path form or existing caller moves.</p>
     */
    private static Path underRoot(Path rootPath, Path projectRelative) {
        int segments = projectRelative.getNameCount();
        if (segments < 2) return null; // needs at least one root segment plus a file name
        int max = Math.min(rootPath.getNameCount(), segments - 1);
        for (int k = max; k >= 1; k--) {
            if (rootPath.endsWith(projectRelative.subpath(0, k))) {
                return projectRelative.subpath(k, segments);
            }
        }
        return null;
    }

    /**
     * Resolve a file to its compilation unit within ONE project.
     *
     * <p>Package-private on purpose (Sprint 28, v3.6.2): {@link ScopedJdtService}
     * — the view a tool gets when it passes {@code projectKey} — used to carry
     * its own COPY of this logic. v3.6.1 fixed the source-folder handling here
     * and the copy kept the old Maven-prefix guess, so an unscoped call
     * resolved a plug-in project's {@code test/} folder and a scoped call did
     * not. Measured on a live 1040-source project: {@code find_tests} answered
     * 126 unscoped and 1 scoped, seconds apart, same resident. One
     * implementation now, so the two cannot drift again.</p>
     */
    static ICompilationUnit lookupCompilationUnit(IJavaProject jp, Path filePath) {
        if (jp == null) return null;
        try {
            // Sprint 28 (v3.6.1): ASK THE MODEL where the source roots are.
            //
            // This used to derive the type name by guessing the source folder
            // from a fixed list of Maven/Gradle path conventions
            // ("src/main/java/", ..., "src/"). Any project whose source folder
            // is named something else — an Eclipse plug-in project with a
            // top-level `test/`, `tests/` or `integration-tests/` — matched no
            // prefix, so the ENTIRE absolute path was turned into a "package
            // name" and every lookup failed. The caller (SourceScan) records
            // that as unresolvable, which is honest, but the effect was that
            // find_tests reported 1 of 20+ test classes and the quality scans
            // silently missed 142 of 1040 files on a real PDE project, while
            // the JDT search engine read the very same files without trouble.
            //
            // The source roots are a fact the Java model already holds, and
            // getResource().getLocation() resolves a LINKED folder to its real
            // filesystem target — which is exactly how jawata mounts a
            // project's source folders into its synthesized workspace. So match
            // the file against the actual roots and derive the name from the
            // one that contains it. The convention guess stays underneath as a
            // fallback for paths the model cannot place.
            ICompilationUnit fromRoots = lookupViaSourceRoots(jp, filePath);
            if (fromRoots != null) return fromRoots;

            String pathStr = filePath.toString().replace('\\', '/');
            String classPath = pathStr;
            String[] sourcePrefixes = {"src/main/java/", "src/test/java/", "src/main/kotlin/", "src/test/kotlin/", "src/"};
            for (String prefix : sourcePrefixes) {
                if (pathStr.contains(prefix)) {
                    int idx = pathStr.indexOf(prefix);
                    classPath = pathStr.substring(idx + prefix.length());
                    break;
                }
            }
            String withoutExt = classPath.replace(".java", "");
            String qualifiedName = withoutExt.replace('/', '.');
            IType type = jp.findType(qualifiedName);
            if (type != null) return type.getCompilationUnit();

            for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    int lastSlash = classPath.lastIndexOf('/');
                    String packageName = lastSlash > 0 ? classPath.substring(0, lastSlash).replace('/', '.') : "";
                    String className = lastSlash > 0 ? classPath.substring(lastSlash + 1) : classPath;
                    IPackageFragment pkg = root.getPackageFragment(packageName);
                    if (pkg != null && pkg.exists()) {
                        ICompilationUnit cu = pkg.getCompilationUnit(className);
                        if (cu != null && cu.exists()) return cu;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolve a file through the project's DECLARED source roots, whatever they
     * are named.
     *
     * <p>Sprint 28 (v3.6.1). Each {@code K_SOURCE} package-fragment root knows
     * its own filesystem location, and for a linked folder that location is the
     * link TARGET — so a project mounted as {@code src-1-test -> <root>/test}
     * resolves correctly without anyone hard-coding the name {@code test}.
     * Longest matching root wins, so a nested source folder is preferred over
     * its parent.</p>
     *
     * @return the compilation unit, or null when no declared root contains the
     *         file (the caller then falls back to the convention guess)
     */
    private static ICompilationUnit lookupViaSourceRoots(IJavaProject jp, Path filePath) throws JavaModelException {
        Path absolute = filePath.isAbsolute() ? filePath.normalize() : null;
        Path projectRelative = absolute == null ? filePath.normalize() : null;

        IPackageFragmentRoot bestRoot = null;
        Path bestRelative = null;
        for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;
            IResource resource = root.getResource();
            if (resource == null) continue;
            IPath location = resource.getLocation();
            if (location == null) continue;
            Path rootPath = Path.of(location.toOSString()).normalize();
            Path relative;
            if (absolute != null) {
                if (!absolute.startsWith(rootPath)) continue;
                relative = rootPath.relativize(absolute);
            } else {
                relative = underRoot(rootPath, projectRelative);
                if (relative == null) continue;
            }
            // Longest root wins: prefer the most specific source folder.
            if (bestRelative == null || relative.getNameCount() < bestRelative.getNameCount()) {
                bestRoot = root;
                bestRelative = relative;
            }
        }
        if (bestRoot == null) return null;

        String relative = bestRelative.toString().replace('\\', '/');
        int lastSlash = relative.lastIndexOf('/');
        String packageName = lastSlash > 0 ? relative.substring(0, lastSlash).replace('/', '.') : "";
        String className = lastSlash > 0 ? relative.substring(lastSlash + 1) : relative;
        IPackageFragment pkg = bestRoot.getPackageFragment(packageName);
        if (pkg == null || !pkg.exists()) return null;
        ICompilationUnit cu = pkg.getCompilationUnit(className);
        return cu != null && cu.exists() ? cu : null;
    }

    @Override
    public IJavaElement getElementAtPosition(Path filePath, int line, int column) {
        ICompilationUnit cu = getCompilationUnit(filePath);
        if (cu == null) {
            log.debug("Compilation unit not found for: {}", filePath);
            return null;
        }

        try {
            // Ensure the compilation unit is open and reconciled for codeSelect to work
            if (!cu.isOpen()) {
                cu.open(null);
            }

            // Reconcile to ensure the AST is up to date
            cu.reconcile(ICompilationUnit.NO_AST, false, null, null);

            int offset = getOffset(cu, line, column);
            log.debug("Looking for element at {}:{}:{} (offset {})", filePath, line, column, offset);

            IJavaElement[] elements = cu.codeSelect(offset, 0);
            if (elements.length > 0) {
                log.debug("Found element: {} ({})", elements[0].getElementName(), elements[0].getClass().getSimpleName());
                return elements[0];
            }

            // Fallback: try to find element at offset using getElementAt
            IJavaElement element = cu.getElementAt(offset);
            if (element != null) {
                log.debug("Found element via getElementAt: {} ({})", element.getElementName(), element.getClass().getSimpleName());
                return element;
            }

            log.debug("No element found at position");
            return null;
        } catch (JavaModelException e) {
            log.warn("Error getting element at position: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public IType getTypeAtPosition(Path filePath, int line, int column) {
        IJavaElement element = getElementAtPosition(filePath, line, column);
        if (element instanceof IType type) {
            return type;
        }
        // If the element is within a type, try to get the enclosing type
        if (element != null) {
            IType enclosingType = (IType) element.getAncestor(IJavaElement.TYPE);
            return enclosingType;
        }
        return null;
    }

    @Override
    public IType findType(String typeName) {
        if (typeName == null || typeName.isBlank()) return null;
        // Track lookup FAILURES separately from misses: null may only mean
        // "the model answered: no such type". If any probed project failed to
        // answer and none produced the type, reporting null would let the
        // caller claim an absence over a lookup that never completed (the
        // "Type not found: org.junit.jupiter.api.Test" lie).
        JavaModelException failed = null;
        // Default project first, then the rest of the workspace.
        if (javaProject != null) {
            try {
                IType type = lookupType(javaProject, typeName);
                if (type != null) return type;
            } catch (JavaModelException e) {
                failed = e;
            }
        }
        for (LoadedProject other : projectsByKey.values()) {
            if (other.javaProject() == javaProject) continue;
            try {
                IType type = lookupType(other.javaProject(), typeName);
                if (type != null) return type;
            } catch (JavaModelException e) {
                if (failed == null) failed = e;
            }
        }
        if (failed != null) {
            throw new TypeLookupException(typeName, failed);
        }
        return null;
    }

    /**
     * Look a type up in one project. Returns null ONLY for a genuine miss —
     * a model failure PROPAGATES, because "the lookup failed" and "the type
     * does not exist" are different answers and the caller must not merge them.
     */
    private static IType lookupType(IJavaProject jp, String typeName) throws JavaModelException {
        if (jp == null) return null;
        // A CLOSED project is deliberately out of scope — a miss, not a failure
        // (asking a closed project anything throws; that must not read as
        // "the model could not answer").
        if (jp.getProject() != null && !jp.getProject().isOpen()) return null;
        IType type = jp.findType(typeName);
        if (type != null) return type;
        for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
            if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                for (IJavaElement child : root.getChildren()) {
                    if (child instanceof IPackageFragment pkg) {
                        for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                            for (IType t : cu.getTypes()) {
                                if (t.getElementName().equals(typeName)) return t;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public String getContextLine(ICompilationUnit cu, int offset) {
        try {
            String source = cu.getSource();
            if (source == null) {
                return "";
            }

            // Find line start
            int lineStart = offset;
            while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
                lineStart--;
            }

            // Find line end
            int lineEnd = offset;
            while (lineEnd < source.length() && source.charAt(lineEnd) != '\n' && source.charAt(lineEnd) != '\r') {
                lineEnd++;
            }

            String line = source.substring(lineStart, Math.min(lineEnd, lineStart + 200));
            return line.trim();
        } catch (JavaModelException e) {
            log.trace("Error getting context line: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public int getOffset(ICompilationUnit cu, int line, int column) {
        try {
            String source = cu.getSource();
            if (source == null) {
                return 0;
            }

            int offset = 0;
            int currentLine = 0;

            // Navigate to the correct line
            while (currentLine < line && offset < source.length()) {
                if (source.charAt(offset) == '\n') {
                    currentLine++;
                }
                offset++;
            }

            // Add column offset
            return Math.min(offset + column, source.length());
        } catch (JavaModelException e) {
            log.warn("Error calculating offset: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int getLineNumber(ICompilationUnit cu, int offset) {
        try {
            String source = cu.getSource();
            if (source == null) {
                return 0;
            }

            int line = 0;
            for (int i = 0; i < offset && i < source.length(); i++) {
                if (source.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        } catch (JavaModelException e) {
            log.warn("Error calculating line number: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int getColumnNumber(ICompilationUnit cu, int offset) {
        try {
            String source = cu.getSource();
            if (source == null) {
                return 0;
            }

            int column = 0;
            for (int i = offset - 1; i >= 0; i--) {
                if (source.charAt(i) == '\n') {
                    break;
                }
                column++;
            }
            return column;
        } catch (JavaModelException e) {
            log.warn("Error calculating column number: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public List<Path> getAllJavaFiles() {
        // Aggregate across every loaded project so workspace-wide listings
        // (find_unused_code etc.) see all .java files in single-workspace
        // mode. Falls back to the legacy javaProject when projectsByKey is
        // empty (transient state during construction).
        List<Path> files = new ArrayList<>();
        Collection<LoadedProject> loaded = projectsByKey.values();
        if (loaded.isEmpty()) {
            if (javaProject != null) {
                collectFilesFrom(javaProject, files);
            }
            return files;
        }
        for (LoadedProject p : loaded) {
            collectFilesFrom(p.javaProject(), files);
        }
        return files;
    }

    private void collectFilesFrom(IJavaProject jp, List<Path> files) {
        if (jp == null) return;
        // A CLOSED project is deliberately out of scope — a SKIP, not a failure
        // (JDT reports a closed project as non-existent, and asking it for
        // package roots throws). Same decision as FindDuplicateCodeTool.
        if (jp.getProject() != null && !jp.getProject().isOpen()) {
            return;
        }
        try {
            for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    collectJavaFiles(root, files);
                }
            }
        } catch (JavaModelException e) {
            // Do NOT return the partial list — a partial listing is
            // indistinguishable from a complete one at the call site, and it
            // has already produced a "0 findings" verdict over files that were
            // never enumerated (the LazyClassDetector flake). Fail LOUDLY.
            log.warn("Listing Java files of {} failed: {}", jp.getElementName(), e.getMessage());
            throw new SourceListingException(jp.getElementName(), e);
        }
    }

    private void collectJavaFiles(IPackageFragmentRoot root, List<Path> files) throws JavaModelException {
        for (IJavaElement child : root.getChildren()) {
            if (child instanceof IPackageFragment pkg) {
                for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                    IResource resource = cu.getResource();
                    if (resource != null) {
                        IPath location = resource.getLocation();
                        if (location != null) {
                            files.add(Path.of(location.toOSString()));
                        }
                    }
                }
            }
        }
    }
}
