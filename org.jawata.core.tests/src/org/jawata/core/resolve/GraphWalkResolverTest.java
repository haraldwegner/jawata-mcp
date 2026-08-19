package org.jawata.core.resolve;

import org.jawata.core.resolve.PlatformResolver.Platform;
import org.jawata.core.resolve.PlatformResolver.PoolBundle;
import org.jawata.core.resolve.PlatformResolver.Provider;
import org.jawata.core.resolve.PlatformResolver.Wiring;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 11.2 — the resolver's whole semantic surface as PURE unit tests:
 * no JDT, no filesystem, no clock (the architecture's D-THREE).
 */
class GraphWalkResolverTest {

    private static final Platform LINUX = new Platform("linux", "gtk", "x86_64");
    private final GraphWalkResolver resolver = new GraphWalkResolver();

    // ------------------------------------------------------------------
    // builders
    // ------------------------------------------------------------------

    private static BundleFacts bundle(String name, String version, String requireBundle,
            String importPkg, String exportPkg) {
        java.util.jar.Manifest m = new java.util.jar.Manifest();
        m.getMainAttributes().putValue("Manifest-Version", "1.0");
        m.getMainAttributes().putValue("Bundle-SymbolicName", name);
        m.getMainAttributes().putValue("Bundle-Version", version);
        if (requireBundle != null) {
            m.getMainAttributes().putValue("Require-Bundle", requireBundle);
        }
        if (importPkg != null) {
            m.getMainAttributes().putValue("Import-Package", importPkg);
        }
        if (exportPkg != null) {
            m.getMainAttributes().putValue("Export-Package", exportPkg);
        }
        return BundleFacts.of(m).orElseThrow();
    }

    private static PoolBundle jar(String name, String version, Map<String, String> extra) {
        java.util.jar.Manifest m = new java.util.jar.Manifest();
        m.getMainAttributes().putValue("Manifest-Version", "1.0");
        m.getMainAttributes().putValue("Bundle-SymbolicName", name);
        m.getMainAttributes().putValue("Bundle-Version", version);
        extra.forEach((k, v) -> m.getMainAttributes().putValue(k, v));
        return new PoolBundle(BundleFacts.of(m).orElseThrow(),
            Path.of("/pool", name + "-" + version + ".jar"));
    }

    private static List<String> workspaceProviders(Wiring w) {
        return w.providers().stream()
            .filter(p -> p.workspaceBundle().isPresent())
            .map(p -> p.workspaceBundle().get()).toList();
    }

    private static List<String> jarProviders(Wiring w) {
        return w.providers().stream()
            .filter(p -> p.jar().isPresent())
            .map(p -> p.jar().get().getFileName().toString()).toList();
    }

    // ------------------------------------------------------------------
    // the rules
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a workspace sibling ALWAYS beats a pool jar of the same name")
    void workspaceWins() {
        Map<String, BundleFacts> ws = Map.of(
            "app", bundle("app", "1.0.0", "lib", null, null),
            "lib", bundle("lib", "0.5.0", null, null, "org.lib.api"));
        List<PoolBundle> pool = List.of(jar("lib", "9.9.9", Map.of()));
        Wiring app = resolver.resolve(ws, pool, LINUX).get("app");
        assertEquals(List.of("lib"), workspaceProviders(app),
            "the checked-out source IS the version under work, whatever the pool holds");
        assertTrue(jarProviders(app).isEmpty(), "no jar rides along for the same name");
        assertTrue(app.unresolved().isEmpty());
    }

    @Test
    @DisplayName("newest jar satisfying the declared floor wins; below the floor is an HONEST miss")
    void newestSatisfyingFloor() {
        Map<String, BundleFacts> ws = Map.of(
            "app", bundle("app", "1.0.0", "dep;bundle-version=\"2.0.0\"", null, null));
        Wiring floored = resolver.resolve(ws,
            List.of(jar("dep", "1.5.0", Map.of()), jar("dep", "2.5.0", Map.of()),
                    jar("dep", "2.1.0", Map.of())),
            LINUX).get("app");
        assertEquals(List.of("dep-2.5.0.jar"), jarProviders(floored));

        Wiring unmet = resolver.resolve(ws,
            List.of(jar("dep", "1.5.0", Map.of())), LINUX).get("app");
        assertTrue(jarProviders(unmet).isEmpty());
        assertEquals(1, unmet.unresolved().size());
        assertTrue(unmet.unresolved().get(0).reason().contains("floor"),
            "the reason names WHY: " + unmet.unresolved());
    }

    @Test
    @DisplayName("cycles wire both ways — data, not an error, not a stack overflow")
    void cyclesAreLegal() {
        Map<String, BundleFacts> ws = Map.of(
            "a", bundle("a", "1.0.0", "b", null, "pkg.a"),
            "b", bundle("b", "1.0.0", "a", null, "pkg.b"));
        Map<String, Wiring> wired = resolver.resolve(ws, List.of(), LINUX);
        assertEquals(List.of("b"), workspaceProviders(wired.get("a")));
        assertEquals(List.of("a"), workspaceProviders(wired.get("b")));
        assertTrue(wired.get("a").unresolved().isEmpty());
    }

    @Test
    @DisplayName("visibility:=reexport carries transitive providers — the #11 graph property")
    void reexportClosure() {
        Map<String, BundleFacts> ws = Map.of(
            "a", bundle("a", "1.0.0", "b", null, null),
            "b", bundle("b", "1.0.0", "c;visibility:=reexport", null, "pkg.b"),
            "c", bundle("c", "1.0.0", null, null, "pkg.c"));
        Wiring a = resolver.resolve(ws, List.of(), LINUX).get("a");
        assertEquals(List.of("b", "c"), workspaceProviders(a),
            "A requires B; B reexports C; A must reach both");
    }

    @Test
    @DisplayName("a NON-reexported transitive requirement does NOT leak upward")
    void privateRequirementsStayPrivate() {
        Map<String, BundleFacts> ws = Map.of(
            "a", bundle("a", "1.0.0", "b", null, null),
            "b", bundle("b", "1.0.0", "c", null, "pkg.b"),
            "c", bundle("c", "1.0.0", null, null, "pkg.c"));
        Wiring a = resolver.resolve(ws, List.of(), LINUX).get("a");
        assertEquals(List.of("b"), workspaceProviders(a),
            "B requires C privately; A must NOT see C — over-resolution changes "
                + "search and refactoring answers");
    }

    @Test
    @DisplayName("Import-Package resolves from the ONE selection pass — never a losing jar")
    void importPackageAgreesWithArbitration() {
        Map<String, BundleFacts> ws = Map.of(
            "app", bundle("app", "1.0.0", null, "org.dup.api", null));
        Wiring app = resolver.resolve(ws,
            List.of(jar("dup", "1.0.0", Map.of("Export-Package", "org.dup.api")),
                    jar("dup", "2.0.0", Map.of("Export-Package", "org.dup.api"))),
            LINUX).get("app");
        assertEquals(List.of("dup-2.0.0.jar"), jarProviders(app),
            "the package answer must be the symbolic-name winner — the old pool's two "
                + "maps disagreed here");
    }

    @Test
    @DisplayName("fragments: the current platform's newest rides with its host; others never")
    void fragmentSelection() {
        Map<String, BundleFacts> ws = Map.of(
            "app", bundle("app", "1.0.0", "host", null, null));
        List<PoolBundle> pool = List.of(
            jar("host", "1.1.0", Map.of("Export-Package", "org.host.widgets")),
            jar("host.gtk.linux.x86_64", "1.0.0", Map.of("Fragment-Host", "host")),
            jar("host.gtk.linux.x86_64", "1.1.0", Map.of("Fragment-Host", "host",
                "Eclipse-PlatformFilter", "(& (osgi.os=linux) (osgi.ws=gtk) (osgi.arch=x86_64))")),
            jar("host.win32.win32.x86_64", "1.1.0", Map.of("Fragment-Host", "host",
                "Eclipse-PlatformFilter", "(& (osgi.os=win32) (osgi.ws=win32) (osgi.arch=x86_64))")));
        Wiring app = resolver.resolve(ws, pool, LINUX).get("app");
        assertEquals(List.of("host-1.1.0.jar", "host.gtk.linux.x86_64-1.1.0.jar"),
            jarProviders(app),
            "host + exactly the injected platform's newest fragment; win32 never leaks "
                + "onto a linux classpath");
    }

    @Test
    @DisplayName("a fragment whose host floor is unmet stays off the classpath")
    void fragmentHostFloor() {
        Map<String, BundleFacts> ws = Map.of(
            "app", bundle("app", "1.0.0", "host", null, null));
        List<PoolBundle> pool = List.of(
            jar("host", "1.0.0", Map.of()),
            jar("host.gtk.linux.x86_64", "1.0.0",
                Map.of("Fragment-Host", "host;bundle-version=\"2.0.0\"")));
        Wiring app = resolver.resolve(ws, pool, LINUX).get("app");
        assertEquals(List.of("host-1.0.0.jar"), jarProviders(app),
            "the fragment demands host >= 2.0.0 and the host is 1.0.0");
    }

    @Test
    @DisplayName("an optional miss is still REPORTED — 13.1's flagged default, pinned")
    void optionalMissesReport() {
        Map<String, BundleFacts> ws = Map.of(
            "app", bundle("app", "1.0.0", "maybe;resolution:=optional", null, null));
        Wiring app = resolver.resolve(ws, List.of(), LINUX).get("app");
        assertEquals(1, app.unresolved().size(),
            "excluding optional misses changes studio-visible numbers — that is "
                + "Harald's ruling to make, not a default to slip in");
    }

    @Test
    @DisplayName("duplicate symbolic names arbitrate deterministically — highest version")
    void duplicateArbitrationIsDeterministic() {
        Map<String, BundleFacts> ws = Map.of(
            "app", bundle("app", "1.0.0", "dup", null, null));
        // Same pool, both presentation orders — the answer must not move.
        List<PoolBundle> ab = List.of(jar("dup", "1.0.0", Map.of()), jar("dup", "2.0.0", Map.of()));
        List<PoolBundle> ba = List.of(jar("dup", "2.0.0", Map.of()), jar("dup", "1.0.0", Map.of()));
        assertEquals(jarProviders(resolver.resolve(ws, ab, LINUX).get("app")),
            jarProviders(resolver.resolve(ws, ba, LINUX).get("app")));
        assertEquals(List.of("dup-2.0.0.jar"),
            jarProviders(resolver.resolve(ws, ab, LINUX).get("app")));
    }

    @Test
    @DisplayName("a workspace sibling satisfies Import-Package before any pool jar")
    void importPackagePrefersWorkspace() {
        Map<String, BundleFacts> ws = Map.of(
            "app", bundle("app", "1.0.0", null, "org.shared.api", null),
            "provider", bundle("provider", "1.0.0", null, null, "org.shared.api"));
        Wiring app = resolver.resolve(ws,
            List.of(jar("ext", "9.0.0", Map.of("Export-Package", "org.shared.api"))),
            LINUX).get("app");
        assertEquals(List.of("provider"), workspaceProviders(app));
        assertTrue(jarProviders(app).isEmpty());
    }

    @Test
    @DisplayName("version comparison: segments numeric, qualifiers ignored, missing = 0")
    void versionComparison() {
        assertTrue(GraphWalkResolver.compareVersions("1.10.0", "1.9.0") > 0,
            "numeric, not lexicographic");
        assertEquals(0, GraphWalkResolver.compareVersions("1.0", "1.0.0"));
        assertTrue(GraphWalkResolver.compareVersions("2.0.0.qualifier", "2.0.0") == 0);
    }
}
