package org.jawata.core.project;

import org.jawata.core.resolve.BundleFacts;
import org.jawata.core.resolve.PlatformResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Sprint 23 (D7), rebuilt at Stage 12.2 — the EXTERNAL bundle pools: the jars
 * a PDE requirement can resolve to when no workspace project provides it.
 *
 * <p>Pool directories, in preference order:</p>
 * <ol>
 *   <li>{@code jawata.bundle.pools} system property (path-separated list) —
 *       the explicit, materialized-target-platform case;</li>
 *   <li>the shared p2 pool ({@code ~/.p2/pool/plugins}), when present;</li>
 *   <li>the Tycho p2 cache ({@code ~/.m2/repository/p2/osgi/bundle}) — the
 *       C10 ruling's platform source: what the user's own Tycho build already
 *       materialised, in its {@code name/version/jar} nesting;</li>
 *   <li>the running server's own framework bundles
 *       ({@code jawata.dist.root/bundles}) and the dist root — the
 *       self-hosting fallback (slf4j-api and org.eclipse.osgi ride the BOOT
 *       classpath beside jawata.jar, not bundles/).</li>
 * </ol>
 *
 * <p>Each jar's manifest is read ONCE into full {@link BundleFacts} — the
 * resolver needs {@code Fragment-Host} and {@code Eclipse-PlatformFilter},
 * not just names — and handed to the {@link PlatformResolver} as
 * {@link PlatformResolver.PoolBundle}s via {@link #poolBundles()}; the
 * resolver owns arbitration (one selection pass, R7). The pool keeps ONE
 * derived query map — {@link #bundleJar}, the JUnit-container lookup, winner
 * per symbolic name — and no package map at all: the old second map is the
 * one that could disagree with the first, and the resolver's selection is
 * the only package view now (the strongest form of R7).</p>
 *
 * <p>CACHE DISCIPLINE (R8): the old cache keyed on the pool root's mtime,
 * which is blind twice in the nested layout — a new {@code name/version/}
 * bumps {@code name/}, not the root, and an in-place jar replacement bumps
 * nothing. The cache now fingerprints EVERY jar (size, mtime) on every
 * {@link #index} call: the directory walk is cheap, manifests are re-read
 * only for new or changed jars, and removed jars drop out. A cleared cache
 * ({@link #clearCaches()}, from {@code refresh_workspace}) re-reads
 * everything.</p>
 */
public final class ExternalBundlePool {

    private static final Logger log = LoggerFactory.getLogger(ExternalBundlePool.class);

    /** Per-pool-root cache of per-jar facts, revalidated by (size, mtime) on every index. */
    private static final ConcurrentHashMap<String, Map<String, CachedJar>> DIR_CACHE =
        new ConcurrentHashMap<>();

    private record CachedJar(long size, long mtime, Optional<PlatformResolver.PoolBundle> bundle) { }

    private final List<PlatformResolver.PoolBundle> bundles = new ArrayList<>();
    private final Map<String, PlatformResolver.PoolBundle> winnerByName = new HashMap<>();

    private ExternalBundlePool() { }

    /** Index the given pool directories; arbitration is newest-version across all of them. */
    public static ExternalBundlePool index(List<Path> poolDirs) {
        ExternalBundlePool pool = new ExternalBundlePool();
        for (Path dir : poolDirs) {
            pool.bundles.addAll(indexDir(dir));
        }
        // The ONE selection pass (R7), mirrored from the resolver: winner per
        // symbolic name (newest), fragments never providers in their own
        // right, exported packages derived from the winners only.
        for (PlatformResolver.PoolBundle candidate : pool.bundles) {
            if (candidate.facts().fragmentHost().isPresent()) {
                continue;
            }
            pool.winnerByName.merge(candidate.facts().symbolicName(), candidate,
                ExternalBundlePool::newerOf);
        }
        return pool;
    }

    /** The production pool-directory chain (see class Javadoc). */
    public static List<Path> defaultPoolDirs() {
        List<Path> dirs = new ArrayList<>();
        String explicit = System.getProperty("jawata.bundle.pools");
        if (explicit != null && !explicit.isBlank()) {
            for (String part : explicit.split(java.io.File.pathSeparator)) {
                if (!part.isBlank()) dirs.add(Path.of(part));
            }
        }
        // The MACHINE pools — what this user's home directory happens to hold.
        // jawata.bundle.pools.machine=off excludes them: the test suites set it
        // so goldens and fixtures never depend on the machine's own caches
        // (a golden capturing ~/.m2 content fails on every other machine).
        if (!"off".equals(System.getProperty("jawata.bundle.pools.machine"))) {
            String home = System.getProperty("user.home");
            dirs.add(Path.of(home, ".p2", "pool", "plugins"));
            // The Tycho p2 cache (C10's ruling: no Eclipse install — the pom's
            // own build already downloaded the target platform here).
            dirs.add(Path.of(home, ".m2", "repository", "p2", "osgi", "bundle"));
        }
        String distRoot = System.getProperty("jawata.dist.root");
        if (distRoot != null && !distRoot.isBlank()) {
            dirs.add(Path.of(distRoot, "bundles"));
            dirs.add(Path.of(distRoot));
        }
        return dirs.stream().filter(Files::isDirectory).toList();
    }

    /** Every indexed jar with its full facts — the resolver's pool input. */
    public List<PlatformResolver.PoolBundle> poolBundles() {
        return List.copyOf(bundles);
    }

    public Optional<Path> bundleJar(String symbolicName) {
        return Optional.ofNullable(winnerByName.get(symbolicName))
            .map(PlatformResolver.PoolBundle::jar);
    }

    /** The facts read from one indexed jar, by its path — the applier's jar-in-jar honesty check (12.3). */
    public Optional<BundleFacts> factsOf(Path jar) {
        return bundles.stream()
            .filter(b -> b.jar().equals(jar))
            .map(PlatformResolver.PoolBundle::facts)
            .findFirst();
    }

    public boolean isEmpty() {
        return bundles.isEmpty();
    }

    /** Drop every cached per-jar fact — {@code refresh_workspace}'s reconcile contract. */
    public static void clearCaches() {
        DIR_CACHE.clear();
    }

    // ------------------------------------------------------------- indexing

    private static List<PlatformResolver.PoolBundle> indexDir(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        String key = dir.toAbsolutePath().toString();
        // Sorted for a deterministic bundle order run to run (R20).
        TreeMap<String, Path> jars = new TreeMap<>();
        try {
            collectJars(dir, jars);
        } catch (IOException | java.io.UncheckedIOException e) {
            // UncheckedIOException too (Sprint 28, C1 audit round 3): Files.list
            // defers the directory read to the terminal operation and wraps a
            // failure there unchecked — a pool directory that becomes
            // unreadable degrades to "nothing resolved from it", never an
            // aborted load.
            log.warn("bundle pool: cannot list {}: {}", dir, e.getMessage());
            return List.of();
        }
        Map<String, CachedJar> cached = DIR_CACHE.getOrDefault(key, Map.of());
        Map<String, CachedJar> fresh = new LinkedHashMap<>();
        List<PlatformResolver.PoolBundle> out = new ArrayList<>();
        int reread = 0;
        for (Map.Entry<String, Path> e : jars.entrySet()) {
            long size;
            long mtime;
            try {
                size = Files.size(e.getValue());
                mtime = Files.getLastModifiedTime(e.getValue()).toMillis();
            } catch (IOException io) {
                continue; // vanished between walk and stat
            }
            CachedJar hit = cached.get(e.getKey());
            CachedJar entry;
            if (hit != null && hit.size() == size && hit.mtime() == mtime) {
                entry = hit;
            } else {
                entry = new CachedJar(size, mtime, readJar(e.getValue()));
                reread++;
            }
            fresh.put(e.getKey(), entry);
            entry.bundle().ifPresent(out::add);
        }
        DIR_CACHE.put(key, Map.copyOf(fresh));
        if (reread > 0) {
            log.debug("bundle pool indexed {}: {} jars, {} (re)read", dir, jars.size(), reread);
        }
        return out;
    }

    /**
     * Collect candidate jars: the flat layout ({@code dir/*.jar}) and the
     * Tycho p2 nesting ({@code dir/name/version/*.jar}) — exactly those two
     * shapes, no general recursion (the ruled bound: a pool is a pool, not a
     * filesystem crawl).
     */
    private static void collectJars(Path dir, Map<String, Path> jars) throws IOException {
        try (Stream<Path> level1 = Files.list(dir)) {
            for (Path p1 : level1.toList()) {
                if (isJar(p1)) {
                    jars.put(p1.toAbsolutePath().toString(), p1);
                } else if (Files.isDirectory(p1)) {
                    try (Stream<Path> level2 = Files.list(p1)) {
                        for (Path p2 : level2.filter(Files::isDirectory).toList()) {
                            try (Stream<Path> level3 = Files.list(p2)) {
                                level3.filter(ExternalBundlePool::isJar).forEach(j ->
                                    jars.put(j.toAbsolutePath().toString(), j));
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean isJar(Path p) {
        return Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar");
    }

    private static Optional<PlatformResolver.PoolBundle> readJar(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest manifest = jf.getManifest();
            if (manifest == null) return Optional.empty();
            return BundleFacts.of(manifest)
                .map(facts -> new PlatformResolver.PoolBundle(facts, jar));
        } catch (IOException e) {
            log.debug("bundle pool: unreadable jar {} ({})", jar, e.getMessage());
            return Optional.empty();
        }
    }

    private static PlatformResolver.PoolBundle newerOf(PlatformResolver.PoolBundle a,
                                                       PlatformResolver.PoolBundle b) {
        return compareVersions(a.facts().version().orElse("0"),
            b.facts().version().orElse("0")) >= 0 ? a : b;
    }

    /** OSGi-ish version compare: numeric segments numerically, rest lexically. */
    static int compareVersions(String a, String b) {
        String[] as = a.split("\\.", 4);
        String[] bs = b.split("\\.", 4);
        for (int i = 0; i < Math.max(as.length, bs.length); i++) {
            String sa = i < as.length ? as[i] : "0";
            String sb = i < bs.length ? bs[i] : "0";
            int cmp;
            try {
                cmp = Integer.compare(Integer.parseInt(sa), Integer.parseInt(sb));
            } catch (NumberFormatException e) {
                cmp = sa.compareTo(sb);
            }
            if (cmp != 0) return cmp;
        }
        return 0;
    }
}
