package org.goja.core.workspace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sprint 21d (item A) — the strict-disk-sync scan core: per call, detect the source
 * files an EXTERNAL editor (agent Edit tool, git, another editor) changed, added or
 * deleted since the last scan, by evidence — mtime/size fast path, content hash for
 * files inside the filesystem-timestamp granularity window.
 *
 * <p>Deliberately JDT-free: this class only answers "what changed on disk?".
 * Reconciliation ({@code IFile.refreshLocal} + build) is layered on top and wired at
 * the tool-dispatch seam, so every tool call answers the CURRENT tree.
 *
 * <p><b>No configuration switch of any kind</b> — sync-off is a defect, and correctness
 * is not configurable. The only skip is the one earned per call: no edit detected →
 * empty result → no reconcile work. (Distinct from {@link WorkspaceFileWatcher}, which
 * tracks {@code workspace.json} project MEMBERSHIP — this guard tracks the projects'
 * source CONTENT; the two compose and do not overlap.)
 *
 * <p>Thread-safety: {@link #scan(Collection)} is {@code synchronized} — the MCP HTTP
 * transport dispatches tool calls on a cached thread pool, so concurrent calls must
 * not race the snapshot.
 */
public final class DiskSyncGuard {

    /** One scan's outcome. {@code hashedCount} = content hashes computed (cost telemetry). */
    public record ScanResult(List<Path> changed, List<Path> added, List<Path> deleted,
                             long durationNanos, int hashedCount) {
        public boolean isEmpty() {
            return changed.isEmpty() && added.isEmpty() && deleted.isEmpty();
        }
    }

    private record Sig(long mtimeMillis, long size) {}

    /**
     * Filesystem timestamp granularity window: a file whose mtime is at or after
     * (lastScan − this) can hide a same-size edit inside one timestamp tick — those
     * files are hash-verified; older files are trusted by signature alone. 2 s covers
     * coarse filesystems (FAT); the rule errs toward hashing more, never less.
     */
    private static final long FS_GRANULARITY_MILLIS = 2_000;

    /** Build-output / VCS trees are never source-scan territory (backstop, not config). */
    private static final Set<String> SKIPPED_DIR_NAMES = Set.of("target", "bin", "build", "node_modules");

    private final Map<Path, Sig> known = new HashMap<>();
    /** Content hashes for files inside the granularity window; evicted when they age out. */
    private final Map<Path, String> youngHashes = new HashMap<>();
    private boolean primed = false;
    private long lastScanStartMillis = -1;

    /**
     * Compare the {@code .java} files under {@code roots} against the last scan.
     * The FIRST scan primes the baseline and reports nothing (the JDT model was just
     * loaded from this same disk state). Files under roots no longer passed are
     * dropped silently — the workspace no longer owns them, they are not edits.
     */
    public synchronized ScanResult scan(Collection<Path> roots) {
        long startNanos = System.nanoTime();
        long scanStartMillis = System.currentTimeMillis();

        List<Path> normRoots = new ArrayList<>(roots.size());
        for (Path r : roots) {
            normRoots.add(r.toAbsolutePath().normalize());
        }
        known.keySet().removeIf(p -> normRoots.stream().noneMatch(p::startsWith));
        youngHashes.keySet().removeIf(p -> normRoots.stream().noneMatch(p::startsWith));

        Map<Path, Sig> current = collect(normRoots);

        List<Path> changed = new ArrayList<>();
        List<Path> added = new ArrayList<>();
        List<Path> deleted = new ArrayList<>();
        int hashed = 0;

        long youngThreshold = (primed ? lastScanStartMillis : scanStartMillis) - FS_GRANULARITY_MILLIS;

        for (Map.Entry<Path, Sig> e : current.entrySet()) {
            Path file = e.getKey();
            Sig sig = e.getValue();
            Sig prev = known.get(file);
            boolean young = sig.mtimeMillis() >= youngThreshold;

            if (prev == null) {
                if (primed) {
                    added.add(file);
                }
                if (young) {
                    youngHashes.put(file, sha256(file));
                    hashed++;
                }
            } else if (!prev.equals(sig)) {
                changed.add(file);
                if (young) {
                    youngHashes.put(file, sha256(file));
                    hashed++;
                } else {
                    youngHashes.remove(file);
                }
            } else if (young) {
                // Same signature inside the granularity window — the one place a
                // same-size edit can hide; the hash is the tie-breaker.
                String h = sha256(file);
                hashed++;
                String prior = youngHashes.put(file, h);
                if (primed && prior != null && !prior.equals(h)) {
                    changed.add(file);
                }
            } else {
                youngHashes.remove(file);      // aged out — bound the cache
            }
        }
        if (primed) {
            for (Path p : known.keySet()) {
                if (!current.containsKey(p)) {
                    deleted.add(p);
                }
            }
        }

        known.clear();
        known.putAll(current);
        primed = true;
        lastScanStartMillis = scanStartMillis;

        return new ScanResult(List.copyOf(changed), List.copyOf(added), List.copyOf(deleted),
            System.nanoTime() - startNanos, hashed);
    }

    private static Map<Path, Sig> collect(List<Path> roots) {
        Map<Path, Sig> out = new HashMap<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;                       // vanished root: membership concern, not an edit
            }
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        if (name.startsWith(".") || SKIPPED_DIR_NAMES.contains(name)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".java")) {
                            out.put(file.toAbsolutePath().normalize(),
                                new Sig(attrs.lastModifiedTime().toMillis(), attrs.size()));
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;   // a vanishing file is next scan's delete
                    }
                });
            } catch (IOException e) {
                // Unreadable root: surface nothing false; the next scan retries.
            }
        }
        return out;
    }

    private static String sha256(Path file) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digest);
        } catch (IOException e) {
            return "unreadable:" + e.getMessage();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
