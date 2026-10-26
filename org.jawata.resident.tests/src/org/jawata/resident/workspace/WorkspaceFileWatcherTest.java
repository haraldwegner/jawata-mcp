package org.jawata.resident.workspace;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;

import org.jawata.resident.fixtures.TestProjectHelper;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.resident.workspace.WorkspaceFileWatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Integration tests for {@link WorkspaceFileWatcher}. Drives the watcher
 * against real JdtServiceImpl + real fixture projects, and writes
 * {@code workspace.json} files to provoke load/add/remove.
 *
 * <p>Uses a polling helper {@link #waitUntil(Duration, java.util.function.BooleanSupplier)}
 * because {@code WatchService} events are inherently asynchronous; we accept
 * a short delay (max 30 s) for the file-watcher thread to react.
 */
class WorkspaceFileWatcherTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private Path dataDir;
    private Path workspaceJson;
    private WorkspaceFileWatcher watcher;
    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        dataDir = Files.createTempDirectory("jawata-workspace-watcher-test-");
        workspaceJson = dataDir.resolve("workspace.json");
        service = helper.disposeAfter(new JdtServiceImpl());
    }

    @AfterEach
    void tearDown() {
        if (watcher != null) {
            watcher.close();
            watcher = null;
        }
        // best-effort cleanup; tempdir cleanup not strictly needed
    }

    @Test
    @DisplayName("initial load reads workspace.json and loads listed projects")
    void initialLoad_loadsAllProjects() throws Exception {
        Path simpleMaven = helper.getFixturePath("simple-maven");
        Path simpleMavenB = helper.getFixturePath("simple-maven-b");
        writeWorkspaceJson(simpleMaven, simpleMavenB);

        watcher = new WorkspaceFileWatcher(dataDir, service);
        watcher.start();

        assertEquals(2, service.allProjects().size(),
            "Both fixture projects should be loaded after initial start()");
        assertTrue(service.defaultProjectKey().isPresent(),
            "Default project key should be set (first project loaded via loadProject)");
    }

    @Test
    @DisplayName("a host that owns its own workspace is never changed by workspace.json: start() loads nothing")
    void startLoadsNothingWhenTheHostOwnsTheWorkspace() throws Exception {
        writeWorkspaceJson(helper.getFixturePath("simple-maven"));

        watcher = new WorkspaceFileWatcher(dataDir, service, new org.jawata.spi.host.HostWorkspace() {
            @Override
            public java.util.List<ProjectFolder> folders() {
                return java.util.List.of();
            }

            @Override
            public boolean ownsWorkspace() {
                return false;
            }
        });
        watcher.start();

        assertEquals(0, service.allProjects().size(), "the host's workspace is the host's: the watcher added nothing");
    }

    @Test
    @DisplayName("appending a project to workspace.json adds it to the workspace within 30s")
    void addProjectOnFileChange() throws Exception {
        Path simpleMaven = helper.getFixturePath("simple-maven");
        writeWorkspaceJson(simpleMaven);

        watcher = new WorkspaceFileWatcher(dataDir, service);
        watcher.start();
        assertEquals(1, service.allProjects().size());

        // Mutate the file: add simple-maven-b.
        Path simpleMavenB = helper.getFixturePath("simple-maven-b");
        writeWorkspaceJson(simpleMaven, simpleMavenB);

        boolean grew = waitUntil(Duration.ofSeconds(30), () -> service.allProjects().size() == 2);
        assertTrue(grew,
            "Watcher should have picked up the added path within 30 s. Loaded: " +
            service.projectKeys());
    }

    @Test
    @DisplayName("removing a project from workspace.json drops it from the workspace within 30s")
    void removeProjectOnFileChange() throws Exception {
        Path simpleMaven = helper.getFixturePath("simple-maven");
        Path simpleMavenB = helper.getFixturePath("simple-maven-b");
        writeWorkspaceJson(simpleMaven, simpleMavenB);

        watcher = new WorkspaceFileWatcher(dataDir, service);
        watcher.start();
        assertEquals(2, service.allProjects().size());

        // Mutate the file: drop simple-maven-b.
        writeWorkspaceJson(simpleMaven);

        boolean shrunk = waitUntil(Duration.ofSeconds(30), () -> service.allProjects().size() == 1);
        assertTrue(shrunk,
            "Watcher should have removed the dropped path within 30 s. Loaded: " +
            service.projectKeys());
    }

    @Test
    @DisplayName("bugs.md #6: non-atomic direct write to workspace.json (no tmp+rename) is still picked up")
    void picksUpNonAtomicDirectWrite() throws Exception {
        Path simpleMaven = helper.getFixturePath("simple-maven");
        writeWorkspaceJson(simpleMaven);

        watcher = new WorkspaceFileWatcher(dataDir, service);
        watcher.start();
        assertEquals(1, service.allProjects().size());

        // Bypass the atomic-rename helper: write the new contents directly to
        // workspace.json. This is the production-suspect failure mode for
        // bug #6 — a write pattern that doesn't go through Files.move(...
        // ATOMIC_MOVE) and might fire different (or fewer) WatchService
        // events. The new mtime-fallback poll guarantees reconciliation
        // regardless of event delivery.
        Path simpleMavenB = helper.getFixturePath("simple-maven-b");
        // Sprint 28a: escape the separators, as the builder at the bottom of this
        // file already does. A raw Windows path pasted into JSON is a string of
        // invalid escapes (\U, \h, ...), every reconcile parse fails, and the
        // watcher test times out on a fixture bug rather than a product one.
        String body = "{\n  \"version\": 1,\n  \"name\": \"test\",\n  \"projects\": [\n"
            + "    \"" + simpleMaven.toAbsolutePath().toString().replace("\\", "\\\\") + "\",\n"
            + "    \"" + simpleMavenB.toAbsolutePath().toString().replace("\\", "\\\\") + "\"\n"
            + "  ]\n}\n";
        Files.writeString(workspaceJson, body);

        boolean grew = waitUntil(Duration.ofSeconds(30),
            () -> service.allProjects().size() == 2);
        assertTrue(grew,
            "Watcher should pick up a non-atomic direct write within 30 s. Loaded: " +
            service.projectKeys());
    }

    @Test
    @DisplayName("bugs.md #6: short-fallback-poll constructor reconciles within the poll interval even if events were missed")
    void shortFallbackPoll_reconcilesViaMtimeCheck() throws Exception {
        Path simpleMaven = helper.getFixturePath("simple-maven");
        writeWorkspaceJson(simpleMaven);

        // Test-only constructor with 1s fallback poll.
        watcher = new WorkspaceFileWatcher(dataDir, service, 1);
        watcher.start();
        assertEquals(1, service.allProjects().size());

        // Modify via tmp+rename — events should fire AND the fallback would
        // catch it within 1s if events did get missed. Either path produces
        // the expected reconciliation.
        Path simpleMavenB = helper.getFixturePath("simple-maven-b");
        writeWorkspaceJson(simpleMaven, simpleMavenB);

        boolean grew = waitUntil(Duration.ofSeconds(3),
            () -> service.allProjects().size() == 2);
        assertTrue(grew,
            "Short-fallback-poll watcher should reconcile within 3 s. Loaded: " +
            service.projectKeys());
    }

    @Test
    @DisplayName("malformed JSON write does not crash the watcher")
    void malformedJson_logsAndContinues() throws Exception {
        Path simpleMaven = helper.getFixturePath("simple-maven");
        writeWorkspaceJson(simpleMaven);

        watcher = new WorkspaceFileWatcher(dataDir, service);
        watcher.start();
        assertEquals(1, service.allProjects().size());

        // Corrupt the file. Watcher should log and continue running.
        Files.writeString(workspaceJson, "{ this is not json }");
        Thread.sleep(500); // give the watcher a moment to process the bad write

        // Now write a valid file again. If the watcher survived, it should
        // pick up this change and react.
        Path simpleMavenB = helper.getFixturePath("simple-maven-b");
        writeWorkspaceJson(simpleMaven, simpleMavenB);

        boolean recovered = waitUntil(Duration.ofSeconds(30), () -> service.allProjects().size() == 2);
        assertTrue(recovered,
            "Watcher should still be alive after malformed write. Loaded: " +
            service.projectKeys());
    }

    @Test
    @DisplayName("28k C2: a refused watch service does not fail start() — the initial load stands and a later change is followed by polling")
    void refusedWatchServiceStillFollowsTheFile() throws Exception {
        Path simpleMaven = helper.getFixturePath("simple-maven");
        writeWorkspaceJson(simpleMaven);

        watcher = new WorkspaceFileWatcher(dataDir, service, 1, WorkspaceFileWatcherTest::refuse);
        assertDoesNotThrow(watcher::start, "the operating system refusing a watch service must not fail the load");
        assertEquals(1, service.allProjects().size(), "the initial load happened");

        writeWorkspaceJson(simpleMaven, helper.getFixturePath("simple-maven-b"));

        assertTrue(waitUntil(Duration.ofSeconds(10), () -> service.allProjects().size() == 2),
            "polling alone should reconcile the change; loaded: " + service.projectKeys());
    }

    @Test
    @DisplayName("28k C2: a workspace.json that appears after a refused watch service is picked up by polling")
    void fileCreatedLaterIsFollowedByPolling() throws Exception {
        watcher = new WorkspaceFileWatcher(dataDir, service, 1, WorkspaceFileWatcherTest::refuse);
        assertDoesNotThrow(watcher::start);
        assertEquals(0, service.allProjects().size());

        writeWorkspaceJson(helper.getFixturePath("simple-maven"));

        assertTrue(waitUntil(Duration.ofSeconds(10), () -> service.allProjects().size() == 1),
            "the file's appearance should be seen by polling; loaded: " + service.projectKeys());
    }

    @Test
    @DisplayName("28k C2: with no watch service, a write that lands while a reconcile is loading projects is not marked as already seen")
    void writeDuringAReconcileIsFollowedByPolling() throws Exception {
        Path first = helper.getFixturePath("simple-maven");
        Path second = helper.getFixturePath("simple-maven-b");
        Path third = helper.getFixturePath("compile-clean");
        SlowAdd slow = new SlowAdd();
        JdtServiceImpl slowService = helper.disposeAfter(slow);
        writeWorkspaceJson(first);

        watcher = new WorkspaceFileWatcher(dataDir, slowService, 1, WorkspaceFileWatcherTest::refuse);
        watcher.start();
        assertEquals(1, slowService.allProjects().size());

        writeWorkspaceJson(first, second);
        assertTrue(slow.inside.await(30, java.util.concurrent.TimeUnit.SECONDS),
            "the poll should have started a reconcile that is now loading the second project");
        // The reconcile is held inside its project load: rewrite the file now, with a modification time that cannot be
        // mistaken for the one the reconcile started from.
        writeWorkspaceJson(first, second, third);
        Files.setLastModifiedTime(workspaceJson, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        slow.release.countDown();

        assertTrue(waitUntil(Duration.ofSeconds(60), () -> slowService.allProjects().size() == 3),
            "the write made during the reconcile must be picked up by the next poll; loaded: " + slowService.projectKeys());
    }

    @Test
    @DisplayName("28k C2: with no watch service, a write that lands while the initial load is running is not marked as already seen")
    void writeDuringTheInitialLoadIsFollowedByPolling() throws Exception {
        Path first = helper.getFixturePath("simple-maven");
        Path second = helper.getFixturePath("simple-maven-b");
        Path third = helper.getFixturePath("compile-clean");
        SlowAdd slow = new SlowAdd();
        JdtServiceImpl slowService = helper.disposeAfter(slow);
        writeWorkspaceJson(first, second);

        watcher = new WorkspaceFileWatcher(dataDir, slowService, 1, WorkspaceFileWatcherTest::refuse);
        java.util.concurrent.atomic.AtomicReference<Throwable> startFailure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread starter = new Thread(() -> {
            try {
                watcher.start();
            } catch (Throwable t) {
                startFailure.set(t);
            }
        }, "initial-load");
        starter.start();
        assertTrue(slow.inside.await(30, java.util.concurrent.TimeUnit.SECONDS),
            "the initial load should be loading the second project");

        writeWorkspaceJson(first, second, third);
        Files.setLastModifiedTime(workspaceJson, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        slow.release.countDown();
        starter.join(60_000);
        assertNull(startFailure.get(), "start() must not fail");

        assertTrue(waitUntil(Duration.ofSeconds(60), () -> slowService.allProjects().size() == 3),
            "the write made during the initial load must be picked up by the poll; loaded: " + slowService.projectKeys());
    }

    /** A service whose project add holds still until the test lets it go, so the test can rewrite workspace.json meanwhile. */
    private static final class SlowAdd extends JdtServiceImpl {
        final java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        @Override
        public org.jawata.core.LoadedProject addProject(Path path, org.jawata.resident.project.DeclaredSourceRoots declared)
                throws org.eclipse.core.runtime.CoreException {
            inside.countDown();
            try {
                release.await(60, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.addProject(path, declared);
        }
    }

    /** What the operating system answers when the user's inotify instances are all taken. */
    private static java.nio.file.WatchService refuse() throws java.io.IOException {
        throw new java.io.IOException("User limit of inotify instances reached or too many open files");
    }

    /**
     * Atomically write a workspace.json with the given project paths.
     * Uses a temp-and-rename pattern matching what the manager will do, so
     * the watcher never observes a half-written file.
     */
    private void writeWorkspaceJson(Path... paths) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"version\": 1,\n  \"name\": \"test\",\n  \"projects\": [\n");
        for (int i = 0; i < paths.length; i++) {
            if (i > 0) sb.append(",\n");
            // ESCAPE THE SEPARATOR. A Windows absolute path is C:\Users\... and a
            // backslash begins an escape sequence in JSON, so appending one raw
            // produces "\U" — not a valid escape. The file is then unparseable,
            // the watcher loads nothing, and all six tests in this class report
            // "expected <N> but was <0>". Found by Sprint 28a on the first run of
            // the cross-platform CI matrix; harmless on Unix, fatal on Windows.
            String jsonPath = paths[i].toAbsolutePath().toString().replace("\\", "\\\\");
            sb.append("    \"").append(jsonPath).append("\"");
        }
        sb.append("\n  ]\n}\n");

        Path tmp = workspaceJson.resolveSibling("workspace.json.tmp");
        Files.writeString(tmp, sb.toString());
        // Sprint 28a: retry the rename. On Windows an ATOMIC_MOVE onto a file
        // the watcher is concurrently READING fails AccessDenied — sharing
        // semantics, not a logic error. The reader closes within milliseconds;
        // a bounded retry is what any real Windows writer of this file needs
        // too (noted for the product's own atomic-write helper).
        java.nio.file.AccessDeniedException last = null;
        for (int i = 0; i < 20; i++) {
            try {
                Files.move(tmp, workspaceJson,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (java.nio.file.AccessDeniedException e) {
                last = e;
                Thread.sleep(50);
            }
        }
        throw last;
    }

    /**
     * Polling helper. Returns {@code true} once {@code condition} is met,
     * {@code false} after timeout.
     */
    private static boolean waitUntil(Duration timeout, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }
}
