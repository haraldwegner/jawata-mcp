package org.jawata.mcp.coverage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.jawata.core.LoadedProject;
import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostOS;
import org.jawata.core.host.HostProcessOutcome;
import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.execution.ForkedTestRunner;
import org.jawata.mcp.execution.RunnerClasspath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sprint 23 (D3) — the coverage collector + query service on the forked-
 * runner spine: mounts the JaCoCo agent on a run, writes the provenance
 * manifest when the run completes, and answers report/uncovered queries
 * from a small analyzed-model cache.
 */
public final class CoverageService {

    private static final Logger log = LoggerFactory.getLogger(CoverageService.class);

    private final CoverageStore store = new CoverageStore();
    /** Tiny model cache — reports are re-read often within a session. */
    private final Map<String, CoverageModel> modelCache =
        new LinkedHashMap<>(8, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CoverageModel> eldest) {
                return size() > 4;
            }
        };

    public CoverageStore store() {
        return store;
    }

    /** Pre-run: create the artifact + mount the agent on the runner spec. */
    public String mountCollector(ForkedTestRunner.Spec spec) throws IOException {
        return mountCollector(spec, false);
    }

    /**
     * Attribution variant (Stage 9): the agent runs with {@code output=none}
     * and the RUNNER dumps+resets at every test boundary into per-test
     * segment files; the union is merged into jacoco.exec at finalize so the
     * artifact doubles as a normal coverage artifact.
     */
    public String mountCollector(ForkedTestRunner.Spec spec, boolean attribution)
            throws IOException {
        String artifactId = store.newArtifactId();
        Path dir = store.createArtifactDir(artifactId);
        Path agentJar = RunnerClasspath.toolJar("org.jacoco.agent-");
        if (attribution) {
            spec.vmArgs.add(0, "-javaagent:" + agentJar + "=output=none");
            spec.attributionDir = dir.resolve("segments");
        } else {
            spec.vmArgs.add(0, "-javaagent:" + agentJar + "=destfile="
                + dir.resolve(CoverageStore.EXEC_FILE) + ",dumponexit=true");
        }
        return artifactId;
    }

    /** Post-run: write the provenance manifest — evidence is only now an artifact. */
    public CoverageManifest finalizeArtifact(String artifactId, LoadedProject project,
            ForkedTestRunner.Spec spec, ForkedTestRunner.Result result,
            String framework, String evidenceKind) throws IOException {
        CoverageManifest m = new CoverageManifest();
        m.artifactId = artifactId;
        m.createdAt = Instant.now().toString();
        m.jawataVersion = CoverageService.class.getPackage() == null ? "dev"
            : String.valueOf(CoverageService.class.getPackage().getImplementationVersion());
        m.jacocoVersion = "0.8.15";
        m.jdkVersion = System.getProperty("java.version");
        m.environment = HostOS.description();
        m.projectKey = project.projectKey();
        m.projectRoot = project.projectRoot().toString();
        m.framework = framework;
        m.evidenceKind = evidenceKind == null || evidenceKind.isBlank() ? "unit" : evidenceKind;
        m.selectClasses = new ArrayList<>(spec.selectClasses);
        m.selectMethods = new ArrayList<>(spec.selectMethods);
        m.selectPackages = new ArrayList<>(spec.selectPackages);

        collectRoots(project.javaProject(), m);
        gitProvenance(project.projectRoot(), m);

        Path segments = store.root().resolve(artifactId).resolve("segments");
        if (Files.isDirectory(segments)) {
            m.attribution = true;
            mergeSegments(segments, store.execFile(artifactId));
        }

        m.runFinalized = result.evidenceFinalized;
        m.completionStatus = result.evidenceFinalized ? "FINALIZED"
            : result.cancelled ? "CANCELLED"
            : result.timedOut ? "TIMED_OUT" : "ABNORMAL";
        m.testsTotal = result.total;
        m.testsPassed = result.passed;
        m.testsFailed = result.failed;
        m.testsSkipped = result.skipped;

        store.writeManifest(artifactId, m);
        return m;
    }

    /** Output folders = class roots (bundle identity); source folders for provenance. */
    private static void collectRoots(IJavaProject project, CoverageManifest m) {
        try {
            org.eclipse.core.resources.IWorkspaceRoot wsRoot =
                org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot();
            org.eclipse.core.runtime.IPath defaultOut = project.getOutputLocation();
            addWorkspacePath(wsRoot, defaultOut, m.classRoots);
            for (IClasspathEntry entry : project.getResolvedClasspath(true)) {
                if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
                    addWorkspacePath(wsRoot, entry.getPath(), m.sourceRoots);
                    if (entry.getOutputLocation() != null) {
                        addWorkspacePath(wsRoot, entry.getOutputLocation(), m.classRoots);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("cannot collect class/source roots: {}", e.getMessage());
        }
    }

    private static void addWorkspacePath(org.eclipse.core.resources.IWorkspaceRoot wsRoot,
            org.eclipse.core.runtime.IPath path, List<String> into) {
        if (path == null) return;
        org.eclipse.core.resources.IResource res = wsRoot.findMember(path);
        org.eclipse.core.runtime.IPath loc = res != null ? res.getLocation() : null;
        String value = loc != null ? loc.toOSString() : path.toOSString();
        if (!into.contains(value)) into.add(value);
    }

    private static void gitProvenance(Path projectRoot, CoverageManifest m) {
        m.gitRevision = runGit(projectRoot, "rev-parse", "HEAD");
        if (m.gitRevision == null) {
            m.gitRevision = "unversioned";
            m.dirtyFingerprint = "unversioned";
            return;
        }
        String status = runGit(projectRoot, "status", "--porcelain");
        if (status == null || status.isBlank()) {
            m.dirtyFingerprint = "clean";
        } else {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                m.dirtyFingerprint = "dirty-" + HexFormat.of()
                    .formatHex(md.digest(status.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
            } catch (Exception e) {
                m.dirtyFingerprint = "dirty-unhashed";
            }
        }
    }

    /** Union of all per-test segments = the artifact's ordinary exec data. */
    private static void mergeSegments(Path segmentsDir, Path execFile) throws IOException {
        org.jacoco.core.tools.ExecFileLoader loader = new org.jacoco.core.tools.ExecFileLoader();
        try (var files = Files.list(segmentsDir)) {
            for (Path seg : files.filter(p -> p.getFileName().toString().endsWith(".exec"))
                    .sorted().toList()) {
                loader.load(seg.toFile());
            }
        }
        loader.save(execFile.toFile(), false);
    }

    private static String runGit(Path dir, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        try {
            // Through the boundary: the five-second budget is now real. The
            // previous shape read the whole stream BEFORE waitFor, and
            // readAllBytes returns at end of stream — so the timeout could
            // never expire and a wedged git held this call indefinitely.
            HostProcessOutcome outcome = HostProcesses.system()
                .run(HostCommand.of(cmd).waitingAtMost(Duration.ofSeconds(5)));
            return outcome.succeeded() ? outcome.output().strip() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Stage 8 — import an EXTERNAL exec file (CI run) as a first-class
     * artifact: provenance recorded against the CURRENT tree, acceptance
     * gated by the class-id check at analysis time (mismatched bytes surface
     * as stale-bytes REFUSALS, exactly like a local stale artifact).
     */
    public String importArtifact(LoadedProject project, Path execSource, String evidenceKind)
            throws IOException {
        if (!Files.isRegularFile(execSource)) {
            throw new IOException("exec file not found: " + execSource);
        }
        String id = store.newArtifactId();
        Path dir = store.createArtifactDir(id);
        Files.copy(execSource, dir.resolve(CoverageStore.EXEC_FILE));
        CoverageManifest m = new CoverageManifest();
        m.artifactId = id;
        m.createdAt = Instant.now().toString();
        m.jacocoVersion = "0.8.15";
        m.jdkVersion = System.getProperty("java.version");
        m.environment = HostOS.description();
        m.projectKey = project.projectKey();
        m.projectRoot = project.projectRoot().toString();
        m.evidenceKind = evidenceKind == null || evidenceKind.isBlank() ? "imported" : evidenceKind;
        m.framework = "imported";
        collectRoots(project.javaProject(), m);
        gitProvenance(project.projectRoot(), m);
        m.runFinalized = true;
        m.completionStatus = "IMPORTED";
        store.writeManifest(id, m);
        return id;
    }

    private final Map<String, Long> cacheFingerprints = new java.util.HashMap<>();

    /**
     * Analyzed model for an artifact (cached); null when the artifact is
     * unknown. The cache is FRESHNESS-AWARE: a rebuild that touches the
     * class roots invalidates the entry — a stale-bytes verdict must never
     * be masked by a pre-rebuild analysis.
     */
    public synchronized CoverageModel model(String artifactId) throws IOException {
        CoverageManifest manifest = store.readManifest(artifactId).orElse(null);
        if (manifest == null) return null;
        java.util.OptionalLong fingerprint = rootsFingerprint(manifest);
        CoverageModel cached = modelCache.get(artifactId);
        Long cachedFp = cacheFingerprints.get(artifactId);
        // D5: an ABSENT fingerprint can match nothing. A root that could not be walked used to
        // contribute zero and leave the number looking unchanged, which reused the cache — the
        // one outcome this method's javadoc forbids.
        if (fingerprint.isPresent() && cached != null && cachedFp != null
                && cachedFp == fingerprint.getAsLong()) {
            return cached;
        }
        CoverageModel model = CoverageModel.analyze(store.execFile(artifactId), manifest);
        modelCache.put(artifactId, model);
        if (fingerprint.isPresent()) {
            cacheFingerprints.put(artifactId, fingerprint.getAsLong());
        } else {
            // And nothing is stored, so the NEXT call cannot match against a reading that was
            // never taken either.
            cacheFingerprints.remove(artifactId);
        }
        return model;
    }

    /**
     * Newest mtime across the class roots' class files — cheap rebuild signal, or EMPTY when
     * a root could not be walked.
     *
     * <p>D5 (Sprint 28e): this used to swallow the failure and carry on, so a root it could
     * not read contributed nothing and the fingerprint came out looking UNCHANGED. The caller
     * then reused its cached model — which is precisely what {@link #model(String)}'s own
     * javadoc forbids: <i>"a stale-bytes verdict must never be masked by a pre-rebuild
     * analysis."</i> The swallow did not merely lose information, it inverted a safety
     * property the class states one method away.</p>
     *
     * <p>Empty is not a number the caller can mistake for a reading, and it fails toward
     * DOING the work rather than skipping it.</p>
     */
    private static java.util.OptionalLong rootsFingerprint(CoverageManifest manifest) {
        long newest = 0;
        for (String root : manifest.classRoots) {
            Path rootPath = Path.of(root);
            if (!Files.isDirectory(rootPath)) continue;
            try (var walk = Files.walk(rootPath)) {
                newest = Math.max(newest, walk
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .mapToLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            // D5 (Sprint 28e), and the DIRECTION is what makes this one
                            // dangerous rather than merely imprecise. This is a STALENESS
                            // fingerprint reduced with max(): a class file that cannot be
                            // stat'd contributed the epoch, so it could never RAISE
                            // `newest` — a rebuilt-but-unreadable class made the artifact
                            // look FRESH and coverage was then served over stale bytes.
                            // Failing open in the one direction a staleness check must not.
                            //
                            // Unchecked, so the outer catch does what its own message
                            // already promises: re-analyze rather than trust a cache entry
                            // nothing could check.
                            throw new java.io.UncheckedIOException(e);
                        }
                    }).max().orElse(0));
            } catch (IOException | java.io.UncheckedIOException e) {
                log.warn("cannot fingerprint class root {} ({}) — re-analyzing rather than"
                    + " trusting a cache entry nothing could check", rootPath, e.getMessage());
                return java.util.OptionalLong.empty();
            }
        }
        return java.util.OptionalLong.of(newest);
    }

    public synchronized void evict(String artifactId) {
        modelCache.remove(artifactId);
    }
}
