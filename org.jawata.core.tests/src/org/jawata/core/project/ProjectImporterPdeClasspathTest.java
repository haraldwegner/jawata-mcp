package org.jawata.core.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28 (mcp#3) — the classpath losses carried out of v3.5.0.
 *
 * <p>v3.5.0 bound the JRE container, which was loss 1 of 4. This covers the
 * two that stayed open and were measured on a real Eclipse PDE tree
 * (macOS dogfood, 2026-07-26): required PROJECTS silently dropped, and the
 * JUnit container ignored so that {@code find_tests} reported zero tests in a
 * project with three test source folders.</p>
 */
class ProjectImporterPdeClasspathTest {

    /**
     * A {@code kind="src"} entry whose path starts with {@code /} is a project
     * reference, not a source folder. Reading it as a directory resolved it to
     * the absolute path itself, which never exists, so every project reference
     * vanished without a diagnostic.
     */
    @Test
    @DisplayName("readEclipseClasspath separates project references from source folders")
    void readEclipseClasspath_separatesProjectReferencesFromSourceFolders(@TempDir Path projectRoot)
            throws IOException {
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve(".classpath"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <classpath>
                  <classpathentry kind="src" path="src"/>
                  <classpathentry combineaccessrules="false" kind="src" path="/com.example.libs"/>
                  <classpathentry combineaccessrules="false" kind="src" path="/com.example.gateway"/>
                  <classpathentry kind="con" path="org.eclipse.pde.core.requiredPlugins"/>
                  <classpathentry kind="con" path="org.eclipse.jdt.junit.JUNIT_CONTAINER/4"/>
                  <classpathentry kind="output" path="bin"/>
                </classpath>
                """);

        ProjectImporter.ClasspathInfo info = ProjectImporter.readEclipseClasspath(projectRoot);

        assertEquals(List.of("com.example.libs", "com.example.gateway"), info.projectRefs(),
                "project references must be recognised by their leading '/', in declaration order");
        assertEquals(1, info.srcPaths().size(),
                "only the real source folder may remain a source path");
        assertTrue(info.srcPaths().get(0).endsWith("src"),
                "the real source folder must still resolve against the project root");
        assertTrue(info.containers().contains("org.eclipse.jdt.junit.JUNIT_CONTAINER/4"),
                "containers must be captured so the JUnit container can be expanded");
        assertTrue(info.containers().contains("org.eclipse.pde.core.requiredPlugins"),
                "the PDE container must be visible even though Require-Bundle does the resolving");
    }

    @Test
    @DisplayName("a .classpath with no project references yields none")
    void readEclipseClasspath_withoutProjectReferences(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve(".classpath"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <classpath>
                  <classpathentry kind="src" path="src/main/java"/>
                  <classpathentry kind="output" path="target/classes"/>
                </classpath>
                """);

        ProjectImporter.ClasspathInfo info = ProjectImporter.readEclipseClasspath(projectRoot);

        assertTrue(info.projectRefs().isEmpty(), "no leading-'/' entries means no project references");
        assertEquals(1, info.srcPaths().size(), "the ordinary source folder is untouched");
    }

    @Test
    @DisplayName("JUNIT_CONTAINER/4 asks for the JUnit 4 bundles")
    void junitContainerBundles_version4() {
        List<String> bundles = ProjectImporter
                .junitContainerBundles(List.of("org.eclipse.jdt.junit.JUNIT_CONTAINER/4"));

        assertTrue(bundles.contains("org.junit"), "JUnit 4 ships as the org.junit bundle");
        assertFalse(bundles.contains("org.junit.jupiter.api"),
                "a JUnit 4 container must not drag in the Jupiter line");
    }

    @Test
    @DisplayName("JUNIT_CONTAINER/5 asks for the Jupiter bundles")
    void junitContainerBundles_version5() {
        List<String> bundles = ProjectImporter
                .junitContainerBundles(List.of("org.eclipse.jdt.junit.JUNIT_CONTAINER/5"));

        assertTrue(bundles.contains("org.junit.jupiter.api"), "Jupiter's api bundle is required");
        assertTrue(bundles.contains("org.junit.platform.commons"), "the platform bundles come with it");
        assertFalse(bundles.contains("org.junit"), "the JUnit 4 bundle is not part of the Jupiter line");
    }

    /**
     * An unversioned or unknown suffix must ask for BOTH lines. A superfluous
     * jar on the classpath is harmless; a missing one silently breaks every
     * test lookup, which is the failure this whole fix exists to end.
     */
    @Test
    @DisplayName("an unrecognised JUNIT_CONTAINER version asks for both lines rather than guessing")
    void junitContainerBundles_unknownVersionTakesBoth() {
        List<String> bundles = ProjectImporter
                .junitContainerBundles(List.of("org.eclipse.jdt.junit.JUNIT_CONTAINER"));

        assertTrue(bundles.contains("org.junit"), "the JUnit 4 line must be covered");
        assertTrue(bundles.contains("org.junit.jupiter.api"), "the Jupiter line must be covered");
    }

    @Test
    @DisplayName("containers other than the JUnit one contribute nothing")
    void junitContainerBundles_ignoresOtherContainers() {
        List<String> bundles = ProjectImporter.junitContainerBundles(List.of(
                "org.eclipse.pde.core.requiredPlugins",
                "org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-21"));

        assertTrue(bundles.isEmpty(), "only the JUnit container expands here");
    }

    @Test
    @DisplayName("two JUnit containers do not duplicate bundles")
    void junitContainerBundles_deduplicates() {
        List<String> bundles = ProjectImporter.junitContainerBundles(List.of(
                "org.eclipse.jdt.junit.JUNIT_CONTAINER/4",
                "org.eclipse.jdt.junit.JUNIT_CONTAINER/4"));

        assertEquals(bundles.size(), bundles.stream().distinct().count(),
                "duplicate containers must not produce duplicate classpath entries");
    }
}
