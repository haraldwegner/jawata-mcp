package org.jawata.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Sprint 24 (D5/D16) — what a runtime session leaves behind: replay captures,
 * heap dumps, flight recordings, incident bundles. Mirrors Sprint 23's
 * {@code CoverageStore}, generalized over the kind of artifact.
 *
 * <p>Two properties are the point. <b>Provenance</b>: every artifact carries a
 * manifest saying what produced it, from which session, against which target,
 * and when — an artifact whose origin is unknown is evidence of nothing.
 * <b>Expiry and explicit delete</b>: heap dumps are gigabytes; a store that only
 * grows is a disk-full incident with a delay fuse.</p>
 */
public final class RuntimeArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(RuntimeArtifactStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String MANIFEST_FILE = "manifest.json";

    /** A week: long enough to finish an investigation, short enough not to fill a disk. */
    public static final long DEFAULT_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private final Path root;

    public RuntimeArtifactStore() {
        this(resolveRoot());
    }

    public RuntimeArtifactStore(Path root) {
        this.root = root;
    }

    static Path resolveRoot() {
        String explicit = System.getProperty("jawata.runtime.dir");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        String wsRoot = System.getProperty("jawata.workspace.root");
        if (wsRoot != null && !wsRoot.isBlank()) {
            return Path.of(wsRoot, ".jawata", "runtime");
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "jawata-runtime");
    }

    /** {@code <kind>-<millis>-<rand>} — sorts newest-last by name, so listing reverses it. */
    public String newArtifactId(String kind) {
        return kind + "-" + System.currentTimeMillis() + "-"
            + UUID.randomUUID().toString().substring(0, 8);
    }

    public Path createArtifactDir(String artifactId) throws IOException {
        Path dir = root.resolve(artifactId);
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * Store an artifact: its directory, its files (already written there by the
     * caller), and the manifest that says where it came from.
     *
     * <p>The manifest is stamped with {@code createdMillis} and {@code expiresMillis}
     * if the caller did not set them. Provenance the caller must supply — we will not
     * invent it.</p>
     */
    public void writeManifest(String artifactId, Map<String, Object> provenance) throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>(provenance);
        manifest.put("artifactId", artifactId);
        manifest.putIfAbsent("createdMillis", System.currentTimeMillis());
        manifest.putIfAbsent("expiresMillis",
            ((Number) manifest.get("createdMillis")).longValue() + DEFAULT_TTL_MILLIS);
        Path file = root.resolve(artifactId).resolve(MANIFEST_FILE);
        Files.createDirectories(file.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), manifest);
    }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> readManifest(String artifactId) {
        Path file = root.resolve(artifactId).resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(file.toFile(), Map.class));
        } catch (IOException e) {
            log.warn("unreadable runtime manifest {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /** All artifact ids, newest first. Only manifested dirs count — a half-written one is not an artifact. */
    public List<String> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory)
                .filter(d -> Files.isRegularFile(d.resolve(MANIFEST_FILE)))
                .sorted(Comparator.comparing((Path d) -> d.getFileName().toString()).reversed())
                .map(d -> d.getFileName().toString())
                .toList();
        } catch (IOException e) {
            log.warn("cannot list runtime store {}: {}", root, e.getMessage());
            return List.of();
        }
    }

    /** Artifacts with their manifests and on-disk sizes — what a caller needs to decide what to delete. */
    public List<Map<String, Object>> describeAll() {
        List<Map<String, Object>> described = new ArrayList<>();
        for (String id : list()) {
            Map<String, Object> row = new LinkedHashMap<>(readManifest(id).orElse(Map.of()));
            row.put("bytes", sizeOf(id));
            row.put("expired", isExpired(id));
            described.add(row);
        }
        return described;
    }

    public boolean exists(String artifactId) {
        return Files.isRegularFile(root.resolve(artifactId).resolve(MANIFEST_FILE));
    }

    public boolean isExpired(String artifactId) {
        return readManifest(artifactId)
            .map(m -> m.get("expiresMillis"))
            .filter(Number.class::isInstance)
            .map(v -> ((Number) v).longValue() < System.currentTimeMillis())
            .orElse(false);
    }

    public long sizeOf(String artifactId) {
        Path dir = root.resolve(artifactId);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Explicit delete — false when there was nothing to delete. */
    public boolean delete(String artifactId) {
        Path dir = root.resolve(artifactId);
        if (!Files.isDirectory(dir)) {
            return false;
        }
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(paths::add);
        } catch (IOException e) {
            log.warn("cannot walk runtime artifact {}: {}", dir, e.getMessage());
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

    /** Delete every artifact past its expiry; returns the ids actually removed. */
    public List<String> pruneExpired() {
        List<String> pruned = new ArrayList<>();
        for (String id : list()) {
            if (isExpired(id) && delete(id)) {
                pruned.add(id);
            }
        }
        return pruned;
    }

    public Path root() {
        return root;
    }
}
