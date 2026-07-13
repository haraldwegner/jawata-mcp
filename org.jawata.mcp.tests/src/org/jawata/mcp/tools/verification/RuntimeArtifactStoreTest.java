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

        String stale = store.newArtifactId("dump");
        store.createArtifactDir(stale);
        store.writeManifest(stale, Map.of(
            "kind", "dump",
            "expiresMillis", System.currentTimeMillis() - 1_000));   // already past

        String live = store.newArtifactId("dump");
        store.createArtifactDir(live);
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
}
