package org.jawata.mcp.coverage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sprint 28e (D5) — <b>a class file that cannot be stat'd must not make the artifact look
 * FRESH.</b>
 *
 * <p>{@code CoverageService.rootsFingerprint} is a staleness fingerprint: the newest
 * modification time across the class roots, reduced with {@code max()}, compared against a
 * cached value to decide whether a coverage model may be reused. Its per-file accessor
 * swallowed {@code IOException} and answered {@code 0}, and the DIRECTION is what made that
 * serious rather than merely imprecise: the epoch can never RAISE a maximum, so a class that
 * had been rebuilt but could not be read left {@code newest} unchanged, the cache matched, and
 * coverage was served over stale bytes. Failing open in the one direction a staleness check
 * must not.</p>
 *
 * <p><b>THIS TEST EXISTS BECAUSE THE CLAIM THAT IT COULD NOT EXIST WAS WRONG.</b> The fix was
 * recorded as "unguarded by construction", reasoning that reaching the inner catch needs a
 * file to pass {@code Files.isRegularFile} and then fail the accessor — a race no fixture can
 * produce. That is true of the sibling swallow in {@code RuntimeArtifactStore.sizeOf}, whose
 * walk really does filter on {@code Files::isRegularFile}. It is <b>false here</b>: this walk
 * filters on {@code getFileName().toString().endsWith(".class")}, a string test that performs
 * no stat at all. So a DANGLING SYMLINK named {@code *.class} reaches the accessor
 * deterministically — {@code Files.walk} does not follow links, so it yields the link itself
 * and the name test passes; {@code Files.getLastModifiedTime} does follow it, and throws.</p>
 *
 * <p>The C8 fresh-context audit found that, not the author. It is recorded here rather than in
 * a commit message because the lesson belongs where the next person reads the guard: <b>"no
 * test can reach this" is a claim about a filter, and it must be checked against the filter
 * that is actually written.</b></p>
 */
class StaleFingerprintIsNotFreshTest {

    private CoverageManifest manifestOver(Path... roots) {
        CoverageManifest m = new CoverageManifest();
        for (Path r : roots) {
            m.classRoots.add(r.toString());
        }
        return m;
    }

    @Test
    @DisplayName("D5: a class file whose timestamp cannot be read makes the fingerprint ABSENT, not fresh")
    void anUnstattableClassFileHasNoFingerprint() throws Exception {
        Path root = Files.createTempDirectory("jawata-classroot-");
        Files.writeString(root.resolve("Real.class"), "not really bytecode, but it is stattable");

        // THE CONTROL, and it runs FIRST: over a root whose files can all be read, the same
        // call answers with a real number. Without it, an implementation that returned empty
        // ALWAYS would pass the case below while having broken cache invalidation entirely.
        OptionalLong readable = CoverageService.rootsFingerprint(manifestOver(root));

        Path dangling = root.resolve("Vanished.class");
        try {
            Files.createSymbolicLink(dangling, root.resolve("no-such-target"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            assumeTrue(false, "this filesystem will not create a symbolic link: " + e.getMessage());
        }
        // Proof the case was really constructed: the link is THERE (walk will yield it) and
        // its target is NOT (the accessor will throw). A test that silently created neither
        // would report the fix as working without exercising it.
        assumeTrue(Files.isSymbolicLink(dangling) && Files.notExists(dangling),
            "the dangling link was not constructed, so this run measures nothing");

        OptionalLong withDangling = CoverageService.rootsFingerprint(manifestOver(root));

        assertAll(
            () -> assertTrue(readable.isPresent(), "the control must measure something"),
            () -> assertTrue(readable.getAsLong() > 0,
                "and its value must be a real timestamp; got: " + readable),
            () -> assertFalse(withDangling.isPresent(),
                "a class file whose timestamp could not be read must make the fingerprint "
                    + "ABSENT. Answering with a number here is the dangerous direction: the "
                    + "epoch cannot raise a max, so the artifact reads as unchanged and stale "
                    + "coverage is served. got: " + withDangling));
    }
}
