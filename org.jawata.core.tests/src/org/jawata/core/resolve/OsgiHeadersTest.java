package org.jawata.core.resolve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 11.1 — the directive-retaining parser. Pure: no JDT, no workspace.
 */
class OsgiHeadersTest {

    @Test
    @DisplayName("a quoted version range is ONE clause; the comma inside it is content")
    void quotedRangeIsOneClause() {
        List<OsgiHeaders.Requirement> reqs = OsgiHeaders.requirements(
            "org.example.pinned;bundle-version=\"[1.0.0,2.0.0)\", org.example.plain");
        assertEquals(2, reqs.size(), () -> String.valueOf(reqs));
        assertEquals("org.example.pinned", reqs.get(0).name());
        assertEquals(Optional.of("1.0.0"), reqs.get(0).versionFloor(),
            "the FLOOR of a range is its lower bound — newest-satisfying-floor is the policy");
        assertEquals("org.example.plain", reqs.get(1).name());
        assertEquals(Optional.empty(), reqs.get(1).versionFloor());
    }

    @Test
    @DisplayName("the three directives stripDirectives used to discard are KEPT")
    void directivesAreRetained() {
        List<OsgiHeaders.Requirement> reqs = OsgiHeaders.requirements(
            "org.example.reexported;visibility:=reexport,"
                + "org.example.optional;resolution:=optional,"
                + "org.example.floored;bundle-version=\"2.1.0\"");
        assertTrue(reqs.get(0).reexport(), "visibility:=reexport survives parsing");
        assertFalse(reqs.get(0).optional());
        assertTrue(reqs.get(1).optional(), "resolution:=optional survives parsing");
        assertEquals(Optional.of("2.1.0"), reqs.get(2).versionFloor());
    }

    @Test
    @DisplayName("names() matches the old readers' view — the delegation is behaviour-preserving")
    void namesMatchesTheOldView() {
        assertEquals(List.of("a.b", "c.d"),
            OsgiHeaders.names("a.b;singleton:=true, c.d;bundle-version=\"1.0.0\""));
        assertEquals(List.of(), OsgiHeaders.names(null));
        assertEquals(List.of(), OsgiHeaders.names("  "));
    }

    @Test
    @DisplayName("lowerBound handles bare floors and both range forms")
    void lowerBoundForms() {
        assertEquals("1.0.0", OsgiHeaders.lowerBound("1.0.0"));
        assertEquals("1.0.0", OsgiHeaders.lowerBound("[1.0.0,2.0.0)"));
        assertEquals("3.2.1", OsgiHeaders.lowerBound("(3.2.1,4.0.0]"));
    }

    @Test
    @DisplayName("BundleFacts reads every header the resolver needs, from a project dir")
    void bundleFactsFromProjectDir(@TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("META-INF"));
        Files.writeString(project.resolve("META-INF/MANIFEST.MF"),
            "Manifest-Version: 1.0\n"
                + "Bundle-ManifestVersion: 2\n"
                + "Bundle-SymbolicName: org.example.rich;singleton:=true\n"
                + "Bundle-Version: 4.5.6\n"
                + "Require-Bundle: org.dep.one;visibility:=reexport,\n"
                + " org.dep.two;bundle-version=\"[1.0.0,2.0.0)\"\n"
                + "Import-Package: org.imported.pkg,\n"
                + " java.util\n"
                + "Export-Package: org.example.rich.api\n"
                + "Fragment-Host: org.example.host;bundle-version=\"1.1.0\"\n"
                + "Bundle-ClassPath: .,\n"
                + " lib/nested.jar\n"
                + "Eclipse-PlatformFilter: (& (osgi.os=linux) (osgi.ws=gtk) (osgi.arch=x86_64))\n");
        BundleFacts facts = BundleFacts.of(project).orElseThrow();
        assertEquals("org.example.rich", facts.symbolicName());
        assertEquals(Optional.of("4.5.6"), facts.version());
        assertEquals(2, facts.requiredBundles().size());
        assertTrue(facts.requiredBundles().get(0).reexport());
        assertEquals(Optional.of("1.0.0"), facts.requiredBundles().get(1).versionFloor());
        assertEquals(List.of("org.imported.pkg"), facts.importedPackages(),
            "java.* is the JRE container's business, excluded exactly as the old reader did");
        assertEquals(List.of("org.example.rich.api"), facts.exportedPackages());
        assertEquals("org.example.host", facts.fragmentHost().orElseThrow().name());
        assertEquals(Optional.of("1.1.0"), facts.fragmentHost().orElseThrow().versionFloor());
        assertEquals(List.of(".", "lib/nested.jar"), facts.bundleClassPath());
        assertTrue(facts.platformFilter().orElseThrow().contains("osgi.os=linux"));
    }

    @Test
    @DisplayName("a directory without a manifest is not a bundle — empty, never an error")
    void noManifestNoBundle(@TempDir Path dir) throws Exception {
        assertTrue(BundleFacts.of(dir).isEmpty());
    }

    @Test
    @DisplayName("BundleFacts.of(Manifest) works for jar manifests too — one parser, two sources")
    void factsFromJarManifest() {
        java.util.jar.Manifest m = new java.util.jar.Manifest();
        m.getMainAttributes().putValue("Manifest-Version", "1.0");
        m.getMainAttributes().putValue("Bundle-SymbolicName", "org.example.fromjar");
        m.getMainAttributes().putValue("Fragment-Host", "org.example.host");
        BundleFacts facts = BundleFacts.of(m).orElseThrow();
        assertEquals("org.example.fromjar", facts.symbolicName());
        assertEquals("org.example.host", facts.fragmentHost().orElseThrow().name());
        assertTrue(facts.importedPackages().isEmpty());
    }
}
