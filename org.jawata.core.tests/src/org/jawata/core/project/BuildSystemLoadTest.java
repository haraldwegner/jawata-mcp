package org.jawata.core.project;

import org.eclipse.core.resources.IProject;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.jawata.core.fixtures.TestProjectHelper;
import org.jawata.core.workspace.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28 (D-IMPORTER): every declared build system must LOAD, not merely be
 * DETECTED.
 *
 * <p>Detection was already covered — three Bazel tests wrote a marker file into
 * a temp directory and asserted the enum. Nothing exercised source discovery,
 * jar resolution, output exclusion or the language level, and the gap hid two
 * real defects that this class now pins:</p>
 *
 * <ol>
 *   <li><b>Bazel source roots were the package directory.</b> Bazel puts its
 *       {@code BUILD} file in the PACKAGE directory, so {@code java/com/example}
 *       was added as a source root while its classes declare
 *       {@code package com.example} — JDT then expected the default package and
 *       every class in every Bazel project failed with "The declared package
 *       ... does not match the expected package". Detection passed, roots were
 *       found, jars resolved, output was excluded, and the project was
 *       unusable.</li>
 *   <li><b>No project received its declared Java language level.</b> Nothing in
 *       the product set compiler compliance for ANY build system; every project
 *       silently took the workspace default. The v3.6.x round recorded the
 *       symptom without the cause — a project compiling at the wrong level while
 *       its pom declared another.</li>
 * </ol>
 *
 * <p>Each test states which of the five load properties it proves, so a
 * later reader can tell coverage from decoration.</p>
 */
class BuildSystemLoadTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ProjectImporter importer;
    private WorkspaceManager workspaceManager;

    @BeforeEach
    void setUp() throws Exception {
        importer = new ProjectImporter();
        workspaceManager = new WorkspaceManager();
        workspaceManager.initialize();
    }

    // ================= Bazel =================

    @Test
    @DisplayName("bazel: PROPERTY 1 — the source ROOT is found, not the package directory")
    void bazelSourceRootIsTheRootNotThePackageDir() throws Exception {
        IJavaProject jp = load("simple-bazel");
        List<String> roots = sourceRootPaths(jp);

        // java/com/example holds the BUILD file and classes declaring
        // package com.example, so the ROOT is java/ — the directory the package
        // path is relative to. Adding the package dir is the defect this pins.
        assertTrue(roots.stream().anyMatch(p -> p.endsWith("-java")),
            "expected a source root linked from java/, got " + roots);
        assertTrue(roots.stream().anyMatch(p -> p.endsWith("-javatests")),
            "expected a source root linked from javatests/, got " + roots);
        assertTrue(roots.stream().noneMatch(p -> p.contains("com-example")),
            "a Bazel PACKAGE directory was added as a source root — every class in "
                + "the project would land in the wrong package: " + roots);
    }

    @Test
    @DisplayName("bazel: PROPERTY 2 — dependency jars resolve from the output tree")
    void bazelResolvesJarsFromOutputTree() throws Exception {
        IJavaProject jp = load("simple-bazel");
        assertTrue(libraryPaths(jp).stream().anyMatch(p -> p.endsWith("libexample.jar")),
            "bazel-bin/libexample.jar did not reach the classpath: " + libraryPaths(jp));
    }

    @Test
    @DisplayName("bazel: PROPERTY 3 — the output tree is EXCLUDED from source discovery")
    void bazelOutputTreeIsExcluded() throws Exception {
        IJavaProject jp = load("simple-bazel");
        // The fixture plants a BUILD.bazel AND a .java inside bazel-bin/ — a scan
        // that fails to exclude bazel-* would take the bait.
        assertTrue(sourceRootPaths(jp).stream().noneMatch(p -> p.contains("bazel-bin")),
            "the Bazel OUTPUT tree was mounted as source: " + sourceRootPaths(jp));
    }

    @Test
    @DisplayName("bazel: PROPERTY 4 — the language level declared in javacopts is applied")
    void bazelAppliesJavacoptsLanguageLevel() throws Exception {
        Path fixture = helper.getFixturePath("simple-bazel");
        assertEquals(Optional.of("17"), ProjectImporter.readComplianceLevel(fixture),
            "javacopts = [\"--release\", \"17\"] was not read");

        IJavaProject jp = load("simple-bazel");
        assertEquals("17", jp.getOption(JavaCore.COMPILER_COMPLIANCE, true),
            "the declared level never reached the project — it silently took the default");
    }

    @Test
    @DisplayName("bazel: PROPERTY 5 — a real question about the code is answerable")
    void bazelLoadsUsableTypes() throws Exception {
        IJavaProject jp = load("simple-bazel");
        // The point of properties 1-4: after them, the model must actually work.
        assertTrue(jp.findType("com.example.Greeter") != null,
            "com.example.Greeter did not resolve — the project loaded but is not usable");
        assertTrue(jp.findType("com.example.GreeterFactory") != null,
            "com.example.GreeterFactory did not resolve");
    }

    // ================= Gradle =================

    @Test
    @DisplayName("gradle: source roots found, and the src/test convention separates them")
    void gradleLoadsBothSourceSets() throws Exception {
        IJavaProject jp = load("simple-gradle");
        List<String> roots = sourceRootPaths(jp);
        assertTrue(roots.stream().anyMatch(p -> p.contains("main-java")),
            "src/main/java not mounted: " + roots);
        assertTrue(roots.stream().anyMatch(p -> p.contains("test-java")),
            "src/test/java not mounted: " + roots);
    }

    @Test
    @DisplayName("gradle: sourceCompatibility is applied as the language level")
    void gradleAppliesSourceCompatibility() throws Exception {
        assertEquals(Optional.of("17"),
            ProjectImporter.readComplianceLevel(helper.getFixturePath("simple-gradle")),
            "sourceCompatibility = '17' was not read from build.gradle");
        assertEquals("17", load("simple-gradle").getOption(JavaCore.COMPILER_COMPLIANCE, true),
            "the level declared in build.gradle never reached the project");
    }

    @Test
    @DisplayName("gradle: a real question about the code is answerable")
    void gradleLoadsUsableTypes() throws Exception {
        assertTrue(load("simple-gradle").findType("com.example.Calculator") != null,
            "com.example.Calculator did not resolve");
    }

    // ================= plain Eclipse =================

    @Test
    @DisplayName("plain eclipse: .classpath src entries are mounted")
    void plainEclipseLoadsClasspathEntries() throws Exception {
        List<String> roots = sourceRootPaths(load("plain-eclipse"));
        assertTrue(roots.stream().anyMatch(p -> p.contains("main-java")),
            "the .classpath src entry for src/main/java was not mounted: " + roots);
        assertTrue(roots.stream().anyMatch(p -> p.contains("test-java")),
            "the .classpath src entry for src/test/java was not mounted: " + roots);
    }

    @Test
    @DisplayName("plain eclipse: the level in .settings is applied")
    void plainEclipseAppliesSettingsCompliance() throws Exception {
        assertEquals(Optional.of("17"),
            ProjectImporter.readComplianceLevel(helper.getFixturePath("plain-eclipse")),
            "org.eclipse.jdt.core.compiler.compliance=17 was not read from .settings");
        assertEquals("17", load("plain-eclipse").getOption(JavaCore.COMPILER_COMPLIANCE, true),
            "the level declared in .settings never reached the project");
    }

    @Test
    @DisplayName("plain eclipse: a real question about the code is answerable")
    void plainEclipseLoadsUsableTypes() throws Exception {
        assertTrue(load("plain-eclipse").findType("com.example.Inventory") != null,
            "com.example.Inventory did not resolve");
    }

    // ================= the language level, per build system =================

    @Test
    @DisplayName("maven: the declared language level is read from the pom")
    void mavenComplianceIsRead() throws Exception {
        assertEquals(Optional.of("21"),
            ProjectImporter.readComplianceLevel(helper.getFixturePath("simple-maven")),
            "maven.compiler.source=21 was not read from simple-maven's pom");
    }

    @Test
    @DisplayName("maven: source roots are mounted and types resolve")
    void mavenLoadsUsableTypes() throws Exception {
        IJavaProject jp = load("simple-maven");
        assertFalse(sourceRootPaths(jp).isEmpty(), "no source roots mounted for simple-maven");
        assertTrue(jp.findType("com.example.HelloWorld") != null,
            "com.example.HelloWorld did not resolve");
    }

    @Test
    @DisplayName("pde: a bundle's source root is mounted")
    void pdeLoadsSourceRoot() throws Exception {
        assertFalse(sourceRootPaths(load("pde-bundle-a")).isEmpty(),
            "no source roots mounted for the PDE bundle fixture");
    }

    @Test
    @DisplayName("a project declaring nothing keeps the default rather than a guess")
    void undeclaredLevelIsLeftAlone() throws Exception {
        // pde-bundle-a carries a MANIFEST only — no pom, no gradle, no settings.
        assertEquals(Optional.empty(),
            ProjectImporter.readComplianceLevel(helper.getFixturePath("pde-bundle-a")),
            "a level was invented for a project that declares none");
    }

    @Test
    @DisplayName("UNKNOWN: an unrecognised project still loads its sources and says what it is")
    void unknownBuildSystemStillLoads() throws Exception {
        // No pom, no gradle, no bazel marker, no MANIFEST, no .classpath —
        // detection must say UNKNOWN, and the project must still mount what it has
        // rather than loading empty and reporting success.
        Path fixture = helper.getFixturePath("plain-eclipse").getParent().resolve("unknown-layout");
        assertEquals(ProjectImporter.BuildSystem.UNKNOWN, importer.detectBuildSystem(fixture),
            "an unmarked project was not classified UNKNOWN");
        assertFalse(sourceRootPaths(load("unknown-layout")).isEmpty(),
            "an UNKNOWN project mounted no sources — it would load empty and look healthy");
    }

    // ================= helpers =================

    private IJavaProject load(String fixture) throws Exception {
        Path path = helper.getFixturePath(fixture);
        IProject project = workspaceManager.createLinkedProject("load-" + fixture, path);
        return importer.configureJavaProject(project, path, workspaceManager);
    }

    private List<String> sourceRootPaths(IJavaProject jp) throws Exception {
        return Arrays.stream(jp.getRawClasspath())
            .filter(e -> e.getEntryKind() == IClasspathEntry.CPE_SOURCE)
            .map(e -> e.getPath().toString())
            .toList();
    }

    private List<String> libraryPaths(IJavaProject jp) throws Exception {
        return Arrays.stream(jp.getRawClasspath())
            .filter(e -> e.getEntryKind() == IClasspathEntry.CPE_LIBRARY)
            .map(e -> e.getPath().toString())
            .toList();
    }
}
