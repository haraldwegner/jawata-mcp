package org.jawata.core.project;

import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.jawata.core.JdtServiceImpl;
import org.jawata.core.LoadedProject;
import org.jawata.core.fixtures.TestJars;
import org.jawata.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 11.0's RED battery — the behaviours the workspace resolve phase must
 * deliver, written against TODAY'S code and recorded failing (plan Stages
 * 11–13; each test names the checkpoint that enables it).
 *
 * <p>These pin the plan's measured causes: order-dependent sibling
 * resolution (clicktrader's 431), the flat pool blind to the nested Tycho
 * layout, unread {@code Bundle-ClassPath} and {@code Fragment-Host}, the
 * pool's two-maps precedence disagreement, discarded manifest directives,
 * and cycles being unwireable.</p>
 */
class WorkspaceResolveRedTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static LoadedProject byFixture(JdtServiceImpl service, String fixtureName) {
        for (LoadedProject p : service.allProjects()) {
            if (p.projectRoot().getFileName().toString().equals(fixtureName)) {
                return p;
            }
        }
        throw new AssertionError("fixture '" + fixtureName + "' not loaded; have: "
            + service.allProjects().stream().map(LoadedProject::projectKey).toList());
    }

    private static List<String> entryPaths(IJavaProject jp, int kind) throws Exception {
        List<String> out = new ArrayList<>();
        for (IClasspathEntry e : jp.getRawClasspath()) {
            if (e.getEntryKind() == kind) {
                out.add(e.getPath().toString());
            }
        }
        return out;
    }

    /** Write the two nested jars the pde-lib-container fixture declares. */
    private static void generateContainerJars(Path containerCopy) throws Exception {
        TestJars.classJar(containerCopy.resolve("lib/first.jar"),
            "com.example.nested.one.FromFirstJar",
            "package com.example.nested.one;\npublic class FromFirstJar {\n"
                + "    public String label() { return \"first\"; }\n}\n",
            Map.of());
        TestJars.classJar(containerCopy.resolve("lib/second.jar"),
            "com.example.nested.two.FromSecondJar",
            "package com.example.nested.two;\npublic class FromSecondJar {\n"
                + "    public String label() { return \"second\"; }\n}\n",
            Map.of());
    }

    // ------------------------------------------------------------------
    // enabled at C12.1 — the behaviour change
    // ------------------------------------------------------------------

    /**
     * THE CLICKTRADER SHAPE. The dependent loads FIRST, its provider second —
     * on the live workspace that produced 431 errors, every one
     * {@code import com.jats2.model cannot be resolved}, with both required
     * siblings LOADED. Order must not matter.
     */
    @Test
    @DisplayName("RED until C12.1: a Require-Bundle on a sibling loaded LATER still wires")
    void requireBundle_resolvesWhenDependentLoadsFirst() throws Exception {
        // Deliberately reversed: A (requires B) before B.
        JdtServiceImpl service = helper.loadWorkspaceCopy("pde-bundle-a", "pde-bundle-b");
        LoadedProject a = byFixture(service, "pde-bundle-a");
        List<String> projects = entryPaths(a.javaProject(), IClasspathEntry.CPE_PROJECT);
        assertEquals(1, projects.size(),
            "A requires B and B is loaded — a project entry must exist regardless of "
                + "load order; got project entries: " + projects);
    }

    /** Removing a provider must retro-unwire its dependents (pool failover or honest row). */
    @Test
    @DisplayName("RED until C12.1: removing a provider retro-unwires its dependents")
    void removeProvider_failsOverToPoolOrUnresolved() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy("pde-bundle-b", "pde-bundle-a");
        LoadedProject a = byFixture(service, "pde-bundle-a");
        LoadedProject b = byFixture(service, "pde-bundle-b");
        assertEquals(1, entryPaths(a.javaProject(), IClasspathEntry.CPE_PROJECT).size(),
            "precondition: loaded provider-first, so A wires B today");

        service.removeProject(b.projectKey());

        LoadedProject aAfter = service.getProject(a.projectKey()).orElseThrow();
        List<String> stale = entryPaths(aAfter.javaProject(), IClasspathEntry.CPE_PROJECT);
        assertTrue(stale.isEmpty(),
            "B is gone; A must not keep a project entry to a deleted project: " + stale);
        assertTrue(
            aAfter.unresolved().stream().anyMatch(u -> u.name().contains("org.jawata.fixture.b")),
            "and the miss must be an HONEST row, not silence: " + aAfter.unresolved());
    }

    /** The studio reads {@code LoadedProject.unresolved()}; adding the provider must refresh it. */
    @Test
    @DisplayName("RED until C12.1: adding a provider refreshes the dependent's unresolved count")
    void unresolvedCount_refreshesAfterProviderAdded() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy("pde-bundle-a");
        LoadedProject a = byFixture(service, "pde-bundle-a");
        assertTrue(
            a.unresolved().stream().anyMatch(u -> u.name().contains("org.jawata.fixture.b")),
            "precondition: B absent, so A reports it unresolved: " + a.unresolved());

        service.addProject(helper.copyFixture("pde-bundle-b"));

        LoadedProject aAfter = service.getProject(a.projectKey()).orElseThrow();
        assertTrue(
            aAfter.unresolved().stream().noneMatch(u -> u.name().contains("org.jawata.fixture.b")),
            "B is now loaded; the stale row must be gone (the record must be REPUBLISHED, "
                + "not snapshotted forever): " + aAfter.unresolved());
        org.junit.jupiter.api.Assertions.assertSame(a.searchService(), aAfter.searchService(),
            "republication copies the record — the SAME SearchService instance, never a "
                + "rebuilt one (C12.1 audit: a new SearchService per re-resolve left every "
                + "test green while discarding the index)");
    }

    /** Cycles are legal in the dev-time model; JDT's default makes them a hard ERROR. */
    @Test
    @DisplayName("RED until C12.1: a Require-Bundle cycle wires both ways with no ERROR marker")
    void cycle_bothDirectionsWire_noErrorMarker() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy("pde-cycle-a", "pde-cycle-b");
        LoadedProject a = byFixture(service, "pde-cycle-a");
        LoadedProject b = byFixture(service, "pde-cycle-b");
        assertEquals(1, entryPaths(a.javaProject(), IClasspathEntry.CPE_PROJECT).size(),
            "a -> b must wire");
        assertEquals(1, entryPaths(b.javaProject(), IClasspathEntry.CPE_PROJECT).size(),
            "b -> a must wire");
        for (LoadedProject p : List.of(a, b)) {
            p.javaProject().getProject().build(
                org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, null);
            for (IMarker m : p.javaProject().getProject().findMarkers(
                    IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)) {
                int sev = m.getAttribute(IMarker.SEVERITY, -1);
                String msg = String.valueOf(m.getAttribute(IMarker.MESSAGE, ""));
                assertTrue(sev != IMarker.SEVERITY_ERROR || !msg.toLowerCase().contains("cycle"),
                    p.projectKey() + " carries a cycle ERROR: " + msg);
            }
        }
    }

    // ------------------------------------------------------------------
    // enabled at C12.2 — the nested pool, fragments, precedence
    // ------------------------------------------------------------------

    /**
     * The Tycho cache layout ({@code name/version/jar}) with a host + its
     * platform fragments. The flat indexer sees NOTHING here today, and SWT's
     * classes live in the FRAGMENT (the live workspace's #11 case).
     */
    @Test
    @DisplayName("RED until C12.2: a nested pool resolves host + CURRENT-platform fragment")
    void nestedPool_hostPlusCurrentPlatformFragmentResolve(@TempDir Path pool) throws Exception {
        TestJars.nestedPoolBundle(pool, "org.example.host", "1.1.0",
            Map.of("Export-Package", "org.example.widgets"));
        TestJars.nestedPoolBundle(pool, "org.example.host.gtk.linux.x86_64", "1.0.0",
            Map.of("Fragment-Host", "org.example.host"));
        TestJars.nestedPoolBundle(pool, "org.example.host.gtk.linux.x86_64", "1.1.0",
            Map.of("Fragment-Host", "org.example.host",
                   "Eclipse-PlatformFilter", "(& (osgi.os=linux) (osgi.ws=gtk) (osgi.arch=x86_64))"));
        TestJars.nestedPoolBundle(pool, "org.example.host.win32.win32.x86_64", "1.1.0",
            Map.of("Fragment-Host", "org.example.host",
                   "Eclipse-PlatformFilter", "(& (osgi.os=win32) (osgi.ws=win32) (osgi.arch=x86_64))"));

        String before = System.getProperty("jawata.bundle.pools");
        System.setProperty("jawata.bundle.pools", pool.toString());
        try {
            Path project = helper.getTempDirectory().resolve("needs-host");
            Files.createDirectories(project.resolve("META-INF"));
            Files.createDirectories(project.resolve("src"));
            Files.writeString(project.resolve("META-INF/MANIFEST.MF"),
                "Manifest-Version: 1.0\nBundle-ManifestVersion: 2\n"
                    + "Bundle-SymbolicName: org.example.needshost\nBundle-Version: 1.0.0\n"
                    + "Require-Bundle: org.example.host\n");
            JdtServiceImpl service = new JdtServiceImpl();
            try {
                service.loadProject(project);
                LoadedProject p = service.allProjects().iterator().next();
                List<String> libs = entryPaths(p.javaProject(), IClasspathEntry.CPE_LIBRARY);
                assertTrue(libs.stream().anyMatch(l -> l.contains("org.example.host-1.1.0")),
                    "the HOST must resolve from the nested pool: " + libs);
                long fragments = libs.stream()
                    .filter(l -> l.contains("org.example.host.gtk.linux.x86_64-1.1.0")).count();
                assertEquals(1, fragments,
                    "exactly the CURRENT platform's newest fragment rides with the host "
                        + "(gtk/linux/x86_64 on this machine — the triple is injected in "
                        + "production, and win32 must NOT appear): " + libs);
                assertTrue(libs.stream().noneMatch(l -> l.contains("win32")),
                    "the win32 fragment must not leak onto a linux classpath: " + libs);
            } finally {
                service.dispose();
            }
        } finally {
            if (before == null) {
                System.clearProperty("jawata.bundle.pools");
            } else {
                System.setProperty("jawata.bundle.pools", before);
            }
        }
    }

    /**
     * The pool's two maps disagree today: {@code bySymbolicName} merges by
     * highest version while {@code byExportedPackage.putIfAbsent} keeps the
     * first jar seen — one bundle, two different jars.
     */
    @Test
    @DisplayName("RED until C12.2: the exported-package map agrees with the symbolic-name winner")
    void exportedPackageMap_agreesWithSymbolicNameWinner(@TempDir Path pool) throws Exception {
        // FLAT layout on purpose — the flat indexer must see both jars so the
        // disagreement between its two maps is observable in isolation.
        Map<String, String> exports = Map.of("Export-Package", "org.example.api");
        Map<String, String> v1 = new java.util.LinkedHashMap<>(exports);
        v1.put("Bundle-ManifestVersion", "2");
        v1.put("Bundle-SymbolicName", "org.example.dup");
        v1.put("Bundle-Version", "1.0.0");
        Map<String, String> v2 = new java.util.LinkedHashMap<>(exports);
        v2.put("Bundle-ManifestVersion", "2");
        v2.put("Bundle-SymbolicName", "org.example.dup");
        v2.put("Bundle-Version", "2.0.0");
        // Sorted file order puts 1.0.0 first, so putIfAbsent keeps the LOSER.
        TestJars.bundleJar(pool.resolve("a-org.example.dup-1.0.0.jar"), v1);
        TestJars.bundleJar(pool.resolve("b-org.example.dup-2.0.0.jar"), v2);

        ExternalBundlePool indexed = ExternalBundlePool.index(List.of(pool));
        Path winner = indexed.bundleJar("org.example.dup").orElseThrow();
        // The package view is the RESOLVER's selection since 12.2 — the pool
        // no longer carries a second map that could disagree. Resolve a
        // consumer importing the package and assert the wired jar IS the
        // symbolic-name winner.
        org.jawata.core.resolve.BundleFacts consumer = new org.jawata.core.resolve.BundleFacts(
            "org.example.consumer", java.util.Optional.of("1.0.0"), List.of(),
            List.of("org.example.api"), List.of(), java.util.Optional.empty(),
            List.of(), java.util.Optional.empty());
        org.jawata.core.resolve.PlatformResolver.Wiring wiring =
            new org.jawata.core.resolve.GraphWalkResolver()
                .resolve(java.util.Map.of("org.example.consumer", consumer),
                    indexed.poolBundles(),
                    new org.jawata.core.resolve.PlatformResolver.Platform("linux", "gtk", "x86_64"))
                .get("org.example.consumer");
        List<Path> jars = wiring.providers().stream()
            .map(pr -> pr.jar().orElseThrow()).toList();
        assertEquals(List.of(winner), jars,
            "one bundle, one jar: the package view must answer with the same jar the "
                + "symbolic-name arbitration chose");
    }

    // ------------------------------------------------------------------
    // enabled at C12.3 — Bundle-ClassPath
    // ------------------------------------------------------------------

    /** The com.jats2.libs shape: a lib-container bundle consumed by a sibling. */
    @Disabled("RED, recorded at C11.0 — enabled at C12.3")
    @Test
    @DisplayName("RED until C12.3: a container's Bundle-ClassPath jars reach the dependent")
    void libContainer_nestedJarsOnDependentClasspath() throws Exception {
        Path container = helper.copyFixture("pde-lib-container");
        generateContainerJars(container);
        Path consumer = helper.copyFixture("pde-lib-consumer");

        JdtServiceImpl service = new JdtServiceImpl();
        try {
            service.loadProject(container);
            service.addProject(consumer);
            LoadedProject cont = byFixture(service, "pde-lib-container");
            List<String> libs = entryPaths(cont.javaProject(), IClasspathEntry.CPE_LIBRARY);
            long first = libs.stream().filter(l -> l.endsWith("first.jar")).count();
            long second = libs.stream().filter(l -> l.endsWith("second.jar")).count();
            assertEquals(1, first,
                "Bundle-ClassPath's first.jar must appear EXACTLY once — it is also named "
                    + "by the .classpath, and the overlap must dedupe, not duplicate or drop: "
                    + libs);
            assertEquals(1, second, "second.jar comes only from Bundle-ClassPath: " + libs);
        } finally {
            service.dispose();
        }
    }

    // ------------------------------------------------------------------
    // enabled at C13.1 — re-export closure
    // ------------------------------------------------------------------

    /** A requires B; B requires C with {@code visibility:=reexport}; A must reach C. */
    @Disabled("RED, recorded at C11.0 — enabled at C13.1")
    @Test
    @DisplayName("RED until C13.1: visibility:=reexport carries a transitive provider")
    void reexportClosure_transitiveTypeResolves() throws Exception {
        JdtServiceImpl service =
            helper.loadWorkspaceCopy("pde-reexport-c", "pde-reexport-b", "pde-reexport-a");
        LoadedProject a = byFixture(service, "pde-reexport-a");
        List<String> projects = entryPaths(a.javaProject(), IClasspathEntry.CPE_PROJECT);
        assertEquals(2, projects.size(),
            "A requires B, and B reexports C — A's classpath must reach BOTH: " + projects);
    }

    // ------------------------------------------------------------------
    // enabled at C11.1 — the parser
    // ------------------------------------------------------------------

    /** The naive split(",") breaks a quoted version range into phantom requirements. */
    @Test
    @DisplayName("RED until C11.1: a quoted version range is ONE requirement, not two")
    void requireBundle_quotedVersionRange_parsesAsOneEntry(@TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("META-INF"));
        Files.writeString(project.resolve("META-INF/MANIFEST.MF"),
            "Manifest-Version: 1.0\nBundle-ManifestVersion: 2\n"
                + "Bundle-SymbolicName: org.example.ranged\nBundle-Version: 1.0.0\n"
                + "Require-Bundle: org.example.pinned;bundle-version=\"[1.0.0,2.0.0)\",\n"
                + " org.example.plain\n");
        List<String> required = org.jawata.core.resolve.BundleFacts.of(project).orElseThrow()
            .requiredBundles().stream()
            .map(org.jawata.core.resolve.OsgiHeaders.Requirement::name)
            .toList();
        assertEquals(List.of("org.example.pinned", "org.example.plain"), required,
            "the comma INSIDE the quoted range is not a separator; today's naive "
                + "split(\",\") manufactures a third, phantom requirement");
    }
}
