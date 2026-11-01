package org.jawata.core.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 21d (item A) — the JDT-free scan core: external edits (agent, git, another
 * editor) are detected per call by evidence (mtime/size, hash in the FS-granularity
 * window), never by a watcher and never behind a switch.
 */
class DiskSyncGuardTest {

    /** What the Java provider declares for its source files; the scan names no suffix of its own (28h D15). */
    private static final java.util.Set<String> JAVA = java.util.Set.of(".java");

    @Test
    void a_scan_with_no_suffix_looks_at_no_file(@TempDir Path dir) throws IOException {
        write(dir, "src/com/a/A.java", "class A {}");
        DiskSyncGuard guard = new DiskSyncGuard();
        guard.scan(List.of(dir), java.util.Set.of());
        write(dir, "src/com/a/B.java", "class B {}");
        DiskSyncGuard.ScanResult r = guard.scan(List.of(dir), java.util.Set.of());
        assertTrue(r.isEmpty(), "no language declared a suffix, so a new Java file is not looked for: " + r);
    }

    @Test
    void the_scan_looks_for_the_suffix_a_language_declared_and_for_no_other(@TempDir Path dir) throws IOException {
        write(dir, "src/a/A.java", "class A {}");
        write(dir, "src/a/a.cpp", "int a;");
        DiskSyncGuard guard = new DiskSyncGuard();
        guard.scan(List.of(dir), java.util.Set.of(".cpp"));
        write(dir, "src/a/b.cpp", "int b;");
        write(dir, "src/a/B.java", "class B {}");
        DiskSyncGuard.ScanResult r = guard.scan(List.of(dir), java.util.Set.of(".cpp"));
        assertEquals(1, r.added().size(), "only the declared suffix is looked for: " + r);
        assertTrue(r.added().get(0).toString().endsWith("b.cpp"), r.toString());
    }

    @Test
    void an_empty_suffix_names_no_file(@TempDir Path dir) throws IOException {
        write(dir, "src/a/A.java", "class A {}");
        DiskSyncGuard guard = new DiskSyncGuard();
        guard.scan(List.of(dir), java.util.Set.of(""));
        write(dir, "src/a/B.java", "class B {}");
        write(dir, "pom.xml", "<project/>");
        DiskSyncGuard.ScanResult r = guard.scan(List.of(dir), java.util.Set.of(""));
        assertTrue(r.isEmpty(), "every name ends in the empty string, so honouring it would scan every file: " + r);
    }

    @Test
    void the_files_of_a_suffix_no_language_declares_any_more_are_reported_deleted(@TempDir Path dir) throws IOException {
        write(dir, "src/a/a.cpp", "int a;");
        write(dir, "src/a/A.java", "class A {}");
        DiskSyncGuard guard = new DiskSyncGuard();
        guard.scan(List.of(dir), java.util.Set.of(".java", ".cpp"));
        DiskSyncGuard.ScanResult r = guard.scan(List.of(dir), java.util.Set.of(".java"));
        assertEquals(1, r.deleted().size(), "the language that left takes its files out of the baseline: " + r);
        assertTrue(r.deleted().get(0).toString().endsWith("a.cpp"), r.toString());
    }

    private Path write(Path dir, String rel, String content) throws IOException {
        Path f = dir.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
        return f;
    }

    @Test
    void first_scan_primes_the_baseline_and_reports_the_new_root(@TempDir Path dir) throws IOException {
        write(dir, "src/com/a/A.java", "class A {}");
        DiskSyncGuard guard = new DiskSyncGuard();
        DiskSyncGuard.ScanResult first = guard.scan(List.of(dir), JAVA);
        assertTrue(first.isEmpty(), "the first scan is the baseline, not a change set");
        assertEquals(List.of(dir.toAbsolutePath().normalize()), first.newRoots(),
            "first sight of a root is reported so the caller reconciles it whole — "
                + "closing the blind window between model load and first guard pass");
        assertTrue(guard.scan(List.of(dir), JAVA).newRoots().isEmpty(), "a primed root is not new");
    }

    @Test
    void detects_modified_files(@TempDir Path dir) throws IOException {
        Path a = write(dir, "src/com/a/A.java", "class A {}");
        DiskSyncGuard guard = new DiskSyncGuard();
        guard.scan(List.of(dir), JAVA);

        Files.writeString(a, "class A { int changed; }");
        DiskSyncGuard.ScanResult r = guard.scan(List.of(dir), JAVA);
        assertEquals(List.of(a.toAbsolutePath().normalize()), r.changed());
        assertTrue(r.added().isEmpty());
        assertTrue(r.deleted().isEmpty());
    }

    @Test
    void detects_added_files_including_new_directories(@TempDir Path dir) throws IOException {
        write(dir, "src/com/a/A.java", "class A {}");
        DiskSyncGuard guard = new DiskSyncGuard();
        guard.scan(List.of(dir), JAVA);

        // A new file in a NEW package dir — not a "known file"; disk is truth.
        Path fresh = write(dir, "src/com/brandnew/Fresh.java", "class Fresh {}");
        DiskSyncGuard.ScanResult r = guard.scan(List.of(dir), JAVA);
        assertEquals(List.of(fresh.toAbsolutePath().normalize()), r.added());
        assertTrue(r.changed().isEmpty());
    }

    @Test
    void detects_deleted_files(@TempDir Path dir) throws IOException {
        Path a = write(dir, "src/com/a/A.java", "class A {}");
        Path b = write(dir, "src/com/a/B.java", "class B {}");
        DiskSyncGuard guard = new DiskSyncGuard();
        guard.scan(List.of(dir), JAVA);

        Files.delete(b);
        DiskSyncGuard.ScanResult r = guard.scan(List.of(dir), JAVA);
        assertEquals(List.of(b.toAbsolutePath().normalize()), r.deleted());
        assertTrue(r.changed().isEmpty(), "untouched sibling " + a + " not reported");
    }

    @Test
    void unchanged_tree_scans_empty(@TempDir Path dir) throws IOException {
        for (int i = 0; i < 50; i++) {
            write(dir, "src/p" + (i % 5) + "/C" + i + ".java", "class C" + i + " {}");
        }
        DiskSyncGuard guard = new DiskSyncGuard();
        guard.scan(List.of(dir), JAVA);
        DiskSyncGuard.ScanResult r = guard.scan(List.of(dir), JAVA);
        assertTrue(r.isEmpty(), "no edit -> no work (the only legitimate skip)");
    }

    @Test
    void same_size_same_mtime_edit_inside_granularity_window_is_caught_by_hash(@TempDir Path dir)
            throws IOException {
        Path a = write(dir, "src/com/a/A.java", "class A { int x = 1; }");
        FileTime t = Files.getLastModifiedTime(a);
        DiskSyncGuard guard = new DiskSyncGuard();
        guard.scan(List.of(dir), JAVA);

        // Same length, same forced mtime — invisible to the sig fast path; the file is
        // young (mtime >= lastScan - granularity), so the hash trigger must fire.
        Files.writeString(a, "class A { int x = 2; }");
        Files.setLastModifiedTime(a, t);
        DiskSyncGuard.ScanResult r = guard.scan(List.of(dir), JAVA);
        assertEquals(List.of(a.toAbsolutePath().normalize()), r.changed(),
            "granularity-window same-size edit detected via content hash");
    }

    @Test
    void old_untouched_files_are_not_hashed(@TempDir Path dir) throws IOException {
        Path a = write(dir, "src/com/a/A.java", "class A { int x = 1; }");
        // Age the file far outside any granularity window.
        Files.setLastModifiedTime(a, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
        DiskSyncGuard guard = new DiskSyncGuard();
        guard.scan(List.of(dir), JAVA);
        DiskSyncGuard.ScanResult r = guard.scan(List.of(dir), JAVA);
        assertTrue(r.isEmpty());
        assertEquals(0, r.hashedCount(), "aged files are trusted by sig alone — no hash cost");
    }

    @Test
    void reported_changes_are_consumed_second_scan_is_empty(@TempDir Path dir) throws IOException {
        Path a = write(dir, "src/com/a/A.java", "class A {}");
        DiskSyncGuard guard = new DiskSyncGuard();
        guard.scan(List.of(dir), JAVA);
        Files.writeString(a, "class A { int y; }");
        assertTrue(!guard.scan(List.of(dir), JAVA).isEmpty());
        assertTrue(guard.scan(List.of(dir), JAVA).isEmpty(), "a change is reported exactly once");
    }

    @Test
    void roots_no_longer_scanned_are_dropped_silently(@TempDir Path dir) throws IOException {
        Path rootA = dir.resolve("a");
        Path rootB = dir.resolve("b");
        write(rootA, "src/A.java", "class A {}");
        Path bFile = write(rootB, "src/B.java", "class B {}");
        DiskSyncGuard guard = new DiskSyncGuard();
        guard.scan(List.of(rootA, rootB), JAVA);

        // Project B removed from the workspace: its files are not "deleted" edits —
        // the workspace no longer owns them.
        DiskSyncGuard.ScanResult r = guard.scan(List.of(rootA), JAVA);
        assertTrue(r.isEmpty(), "unloaded root's files (" + bFile + ") are not change reports");
    }

    @Test
    void non_java_files_and_hidden_dirs_are_ignored(@TempDir Path dir) throws IOException {
        write(dir, "src/com/a/A.java", "class A {}");
        DiskSyncGuard guard = new DiskSyncGuard();
        guard.scan(List.of(dir), JAVA);

        write(dir, "src/com/a/notes.md", "notes");
        write(dir, ".git/objects/xx", "blob");
        write(dir, "target/gen/G.java", "class G {}");
        DiskSyncGuard.ScanResult r = guard.scan(List.of(dir), JAVA);
        assertTrue(r.isEmpty(), "only visible .java sources outside build-output dirs count");
    }

    @Test
    void scan_reports_its_duration(@TempDir Path dir) throws IOException {
        for (int i = 0; i < 500; i++) {
            write(dir, "src/p" + (i % 20) + "/C" + i + ".java", "class C" + i + " {}");
        }
        DiskSyncGuard guard = new DiskSyncGuard();
        guard.scan(List.of(dir), JAVA);
        DiskSyncGuard.ScanResult r = guard.scan(List.of(dir), JAVA);
        assertTrue(r.durationNanos() > 0);
        // Soft gate: log-only — the hard benchmark is Stage 3 at workspace scope.
        System.out.printf("DiskSyncGuard unchanged-scan of 500 files: %.2f ms%n",
            r.durationNanos() / 1_000_000.0);
    }
}
