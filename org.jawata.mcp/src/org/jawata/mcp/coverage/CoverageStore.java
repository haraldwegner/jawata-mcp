package org.jawata.mcp.coverage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Sprint 23 (D3) — coverage artifacts on disk: one directory per artifact
 * ({@code jacoco.exec} + {@code manifest.json}) under the workspace state
 * area, with an EXPLICIT delete action (never silent eviction of evidence).
 *
 * <p>Root resolution: {@code jawata.coverage.dir} property (tests, embedders)
 * → {@code jawata.workspace.root}/.jawata/coverage (production, the stable
 * root the boot publishes) → a tmpdir fallback.</p>
 */
public final class CoverageStore {

    private static final Logger log = LoggerFactory.getLogger(CoverageStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String EXEC_FILE = "jacoco.exec";
    public static final String MANIFEST_FILE = "manifest.json";

    private final Path root;

    public CoverageStore() {
        this(resolveRoot());
    }

    public CoverageStore(Path root) {
        this.root = root;
    }

    static Path resolveRoot() {
        String explicit = System.getProperty("jawata.coverage.dir");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        String wsRoot = System.getProperty("jawata.workspace.root");
        if (wsRoot != null && !wsRoot.isBlank()) {
            return Path.of(wsRoot, ".jawata", "coverage");
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "jawata-coverage");
    }

    /** Create a fresh artifact directory; the id doubles as its dir name. */
    public Path createArtifactDir(String artifactId) throws IOException {
        Path dir = root.resolve(artifactId);
        Files.createDirectories(dir);
        return dir;
    }

    public String newArtifactId() {
        return "cov-" + System.currentTimeMillis() + "-"
            + UUID.randomUUID().toString().substring(0, 8);
    }

    public Path execFile(String artifactId) {
        return root.resolve(artifactId).resolve(EXEC_FILE);
    }

    public void writeManifest(String artifactId, CoverageManifest manifest) throws IOException {
        Path file = root.resolve(artifactId).resolve(MANIFEST_FILE);
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), manifest);
    }

    public Optional<CoverageManifest> readManifest(String artifactId) {
        Path file = root.resolve(artifactId).resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(JSON.readValue(file.toFile(), CoverageManifest.class));
        } catch (IOException e) {
            log.warn("unreadable coverage manifest {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /** All artifact ids, newest first. */
    public List<String> list() {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory)
                .filter(d -> Files.isRegularFile(d.resolve(MANIFEST_FILE)))
                .sorted(Comparator.comparing((Path d) -> d.getFileName().toString()).reversed())
                .map(d -> d.getFileName().toString())
                .toList();
        } catch (IOException e) {
            log.warn("cannot list coverage store {}: {}", root, e.getMessage());
            return List.of();
        }
    }

    public Optional<String> latest() {
        List<String> all = list();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    public boolean exists(String artifactId) {
        return Files.isRegularFile(root.resolve(artifactId).resolve(MANIFEST_FILE));
    }

    /** Explicit delete — returns false when the artifact does not exist. */
    public boolean delete(String artifactId) {
        Path dir = root.resolve(artifactId);
        if (!Files.isDirectory(dir)) return false;
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(paths::add);
        } catch (IOException e) {
            log.warn("cannot walk coverage artifact {}: {}", dir, e.getMessage());
            return false;
        }
        boolean ok = true;
        for (Path p : paths) {
            try {
                Files.delete(p);
            } catch (IOException e) {
                ok = false;
                log.warn("cannot delete {}: {}", p, e.getMessage());
            }
        }
        return ok;
    }

    public Path root() {
        return root;
    }
}
