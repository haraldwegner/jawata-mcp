package org.jawata.core.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * THE RESOLVED CLASSPATH, REMEMBERED ACROSS RESTARTS.
 *
 * <p>Resolving one project's Maven classpath is a {@code mvn} shell-out costing
 * seconds. The in-memory map that used to hold the answer died with the process,
 * so every restart re-resolved every project — and the residents all restart
 * together when a release lands, each re-importing its whole workspace at the
 * same instant. Measured 2026-09-01, seventeen minutes after v4.0.0 published:
 * three residents at 189%, 211% and 133% CPU plus children, roughly ten of
 * twenty cores, load average 17, and a machine its owner described as stuck for
 * minutes. At 290 repositories that is 290 Maven runs nobody asked for.</p>
 *
 * <h2>Why persisting is safe here, and would not be for most caches</h2>
 *
 * <p>The key is a SHA-256 of the content of every {@code pom.xml} in the tree.
 * It is not a path, not a timestamp and not a version — so a build file changing
 * by so much as a byte produces a different key and the old entry is simply
 * never asked for again. There is no invalidation logic to get wrong, which is
 * the property that makes this cache worth writing to disk at all.</p>
 *
 * <h2>The half the hash does NOT cover, which is why {@link #get} verifies</h2>
 *
 * <p>The hash covers the POMs. It says nothing about the local repository the
 * resolved jars live in. Purging {@code ~/.m2}, switching to a different local
 * repository, or a dependency being evicted all leave the poms untouched and the
 * key valid while the jar paths rot. In memory that could not bite: the cache
 * never outlived the process that filled it. On disk it can, so every hit is
 * verified — if any jar named by an entry has gone, the entry is a MISS and is
 * deleted, and the caller resolves fresh.</p>
 *
 * <p>That check is the difference between a persistent cache and a persistent
 * bug: a classpath silently missing jars is exactly the failure
 * {@code ProjectImporter} refuses to produce, because every compile, type and
 * search answer built on it would be wrong while looking perfectly healthy.</p>
 *
 * <h2>Location</h2>
 *
 * <p>{@code jawata.classpath.cache.dir} › {@code $XDG_CACHE_HOME/jawata/classpath}
 * › {@code ~/.cache/jawata/classpath}. CACHE, not data: every entry is
 * regenerable by definition, so deleting the directory costs time and never
 * information. It is deliberately not the experience store's data directory —
 * that holds things nothing can rebuild.</p>
 */
final class ClasspathCache {

    private static final Logger log = LoggerFactory.getLogger(ClasspathCache.class);

    /**
     * The in-memory tier, now a working set rather than the whole cache.
     *
     * <p>It was capped at 64 while it was the ONLY tier, which on a machine
     * holding 290 repositories meant the 65th project evicted the first — the
     * cache was already too small before any restart entered the picture. With
     * disk behind it a memory miss costs one small file read instead of a Maven
     * run, so the cap stops being a cliff; it is raised anyway because the
     * entries are lists of strings and the ceiling was never the expensive
     * resource.</p>
     */
    private static final ConcurrentHashMap<String, List<String>> MEMORY = new ConcurrentHashMap<>();
    private static final int MEMORY_MAX = 512;

    private ClasspathCache() {
    }

    /**
     * The cached classpath for {@code key}, or null when there is none to serve.
     *
     * <p>A disk hit whose jars no longer all exist is treated as a MISS and the
     * entry removed — see the class javadoc: the key attests to the build files,
     * never to the repository the jars came from.</p>
     *
     * @param key the pom-tree content hash, or null when the tree could not be
     *            hashed — a null key can never hit, and never throws
     * @return the remembered jar paths, or null for a miss
     */
    static List<String> get(String key) {
        if (key == null) {
            return null;
        }
        List<String> hot = MEMORY.get(key);
        if (hot != null) {
            return new ArrayList<>(hot);
        }
        Path file = entryFile(key);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            List<String> jars = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .toList();
            for (String jar : jars) {
                if (!Files.exists(Path.of(jar))) {
                    log.debug("Classpath cache: entry {} names a jar that is gone ({}) — discarding",
                        key, jar);
                    deleteQuietly(file);
                    return null;
                }
            }
            remember(key, jars);
            log.debug("Classpath cache: served {} entries from disk", jars.size());
            return new ArrayList<>(jars);
        } catch (IOException | RuntimeException e) {
            // An unreadable entry is a miss, never a failure: the caller can
            // always resolve fresh, and a cache that can break a load is worse
            // than no cache.
            log.debug("Classpath cache: could not read entry {} ({}) — resolving fresh",
                key, e.toString());
            deleteQuietly(file);
            return null;
        }
    }

    /**
     * Remember {@code jars} under {@code key}, in memory and on disk.
     *
     * <p>Never throws. A cache that cannot be written is a slower product, not a
     * broken one, so every failure here is logged at debug and swallowed.</p>
     *
     * @param key  the pom-tree content hash; null is ignored
     * @param jars the resolved absolute jar paths; an EMPTY list is a legitimate
     *             answer (a pom with no dependencies) and is cached as one
     */
    static void put(String key, List<String> jars) {
        if (key == null || jars == null) {
            return;
        }
        remember(key, jars);
        Path file = entryFile(key);
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            // Write-then-move: a reader must never see half an entry, and two
            // residents starting together will both be writing this directory.
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp"
                + ProcessHandle.current().pid());
            Files.write(tmp, String.join("\n", jars).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            log.debug("Classpath cache: could not write entry {} ({})", key, e.toString());
        }
    }

    private static void remember(String key, List<String> jars) {
        if (MEMORY.size() < MEMORY_MAX) {
            MEMORY.put(key, List.copyOf(jars));
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Classpath cache: could not delete stale entry {}", file);
        }
    }

    /** The file backing one key, or null when no cache directory can be named. */
    private static Path entryFile(String key) {
        Path dir = cacheDir();
        return dir == null ? null : dir.resolve(key + ".classpath");
    }

    /**
     * {@code jawata.classpath.cache.dir} › {@code $XDG_CACHE_HOME/jawata/classpath}
     * › {@code ~/.cache/jawata/classpath}, or null when even a home directory is
     * unknown — in which case this degrades to the in-memory tier alone, which is
     * exactly the behaviour that shipped before.
     */
    static Path cacheDir() {
        String override = System.getProperty("jawata.classpath.cache.dir");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, "jawata", "classpath");
        }
        String home = System.getProperty("user.home", "");
        if (home.isBlank()) {
            return null;
        }
        return Path.of(home, ".cache", "jawata", "classpath");
    }

    /** Drop the in-memory tier. Tests only — the disk tier is addressed by its directory. */
    static void clearMemory() {
        MEMORY.clear();
    }
}
