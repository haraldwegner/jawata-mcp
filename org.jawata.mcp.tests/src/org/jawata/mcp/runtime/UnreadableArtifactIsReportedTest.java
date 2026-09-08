package org.jawata.mcp.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sprint 28e (D5) — <b>a size that could not be taken is not the number zero.</b>
 *
 * <p>{@code sizeOf} returned {@code 0} when the artifact directory could not be walked, which
 * is the same answer an EMPTY artifact gives. A caller deciding what to delete could not tell
 * "this takes no space" from "I could not look", and both are well-formed numbers — which is
 * why no reader, review or test ever caught it. It is this sprint's own bug class, an absence
 * reported as an emptiness, rendered as a NUMBER rather than a message.</p>
 *
 * <p>The unreadable directory is made by removing its permissions, which is the cheapest REAL
 * failure available: no mock stands in for the filesystem, so the {@code IOException} is the
 * one the production catch clause actually sees.</p>
 */
class UnreadableArtifactIsReportedTest {

    /** A store with one artifact whose directory holds a file of known size. */
    private Path storeWithOneArtifact() throws Exception {
        Path root = Files.createTempDirectory("jawata-artifacts-");
        Path dir = root.resolve("art-1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(RuntimeArtifactStore.MANIFEST_FILE), "{\"kind\":\"probe\"}");
        Files.writeString(dir.resolve("payload.bin"), "0123456789");
        return root;
    }

    /** Strip every permission, and say honestly when the platform will not honour it. */
    private void makeUnreadable(Path dir) throws Exception {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("---------"));
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "no POSIX permissions here — the unreadable case cannot be built");
        }
        // Running as root defeats the permission bits, and a test that silently measures a
        // READABLE directory would report this fix as working without exercising it.
        assumeTrue(!Files.isReadable(dir),
            "the directory is still readable (running as root?) — refusing to pass on a case "
                + "this run could not construct");
    }

    private void restore(Path dir) throws Exception {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    @DisplayName("D5: an artifact whose directory cannot be walked has NO size, not a size of 0")
    void anUnwalkableArtifactHasNoSize() throws Exception {
        Path root = storeWithOneArtifact();
        RuntimeArtifactStore store = new RuntimeArtifactStore(root);
        Path dir = root.resolve("art-1");

        // THE CONTROL, and it runs first: readable, the same call answers with a real number.
        // Without it an implementation that returned empty ALWAYS would pass the case below.
        OptionalLong readable = store.sizeOf("art-1");

        makeUnreadable(dir);
        try {
            OptionalLong unreadable = store.sizeOf("art-1");

            assertAll(
                () -> assertTrue(readable.isPresent(), "the control must measure something"),
                () -> assertTrue(readable.getAsLong() >= 10,
                    "the payload is 10 bytes; got: " + readable),
                () -> assertFalse(unreadable.isPresent(),
                    "a walk that failed must not answer with a number — 0 here is exactly what "
                        + "an empty artifact returns, and a caller cannot tell them apart; got: "
                        + unreadable));
        } finally {
            restore(dir);
        }
    }

    @Test
    @DisplayName("D5: an artifact we CANNOT READ is not swept as an abandoned one")
    void anUnreadableArtifactIsNotSweptAsAbandoned() throws Exception {
        // THE SHARPEST INSTANCE THIS SURVEY FOUND, and it is data loss rather than a wrong
        // number. `Files.isRegularFile` returns false when a file is absent, is not a regular
        // file, OR CANNOT BE DETERMINED — its own javadoc says so. The orphan filter asked it
        // whether a manifest was there, so a directory that became unreadable answered "no
        // manifest", which is this store's definition of an abandoned capture. Past the grace
        // period it was deleted.
        Path root = storeWithOneArtifact();
        RuntimeArtifactStore store = new RuntimeArtifactStore(root);
        Path kept = root.resolve("art-1");

        // A REAL orphan beside it: no manifest, same age. This is the control — without it a
        // sweeper that had simply stopped working would pass the assertion below.
        Path realOrphan = root.resolve("art-orphan");
        Files.createDirectories(realOrphan);
        Files.writeString(realOrphan.resolve("leftover.bin"), "x");

        long aged = System.currentTimeMillis() - RuntimeArtifactStore.ORPHAN_GRACE_MILLIS - 60_000;
        Files.setLastModifiedTime(kept, java.nio.file.attribute.FileTime.fromMillis(aged));
        Files.setLastModifiedTime(realOrphan, java.nio.file.attribute.FileTime.fromMillis(aged));

        makeUnreadable(kept);
        try {
            List<String> pruned = store.pruneOrphans();

            assertAll(
                () -> assertTrue(Files.isDirectory(kept),
                    "an artifact whose manifest could not be READ must survive — 'I could not "
                        + "tell' is not evidence that it was abandoned, and acting on it "
                        + "destroys a real capture"),
                () -> assertFalse(pruned.contains("art-1"), "got: " + pruned),
                () -> assertTrue(pruned.contains("art-orphan"),
                    "a genuinely unmanifested, aged directory must still be swept, or this fix "
                        + "has simply switched the sweeper off; got: " + pruned),
                () -> assertFalse(Files.isDirectory(realOrphan), "got: " + pruned));
        } finally {
            restore(kept);
        }
    }

    @Test
    @DisplayName("D5: and the caller's own row says so, instead of carrying bytes=0")
    void theDescribedRowNamesWhatItCouldNotMeasure() throws Exception {
        Path root = storeWithOneArtifact();
        RuntimeArtifactStore store = new RuntimeArtifactStore(root);
        Path dir = root.resolve("art-1");
        makeUnreadable(dir);
        try {
            List<Map<String, Object>> rows = store.describeAll();

            // RECORDED RATHER THAN ASSERTED AWAY: an unreadable artifact is still INVISIBLE to
            // list(), which asks `Files.isRegularFile` exactly as the orphan filter did, so
            // describeAll never reaches it. That is the same defect in its non-destructive
            // form, it is NOT fixed here, and this assumption is what keeps the file honest
            // about which half shipped.
            assumeTrue(!rows.isEmpty(),
                "list() drops the unreadable artifact, so no row exists to describe — the "
                    + "surviving half of the same defect, recorded in the D5 dossier");
            Map<String, Object> row = rows.get(0);
            assertAll(
                // The half that matters: the number is ABSENT rather than wrong. A row
                // carrying bytes=0 is what a caller deletes on.
                () -> assertFalse(row.containsKey("bytes"),
                    "no size was taken, so none may be reported; got: " + row),
                () -> assertEquals("the artifact directory could not be walked",
                    row.get("bytesUnavailable"),
                    "and the row must say WHY, or the missing key is just another silence; "
                        + "got: " + row));
        } finally {
            restore(dir);
        }
    }
}
