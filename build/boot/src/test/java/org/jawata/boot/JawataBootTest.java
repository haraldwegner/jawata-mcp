package org.jawata.boot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 22d follow-up (Harald 2026-07-12: "We have a class which is not tested but
 * GitHub shall run?") — the boot module IS the CI test harness since the
 * switch; harness code gets product discipline. Covers the pure-JVM seams:
 * bundle-list assembly + start markers, session isolation, test discovery +
 * filter. The in-framework path is exercised end-to-end by every -runTests
 * invocation (CI + the filtered smoke).
 */
class JawataBootTest {

    @TempDir
    Path tmp;

    private String savedRoot;

    @BeforeEach
    void saveProps() {
        savedRoot = System.getProperty("jawata.workspace.root");
        System.clearProperty("jawata.workspace.root");
    }

    @AfterEach
    void restoreProps() {
        if (savedRoot == null) System.clearProperty("jawata.workspace.root");
        else System.setProperty("jawata.workspace.root", savedRoot);
    }

    // ------------------------------------------------------------ bundle list

    @Test
    @DisplayName("osgiBundlesList: reference URLs, sorted, start markers on the right bundles")
    void bundleList_markersAndOrder() throws Exception {
        Path dir = tmp.resolve("bundles");
        Files.createDirectories(dir);
        for (String n : List.of(
                "org.eclipse.core.runtime-3.34.200.jar",
                "org.apache.felix.scr-2.2.18.jar",
                "org.eclipse.jdt.core-3.46.0.jar",
                "not-a-jar.txt")) {
            Files.createFile(dir.resolve(n));
        }
        String list = JawataBoot.osgiBundlesList(dir);
        String[] entries = list.split(",");
        assertEquals(3, entries.length, "only jars enter the list");
        assertTrue(entries[0].endsWith("org.apache.felix.scr-2.2.18.jar@1:start"),
            "scr gets start level 1: " + entries[0]);
        assertTrue(entries[1].endsWith("org.eclipse.core.runtime-3.34.200.jar@start"),
            "core.runtime gets @start: " + entries[1]);
        assertTrue(entries[2].endsWith("org.eclipse.jdt.core-3.46.0.jar"),
            "plain bundles get no marker: " + entries[2]);
        for (String e : entries) assertTrue(e.startsWith("reference:file:"));
    }

    @Test
    @DisplayName("osgiBundlesList: empty dir is a hard error, not a silent empty framework")
    void bundleList_emptyDirThrows() throws Exception {
        Path dir = tmp.resolve("empty");
        Files.createDirectories(dir);
        try {
            JawataBoot.osgiBundlesList(dir);
            throw new AssertionError("must throw on empty bundles dir");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("no bundles"));
        }
    }

    // ------------------------------------------------------- session isolation

    @Test
    @DisplayName("isolateSession: -data gets a uuid8 subdir (created), original published as workspace.root")
    void session_isolatesData() throws Exception {
        Path base = tmp.resolve("ws");
        Files.createDirectories(base);
        JawataBoot.SessionArgs r = JawataBoot.isolateSession(
            List.of("-data", base.toString(), "-port", "8800"));
        assertNotNull(r.sessionPath, "session dir must be created");
        assertTrue(Files.isDirectory(r.sessionPath));
        assertEquals(base, r.sessionPath.getParent());
        assertEquals(8, r.sessionPath.getFileName().toString().length(), "uuid8 subdir");
        assertEquals(List.of("-data", r.sessionPath.toString(), "-port", "8800"), r.rewrittenArgs);
        assertEquals(base.toAbsolutePath().toString(), System.getProperty("jawata.workspace.root"),
            "the ORIGINAL base is published, not the session subdir");
    }

    @Test
    @DisplayName("isolateSession: pre-set workspace.root is never overwritten")
    void session_respectsExistingRoot() throws Exception {
        System.setProperty("jawata.workspace.root", "/pre/set");
        Path base = tmp.resolve("ws2");
        Files.createDirectories(base);
        JawataBoot.isolateSession(List.of("-data", base.toString()));
        assertEquals("/pre/set", System.getProperty("jawata.workspace.root"));
    }

    @Test
    @DisplayName("isolateSession: no -data → no session, args untouched")
    void session_withoutData() throws Exception {
        JawataBoot.SessionArgs r = JawataBoot.isolateSession(List.of("-port", "9999"));
        assertNull(r.sessionPath);
        assertEquals(List.of("-port", "9999"), r.rewrittenArgs);
    }

    // ---------------------------------------------------------- test discovery

    private Path jarWith(String name, List<String> entries) throws Exception {
        Path jar = tmp.resolve(name);
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
            for (String e : entries) {
                jos.putNextEntry(new JarEntry(e));
                jos.closeEntry();
            }
        }
        return jar;
    }

    @Test
    @DisplayName("discoverTestClasses: org.jawata.* jars only, *Test.class only, no inner classes, sorted")
    void discovery_scansAndFilters() throws Exception {
        Path dir = tmp.resolve("test-bundles");
        Files.createDirectories(dir);
        jarWith("test-bundles/org.jawata.mcp.tests-1.jar", List.of(
            "org/jawata/mcp/ZToolTest.class",
            "org/jawata/mcp/AToolTest.class",
            "org/jawata/mcp/AToolTest$Inner.class",
            "org/jawata/mcp/Helper.class"));
        jarWith("test-bundles/junit-jupiter-api-5.11.4.jar", List.of(
            "org/junit/jupiter/api/SomethingTest.class"));
        List<String> all = JawataBoot.discoverTestClasses(dir, null);
        assertEquals(List.of("org.jawata.mcp.AToolTest", "org.jawata.mcp.ZToolTest"), all,
            "sorted, inner + non-Test + foreign-jar entries excluded");
        List<String> filtered = JawataBoot.discoverTestClasses(dir, "ATool");
        assertEquals(List.of("org.jawata.mcp.AToolTest"), filtered);
        assertFalse(JawataBoot.discoverTestClasses(dir, "NoSuch").iterator().hasNext());
    }

    // ------------------------------------------------- stale-dist guard (issue #1)
    // A version bump left the previous org.jawata.*.jar beside the new one; the
    // runner loaded the STALE class and reported green while the new tests never
    // ran (observed live 2026-07-21). An ambiguous classpath must be an ERROR,
    // never a coin flip.

    @Test
    @DisplayName("issue #1: two versions of one test bundle refuse discovery, naming both jars")
    void discovery_refusesTwoVersionsOfOneBundle() throws Exception {
        Path dir = tmp.resolve("test-bundles");
        Files.createDirectories(dir);
        jarWith("test-bundles/org.jawata.mcp.tests-3.2.1.jar",
            List.of("org/jawata/mcp/OldTest.class"));
        jarWith("test-bundles/org.jawata.mcp.tests-3.3.0.jar",
            List.of("org/jawata/mcp/NewTest.class"));
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, () -> JawataBoot.discoverTestClasses(dir, null),
            "no FQCN overlap needed — the version duplication alone is the hazard");
        assertTrue(e.getMessage().contains("org.jawata.mcp.tests-3.2.1.jar")
                && e.getMessage().contains("org.jawata.mcp.tests-3.3.0.jar"),
            "the refusal names BOTH offending jars: " + e.getMessage());
    }

    @Test
    @DisplayName("issue #1: the same test class in two differently-named bundles refuses discovery")
    void discovery_refusesDuplicateClassAcrossBundles() throws Exception {
        Path dir = tmp.resolve("test-bundles");
        Files.createDirectories(dir);
        // Distinct base names (a hand-copied jar), so only the FQCN check can catch it.
        jarWith("test-bundles/org.jawata.mcp.tests-3.3.0.jar",
            List.of("org/jawata/mcp/DupTest.class"));
        jarWith("test-bundles/org.jawata.mcp.tests.backup-1.0.0.jar",
            List.of("org/jawata/mcp/DupTest.class"));
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, () -> JawataBoot.discoverTestClasses(dir, null));
        assertTrue(e.getMessage().contains("org.jawata.mcp.DupTest"),
            "the refusal names the ambiguous class: " + e.getMessage());
    }

    @Test
    @DisplayName("issue #1: the boot classpath assembly refuses two versions of one org.jawata bundle")
    void bundleList_refusesTwoVersionsOfOneBundle() throws Exception {
        Path dir = tmp.resolve("bundles");
        Files.createDirectories(dir);
        // The RESIDENT boot reads the same dirs — a stale production bundle is
        // exactly as dangerous as a stale test bundle.
        jarWith("bundles/org.jawata.core-3.2.1.jar", List.of("x/A.class"));
        jarWith("bundles/org.jawata.core-3.3.0.jar", List.of("x/B.class"));
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, () -> JawataBoot.osgiBundlesList(dir));
        assertTrue(e.getMessage().contains("org.jawata.core-3.2.1.jar")
                && e.getMessage().contains("org.jawata.core-3.3.0.jar"),
            "the refusal names BOTH offending jars: " + e.getMessage());
    }

    @Test
    @DisplayName("issue #1: distinct org.jawata bundles at one version each still boot normally")
    void bundleList_acceptsDistinctBundles() throws Exception {
        Path dir = tmp.resolve("bundles");
        Files.createDirectories(dir);
        jarWith("bundles/org.jawata.core-3.3.0.jar", List.of("x/A.class"));
        jarWith("bundles/org.jawata.mcp-3.3.0.jar", List.of("x/B.class"));
        jarWith("bundles/org.eclipse.jdt.core.compiler.batch_3.46.0.v20260528-0407-jawata5188.jar",
            List.of("x/C.class"));
        assertNotNull(JawataBoot.osgiBundlesList(dir),
            "core + mcp + a non-jawata carried bundle is the NORMAL dist — never refused");
    }

    // ------------------------------------------- what a boot leaves in the temp folder

    /**
     * 2026-10-24: 1,324 {@code jawata-test-ws<N>} folders (34.7 GB, 404 of them over 1 MB, one 340 MB)
     * and 1,782 {@code jawata-boot-config<N>} folders (4.4 MB each) sat in /tmp. A test boot made its
     * workspace and its OSGi configuration folder and removed neither; only a run through
     * build/run-suite.sh swept them, a single class run by hand never did, and the resident server
     * left its configuration folder on every start.
     */
    @Test
    @DisplayName("BootDirs: closing removes the configuration and workspace folders and what they hold")
    void bootDirs_closeRemovesBoth() throws Exception {
        Path parent = tmp.resolve("tmp-root");
        JawataBoot.BootDirs dirs = JawataBoot.BootDirs.open(parent, true);
        Files.createDirectories(dirs.configuration.resolve("org.eclipse.osgi/bundles/1"));
        Files.writeString(dirs.configuration.resolve("org.eclipse.osgi/bundles/1/data"), "x");
        Files.createDirectories(dirs.instance.resolve(".metadata/.plugins"));
        Files.writeString(dirs.instance.resolve(".metadata/.plugins/state"), "x");
        Files.createDirectories(dirs.instance.resolve("jawata-simple-maven-1/target"));
        Files.writeString(dirs.instance.resolve("jawata-simple-maven-1/target/out"), "x");

        dirs.close();

        assertFalse(Files.exists(dirs.configuration), "the configuration folder is still there");
        assertFalse(Files.exists(dirs.instance), "the workspace folder is still there");
        try (java.util.stream.Stream<Path> left = Files.list(parent)) {
            assertEquals(List.of(), left.toList(), "the boot left something in its temp folder");
        }
    }

    @Test
    @DisplayName("BootDirs: a serving boot has a configuration folder and no workspace folder")
    void bootDirs_servingHasNoInstance() throws Exception {
        Path parent = tmp.resolve("tmp-root-serve");
        JawataBoot.BootDirs dirs = JawataBoot.BootDirs.open(parent, false);
        assertNull(dirs.instance, "serving takes its workspace from -data, not from the temp folder");
        assertTrue(Files.isDirectory(dirs.configuration));
        dirs.close();
        assertFalse(Files.exists(dirs.configuration));
    }

    @Test
    @DisplayName("BootDirs: closing twice is harmless (the shutdown hook and the finally both close)")
    void bootDirs_closeTwice() throws Exception {
        JawataBoot.BootDirs dirs = JawataBoot.BootDirs.open(tmp.resolve("tmp-root-twice"), true);
        dirs.close();
        dirs.close();
        assertFalse(Files.exists(dirs.configuration));
    }

    @Test
    @DisplayName("baseProps: the configuration area is the BootDirs' folder — it makes no folder of its own")
    void baseProps_usesTheBootDirs() throws Exception {
        Path bundles = tmp.resolve("bundles-for-props");
        Files.createDirectories(bundles);
        Files.createFile(bundles.resolve("org.eclipse.core.runtime-3.34.200.jar"));
        JawataBoot.BootDirs dirs = JawataBoot.BootDirs.open(tmp.resolve("tmp-root-props"), false);
        try {
            java.util.Map<String, String> props = JawataBoot.baseProps(tmp, bundles, null, dirs);
            assertEquals(dirs.configuration.toString(), props.get("osgi.configuration.area"),
                "baseProps made its own configuration folder, which nothing closes");
        } finally {
            dirs.close();
        }
    }
}
