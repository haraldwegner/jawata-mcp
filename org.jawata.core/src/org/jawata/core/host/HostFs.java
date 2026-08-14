package org.jawata.core.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Sprint 28a (1b, step M9) — filesystem operations whose CORRECT behaviour
 * differs by operating system, in one place.
 *
 * <p>The deletion below is the reason this class exists. Two implementations of
 * "delete this tree" lived in this codebase. One was hardened after the first
 * Windows CI run ever ran — bounded retries, because Windows releases a killed
 * process's file handles LATE, so a single pass right after
 * {@code destroyForcibly} routinely found chunks it could not delete, swallowed
 * every failure, and left the directory behind while the contract read as
 * satisfied. The other was three lines away in a different module, still
 * swallowing everything, still single-pass. A fix that lands in one of two
 * copies is not a fix; it is a coin toss over which caller was lucky.</p>
 *
 * <p>Leaf by contract: this package depends on the JDK and nothing else.</p>
 */
public final class HostFs {

    private HostFs() {
    }

    /**
     * Recursively delete {@code dir}, with bounded retries, reporting what
     * survived.
     *
     * <p>Up to 10 passes, 250 ms apart, stopping the moment the tree is gone.
     * Residue after the last pass is RETURNED with its count — an empty result
     * on failure is a lie, and that applies to cleanup too. A caller that does
     * not care can ignore the number; a caller that reports "nothing left
     * behind" must not claim it without looking.</p>
     *
     * @param dir the directory to remove; {@code null} or absent is a no-op
     * @return the number of entries still present after the last attempt —
     *     {@code 0} means the tree is gone
     */
    public static long deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return 0;
        }
        for (int attempt = 0; attempt < 10; attempt++) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // Retried by the next pass; counted honestly after the last.
                    }
                });
            } catch (Exception ignored) {
                // The dir may already be gone — the check below settles it.
            }
            if (!Files.exists(dir)) {
                return 0;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.count();
        } catch (Exception ignored) {
            // Gone between the loop and the count — the goal state after all.
            return 0;
        }
    }

    /**
     * Whether POSIX file permissions can be read or set here.
     *
     * <p>A capability QUERY, not a try/catch around a call that throws:
     * {@code Files.setPosixFilePermissions} raises
     * {@link UnsupportedOperationException} on Windows, and a fixture that
     * learns this by catching it has already failed in a way that reads like a
     * product bug.</p>
     */
    public static boolean supportsPosixPermissions() {
        return !HostOS.current().isWindows();
    }

    /**
     * Make {@code path} executable, in whatever way this host expresses that.
     *
     * <p>On POSIX that is a permission bit. On Windows executability comes from
     * the file's EXTENSION, so this is a no-op that reports success rather than
     * a failure to do something meaningless.</p>
     */
    public static boolean setExecutable(Path path) {
        if (!supportsPosixPermissions()) {
            return true;
        }
        return path.toFile().setExecutable(true);
    }

    /**
     * The JVM crash logs in {@code dir}.
     *
     * <p>The {@code .log} suffix is load-bearing. A Windows JVM writes a
     * {@code .mdmp} minidump BESIDE its {@code hs_err_pid*.log}, and a glob of
     * {@code hs_err_pid*} alone matches both — handing a binary minidump to a
     * text parser, which then reports an empty crash rather than a crash it
     * could not read. Found by the first Windows matrix run.</p>
     */
    public static List<Path> crashLogs(Path dir) throws IOException {
        return matching(dir, name -> name.startsWith("hs_err_pid") && name.endsWith(".log"));
    }

    /**
     * The native minidumps in {@code dir} — a first-class artifact class of
     * their own, never mixed into {@link #crashLogs}.
     */
    public static List<Path> crashMinidumps(Path dir) throws IOException {
        return matching(dir, name -> name.startsWith("hs_err_pid") && name.endsWith(".mdmp"));
    }

    private static List<Path> matching(Path dir, java.util.function.Predicate<String> nameTest)
            throws IOException {
        List<Path> found = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return found;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(p -> nameTest.test(p.getFileName().toString())).forEach(found::add);
        }
        return found;
    }
}
