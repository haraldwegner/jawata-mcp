package org.jawata.core.project;

import org.jawata.core.fixtures.TestJars;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 12.2 (risk R8) — the DIR_CACHE discipline on the nested Tycho
 * layout. The old cache keyed on the pool ROOT's mtime, which is blind
 * twice here: a new {@code name/version/} bumps {@code name/}, not the
 * root, and an in-place jar replacement bumps nothing at all. Both shapes
 * healed only by a resident restart, invisibly. The cache now fingerprints
 * every jar (size, mtime) on every index call.
 */
class ExternalBundlePoolNestedCacheTest {

    @Test
    @DisplayName("a version added AFTER the first index is seen by the next index (no restart)")
    void versionAddedAfterFirstIndexIsSeen(@TempDir Path pool) throws Exception {
        TestJars.nestedPoolBundle(pool, "org.example.cached", "1.0.0", Map.of());
        ExternalBundlePool first = ExternalBundlePool.index(List.of(pool));
        assertTrue(first.bundleJar("org.example.cached").orElseThrow()
            .toString().contains("1.0.0"), "the first index sees 1.0.0");

        // The blind spot: this bumps pool/org.example.cached/, NOT the root.
        TestJars.nestedPoolBundle(pool, "org.example.cached", "2.0.0", Map.of());

        ExternalBundlePool second = ExternalBundlePool.index(List.of(pool));
        assertTrue(second.bundleJar("org.example.cached").orElseThrow()
            .toString().contains("2.0.0"),
            "a version added after the first index must win the next index — "
                + "a root-mtime cache misses it until restart (R8)");
    }

    @Test
    @DisplayName("a jar REPLACED IN PLACE is re-read by the next index (no restart)")
    void jarReplacedInPlaceIsReRead(@TempDir Path pool) throws Exception {
        Path jar = TestJars.nestedPoolBundle(pool, "org.example.swapped", "1.0.0",
            Map.of("Export-Package", "org.example.before"));
        ExternalBundlePool first = ExternalBundlePool.index(List.of(pool));
        assertTrue(exportsOf(first, "org.example.swapped").contains("org.example.before"),
            "the first index reads the original manifest");

        // Replace the jar's CONTENT at the same path — different exports.
        // The directory mtime does not move; only the file's own size/mtime.
        Path rebuilt = jar.resolveSibling("rebuilt.tmp.jar");
        TestJars.bundleJar(rebuilt, Map.of(
            "Bundle-ManifestVersion", "2",
            "Bundle-SymbolicName", "org.example.swapped",
            "Bundle-Version", "1.0.0",
            "Export-Package", "org.example.after"));
        Files.move(rebuilt, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        // Force a DIFFERENT mtime even on coarse filesystem clocks.
        Files.setLastModifiedTime(jar, FileTime.fromMillis(
            Files.getLastModifiedTime(jar).toMillis() + 5000));

        ExternalBundlePool second = ExternalBundlePool.index(List.of(pool));
        assertTrue(exportsOf(second, "org.example.swapped").contains("org.example.after"),
            "an in-place replacement must be re-read on the next index — "
                + "nothing above the file's own (size, mtime) changes (R8)");
    }

    private static List<String> exportsOf(ExternalBundlePool pool, String symbolicName) {
        return pool.poolBundles().stream()
            .filter(b -> b.facts().symbolicName().equals(symbolicName))
            .flatMap(b -> b.facts().exportedPackages().stream())
            .toList();
    }
}
