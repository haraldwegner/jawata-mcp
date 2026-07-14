package org.jawata.mcp.tools.verification;

import org.jawata.mcp.runtime.RuntimeArtifactStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 (D5/D16) — what a runtime session leaves on disk: stored with
 * provenance, listed, expired, and deleted on demand.
 *
 * <p>This closes a gap: the store was part of C7's exit criteria and was not built
 * there. It is built here, because Stage 11's replay captures and phase 3's
 * profiles both land in it.</p>
 */
class RuntimeArtifactStoreTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("store → list → delete, and the manifest says where the artifact came from")
    void storeListDelete() throws Exception {
        RuntimeArtifactStore store = new RuntimeArtifactStore(root);
        assertTrue(store.list().isEmpty(), "a fresh store holds nothing");

        String id = store.newArtifactId("replay");
        Path dir = store.createArtifactDir(id);
        Files.writeString(dir.resolve("capture.json"), "{\"violatedAt\": 7}");
        store.writeManifest(id, Map.of(
            "kind", "replay",
            "sessionId", "dbg-abc123",
            "target", "com.example.ReplayApp",
            "files", List.of("capture.json")));

        // LISTED, newest first.
        assertEquals(List.of(id), store.list());
        assertTrue(store.exists(id));

        // PROVENANCE — an artifact whose origin is unknown is evidence of nothing.
        Optional<Map<String, Object>> manifest = store.readManifest(id);
        assertTrue(manifest.isPresent());
        assertEquals("replay", manifest.get().get("kind"));
        assertEquals("dbg-abc123", manifest.get().get("sessionId"));
        assertEquals("com.example.ReplayApp", manifest.get().get("target"));
        assertEquals(id, manifest.get().get("artifactId"));
        assertNotNull(manifest.get().get("createdMillis"), "stamped when stored");
        assertNotNull(manifest.get().get("expiresMillis"), "and given an expiry");

        // SIZE — the caller can see what it is costing them.
        Map<String, Object> described = store.describeAll().get(0);
        assertTrue(((Number) described.get("bytes")).longValue() > 0, "got: " + described);
        assertEquals(Boolean.FALSE, described.get("expired"));

        // EXPLICIT DELETE — these get large, so nothing disappears by itself except on expiry.
        assertTrue(store.delete(id));
        assertFalse(store.exists(id));
        assertTrue(store.list().isEmpty());
        assertFalse(Files.exists(dir), "the files are gone, not just the manifest");

        // Deleting what is not there is a false, not a crash.
        assertFalse(store.delete(id));
    }

    @Test
    @DisplayName("an expired artifact is pruned; a live one is left alone")
    void expiryPrunesOnlyWhatIsPastIt() throws Exception {
        RuntimeArtifactStore store = new RuntimeArtifactStore(root);

        // Both directories first: creating an artifact now SWEEPS the store (that is the
        // T1.14 fix — housekeeping on ordinary use), so an already-expired artifact would
        // be pruned by the very act of creating the next one. Manifests are written after,
        // which is also the real order of events in a capture.
        String stale = store.newArtifactId("dump");
        store.createArtifactDir(stale);
        String live = store.newArtifactId("dump");
        store.createArtifactDir(live);

        store.writeManifest(stale, Map.of(
            "kind", "dump",
            "expiresMillis", System.currentTimeMillis() - 1_000));   // already past
        store.writeManifest(live, Map.of("kind", "dump"));           // default TTL

        assertTrue(store.isExpired(stale));
        assertFalse(store.isExpired(live));

        assertEquals(List.of(stale), store.pruneExpired());
        assertFalse(store.exists(stale));
        assertTrue(store.exists(live), "an artifact still in its window must survive a prune");
    }

    @Test
    @DisplayName("a half-written artifact — no manifest — is not an artifact")
    void unmanifestedDirectoriesAreNotListed() throws Exception {
        RuntimeArtifactStore store = new RuntimeArtifactStore(root);
        Files.createDirectories(root.resolve("dump-partial"));
        Files.writeString(root.resolve("dump-partial").resolve("heap.hprof"), "truncated");

        assertTrue(store.list().isEmpty(),
            "without a manifest we cannot say what it is or where it came from — "
                + "so we do not offer it as evidence");
    }

    @Test
    @DisplayName("THE PRUNER ACTUALLY RUNS: an expired artifact is gone by ordinary use of the store")
    void expiryIsEnforcedByNormalUseNotJustAvailable() throws Exception {
        // Sprint-24 audit: pruneExpired() was correct code with NO production caller
        // anywhere — expiry was metadata that never deleted anything, while the `artifacts`
        // steering told the operator "expired ones are pruned". Week-old multi-GB heap dumps
        // sat forever, and the operator, told pruning happened, never deleted them by hand.
        RuntimeArtifactStore store = new RuntimeArtifactStore(root);

        String stale = store.newArtifactId("dump");
        store.createArtifactDir(stale);
        store.writeManifest(stale, Map.of(
            "kind", "dump", "expiresMillis", System.currentTimeMillis() - 1_000));
        String live = store.newArtifactId("dump");
        store.createArtifactDir(live);
        store.writeManifest(live, Map.of("kind", "dump"));

        // describeAll() is exactly what profile/debug(action=artifacts) calls. Simply
        // LOOKING at the store must honour the expiry it advertises.
        List<Map<String, Object>> described = store.describeAll();

        assertEquals(1, described.size(), "the expired artifact must be gone: " + described);
        assertEquals(live, described.get(0).get("artifactId"));
        assertFalse(store.exists(stale), "and gone from disk, not merely hidden");
        assertTrue(store.exists(live), "while one still inside its window is untouched");
    }

    @Test
    @DisplayName("an ABANDONED capture is not leaked forever — invisible to list() is not invisible to the sweeper")
    void anAbandonedCaptureIsSweptEventually() throws Exception {
        // Sprint-24 audit: a capture that failed part-way (jcmd dies at the heap dump, say)
        // left a dir with NO manifest. list() ignores it — correctly, it is not evidence —
        // so it could never be listed, never be deleted by id (the caller never learns the
        // id), and never be pruned by expiry (expiry lives in the manifest it does not
        // have). A multi-GB heap.hprof could sit there until the disk filled.
        RuntimeArtifactStore store = new RuntimeArtifactStore(root);

        Path abandoned = root.resolve("incident-abandoned");
        Files.createDirectories(abandoned);
        Files.writeString(abandoned.resolve("heap.hprof"), "a very large truncated dump");
        Files.setLastModifiedTime(abandoned,
            java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(2)));

        Path inFlight = root.resolve("incident-in-flight");
        Files.createDirectories(inFlight);   // a capture happening RIGHT NOW

        List<String> swept = store.sweep();

        assertTrue(swept.contains("incident-abandoned"), "the abandoned capture is swept: " + swept);
        assertFalse(Files.exists(abandoned), "and its files are actually gone");
        assertTrue(Files.exists(inFlight),
            "but a capture still in progress is NEVER touched — the grace period exists "
                + "precisely so a sweep cannot delete a bundle being written right now");
    }
}
