package org.jawata.core.host;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sprint 28e (D5) — <b>0 means the tree is gone, so it must not be the answer to
 * "I could not count".</b>
 *
 * <p>{@link HostFs#deleteRecursively} documents its own return in one sentence:
 * {@code 0} means the tree is gone. Its final catch returned {@code 0} under a comment
 * reading <i>"Gone between the loop and the count — the goal state after all"</i>, which is
 * one true explanation of a walk that throws and not the only one: a directory that is still
 * present and merely unreadable throws there too, and answered with the number that means
 * removed. The same file's javadoc had already ruled on this — <i>"a caller that reports
 * 'nothing left behind' must not claim it without looking"</i> — so the defect was a
 * violation of a rule written above it.</p>
 *
 * <p>The unreadable directory is built by stripping permissions, which is the cheapest REAL
 * failure available: no mock stands in for the filesystem, so the exception the production
 * catch sees is the one it would see in the field.</p>
 */
class HostFsResidueTest {

    /** Strip every permission, and say honestly when the platform will not honour it. */
    private void makeUnreadable(Path dir) throws Exception {
        if (!HostFs.supportsPosixPermissions()) {
            assumeTrue(false, "no POSIX permissions here — the unreadable case cannot be built");
        }
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("---------"));
        // Running as root defeats the permission bits, and a test that silently measured a
        // READABLE directory would report this fix as working without ever exercising it.
        assumeTrue(!Files.isReadable(dir),
            "the directory is still readable (running as root?) — refusing to pass on a case "
                + "this run could not construct");
    }

    @Test
    @DisplayName("D5: a tree that could not be COUNTED does not answer 0, which means removed")
    void anUnreadableTreeIsNotReportedAsGone() throws Exception {
        Path root = Files.createTempDirectory("jawata-hostfs-");
        Path tree = root.resolve("stubborn");
        Files.createDirectories(tree.resolve("nested"));
        Files.writeString(tree.resolve("nested").resolve("payload.bin"), "0123456789");

        // THE CONTROL, and it runs FIRST: on a tree we CAN read, the same call really does
        // delete and really does answer 0. Without it, an implementation that never returned
        // 0 at all would satisfy the case below while having broken deletion entirely.
        Path deletable = root.resolve("ordinary");
        Files.createDirectories(deletable.resolve("nested"));
        Files.writeString(deletable.resolve("nested").resolve("payload.bin"), "x");
        long goneResidue = HostFs.deleteRecursively(deletable);

        makeUnreadable(tree);
        try {
            long stubbornResidue = HostFs.deleteRecursively(tree);

            assertAll(
                () -> assertEquals(0, goneResidue,
                    "the control: a readable tree IS deleted, and 0 is what that means"),
                () -> assertTrue(Files.notExists(deletable),
                    "and the control's 0 is earned — the tree really is gone"),
                () -> assertTrue(stubbornResidue > 0,
                    "a walk that FAILED must not answer 0: 0 is this method's own word for "
                        + "'the tree is gone', and the tree is still there. got: "
                        + stubbornResidue),
                () -> assertTrue(Files.isDirectory(tree),
                    "proof of life for the case — the tree must genuinely have survived, or "
                        + "the assertion above is measuring nothing"));
        } finally {
            Files.setPosixFilePermissions(tree, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    @DisplayName("D5: a tree whose EXISTENCE cannot be determined is not reported as gone either")
    void aTreeWhoseExistenceCannotBeDeterminedIsNotReportedAsGone() throws Exception {
        // The case the FIRST test cannot reach, and the reason it needs its own.
        //
        // Stripping a directory's own permissions leaves it perfectly stat-able — `exists`
        // walks the PARENT's execute bit, not the target's — so `Files.exists` still answers
        // truthfully there and the two guards inside deleteRecursively never see cannot-tell.
        // Making the PARENT unreadable is what produces it: `Files.exists(child)` then answers
        // FALSE because it cannot determine, and `!exists` reads that as "gone".
        //
        // That fold sat in the entry guard and in the loop's early exit while THIS FILE's
        // subject was the identical fold in the final catch, and the method's own comment
        // twenty lines below forbade it. The C8 audit found it; no test could have, because
        // no test built this case.
        Path parent = Files.createTempDirectory("jawata-hostfs-parent-");
        Path tree = parent.resolve("hidden");
        Files.createDirectories(tree.resolve("nested"));
        Files.writeString(tree.resolve("nested").resolve("payload.bin"), "0123456789");

        makeUnreadable(parent);
        long residue;
        try {
            // Proof the case was constructed: existence is now UNDETERMINABLE — both queries
            // answer false, which is the state `!exists` silently reads as absence.
            assumeTrue(!Files.exists(tree) && !Files.notExists(tree),
                "the parent is still traversable, so existence is still determinable — this "
                    + "run could not construct the case and must not report a pass");
            residue = HostFs.deleteRecursively(tree);
        } finally {
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        assertAll(
            () -> assertTrue(residue > 0,
                "existence could not be determined, so 'the tree is gone' is a claim we have "
                    + "not earned — 0 is exactly that claim. got: " + residue),
            () -> assertTrue(Files.isDirectory(tree),
                "proof of life, checked after the parent is readable again: the tree really "
                    + "did survive, so the assertion above is measuring something"));
    }
}
