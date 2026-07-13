package org.jawata.mcp.coverage;

import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.jawata.core.LoadedProject;
import org.jawata.mcp.execution.ForkedTestRunner;
import org.jawata.mcp.execution.RunnerClasspath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
        String artifactId = store.newArtifactId();
        Path dir = store.createArtifactDir(artifactId);
        Path agentJar = RunnerClasspath.toolJar("org.jacoco.agent-");
        spec.vmArgs.add(0, "-javaagent:" + agentJar + "=destfile="
            + dir.resolve(CoverageStore.EXEC_FILE) + ",dumponexit=true");
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
        m.environment = System.getProperty("os.name") + "/" + System.getProperty("os.arch");
        m.projectKey = project.projectKey();
        m.projectRoot = project.projectRoot().toString();
        m.framework = framework;
        m.evidenceKind = evidenceKind == null || evidenceKind.isBlank() ? "unit" : evidenceKind;
        m.selectClasses = new ArrayList<>(spec.selectClasses);
        m.selectMethods = new ArrayList<>(spec.selectMethods);
        m.selectPackages = new ArrayList<>(spec.selectPackages);

        collectRoots(project.javaProject(), m);
        gitProvenance(project.projectRoot(), m);

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

    private static String runGit(Path dir, String... args) {
        try {
            List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0) return null;
            return out.strip();
        } catch (Exception e) {
            return null;
        }
    }

    /** Analyzed model for an artifact (cached); null when the artifact is unknown. */
    public synchronized CoverageModel model(String artifactId) throws IOException {
        CoverageModel cached = modelCache.get(artifactId);
        if (cached != null) return cached;
        CoverageManifest manifest = store.readManifest(artifactId).orElse(null);
        if (manifest == null) return null;
        CoverageModel model = CoverageModel.analyze(store.execFile(artifactId), manifest);
        modelCache.put(artifactId, model);
        return model;
    }

    public synchronized void evict(String artifactId) {
        modelCache.remove(artifactId);
    }
}
