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
import java.util.Map;
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
            // D5 (Sprint 28e): NOT isRegularFile — see manifestMissing below.
            return dirs.filter(Files::isDirectory)
                .filter(d -> manifestMissing(d) != Boolean.TRUE)
                .sorted(Comparator.comparing((Path d) -> d.getFileName().toString()).reversed())
                .map(d -> d.getFileName().toString())
                .toList();
        } catch (IOException e) {
            log.warn("cannot list coverage store {}: {}", root, e.getMessage());
            return List.of();
        }
    }

    /**
     * Is this directory's manifest MISSING ({@code TRUE}), genuinely present
     * ({@code FALSE}), or could we not tell ({@code null})?
     *
     * <p>D5 (Sprint 28e). {@code Files.isRegularFile} answers false when a file is absent,
     * is not a regular file, OR CANNOT BE DETERMINED — its own javadoc says so — so the
     * filter it fed read an unreadable artifact as having no manifest and dropped it from
     * {@code list()}. The artifact then does not exist as far as any caller is concerned:
     * {@link #latest()} skips it and it can never be described or deleted by name.</p>
     *
     * <p>This is the SECOND copy of a cure written in {@code RuntimeArtifactStore}, which is
     * why it is applied to both rather than to the one that was found. <b>It said "the two
     * are byte-identical stores with byte-identical list() methods", and that was false when
     * written</b> — the stores share ten members and differ elsewhere, and the two copies of
     * THIS helper already differ in their javadoc and their log text. A claim of sameness,
     * inside the copy that disproves it. The architect watch found it; the honest reading is
     * that a copy is the beginning of drift and this one had drifted on arrival.</p>
     *
     * <p>The proposal on the table is one {@code ArtifactDir} value type owning
     * {@code manifestState()}, {@code readManifest()}, {@code exists()}, {@code sizeOf()} and
     * {@code delete()}, held by both stores — a static helper cannot serve, because
     * {@code MANIFEST_FILE} is per-store, so the fact needs an instance. Raised at C8.</p>
     */
    private static Boolean manifestMissing(Path dir) {
        Path manifest = dir.resolve(MANIFEST_FILE);
        try {
            return !Files.readAttributes(manifest,
                java.nio.file.attribute.BasicFileAttributes.class).isRegularFile();
        } catch (java.nio.file.NoSuchFileException e) {
            return Boolean.TRUE;    // genuinely absent — not an artifact
        } catch (IOException e) {
            log.warn("cannot tell whether {} has a manifest ({}) — listing it rather than"
                + " hiding something that may be a real artifact", dir, e.getMessage());
            return null;            // unreadable — NOT evidence of absence
        }
    }

    public Optional<String> latest() {
        List<String> all = list();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * Is this artifact here? {@code false} means a CONFIRMED absence.
     *
     * <p>D5 (Sprint 28e) — the same change and the same reason as
     * {@code RuntimeArtifactStore#exists}: this read {@code Files.isRegularFile}, so it
     * answered "no such artifact" about one whose directory could not be read, while
     * {@link #delete(String)}'s javadoc names it as the way to tell absent from survived.</p>
     */
    public boolean exists(String artifactId) {
        return manifestMissing(root.resolve(artifactId)) != Boolean.TRUE;
    }

    /**
     * Explicit delete. {@code true} means the artifact is GONE.
     *
     * <p>D5 (Sprint 28e) — this javadoc used to read <i>"returns false when the artifact does
     * not exist"</i>, which was false about the code beneath it: {@code false} already meant
     * absent, OR the tree could not be walked, OR a file in it could not be deleted
     * ({@code ok = false}). Only the first is "nothing to delete"; the others mean it is
     * still there.</p>
     *
     * <p>Stated the way round that holds for every branch: {@code false} means <b>not
     * gone</b>. The same wording and the same residual as {@code RuntimeArtifactStore#delete}
     * — these two stores are byte-identical here, and a correction applied to one of them
     * would have left the other saying something untrue.</p>
     */
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

    // ---------------- Stage 8: baselines · threshold policy · unstable lines

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(String fileName) {
        Path file = root.resolve(fileName);
        if (!Files.isRegularFile(file)) return new java.util.LinkedHashMap<>();
        try {
            return JSON.readValue(file.toFile(), Map.class);
        } catch (IOException e) {
            log.warn("unreadable {}: {}", file, e.getMessage());
            return new java.util.LinkedHashMap<>();
        }
    }

    private void writeJsonMap(String fileName, Map<String, Object> value) throws IOException {
        Files.createDirectories(root);
        JSON.writerWithDefaultPrettyPrinter().writeValue(root.resolve(fileName).toFile(), value);
    }

    /** Name a stored artifact as a baseline. */
    public void setBaseline(String name, String artifactId) throws IOException {
        Map<String, Object> baselines = readJsonMap("baselines.json");
        baselines.put(name, artifactId);
        writeJsonMap("baselines.json", baselines);
    }

    public Optional<String> baseline(String name) {
        Object id = readJsonMap("baselines.json").get(name);
        return id == null ? Optional.empty() : Optional.of(String.valueOf(id));
    }

    /**
     * The threshold policy: explicit AND VERSIONED — every change bumps the
     * version so a response can prove WHICH policy judged it. Waivers are
     * part of the policy and always surface in responses.
     */
    public Map<String, Object> readPolicy() {
        return readJsonMap("threshold-policy.json");
    }

    public Map<String, Object> setPolicy(double lineThresholdPercent,
            List<Map<String, String>> waivers) throws IOException {
        Map<String, Object> policy = readPolicy();
        int version = policy.get("version") instanceof Number n ? n.intValue() : 0;
        policy.put("version", version + 1);
        policy.put("lineThresholdPercent", lineThresholdPercent);
        policy.put("waivers", waivers == null ? List.of() : waivers);
        writeJsonMap("threshold-policy.json", policy);
        return policy;
    }

    /** Unstable-line registry: fqn → lines proven non-deterministic. */
    @SuppressWarnings("unchecked")
    public Map<String, List<Integer>> readUnstable() {
        Map<String, Object> raw = readJsonMap("unstable.json");
        Map<String, List<Integer>> out = new java.util.LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v instanceof List<?> l) {
                List<Integer> lines = new ArrayList<>();
                l.forEach(x -> lines.add(((Number) x).intValue()));
                out.put(k, lines);
            }
        });
        return out;
    }

    public void addUnstable(Map<String, List<Integer>> more) throws IOException {
        Map<String, List<Integer>> current = readUnstable();
        more.forEach((fqn, lines) -> {
            List<Integer> merged = new ArrayList<>(current.getOrDefault(fqn, List.of()));
            for (Integer l : lines) {
                if (!merged.contains(l)) merged.add(l);
            }
            current.put(fqn, merged);
        });
        Map<String, Object> raw = new java.util.LinkedHashMap<>();
        current.forEach(raw::put);
        writeJsonMap("unstable.json", raw);
    }
}
