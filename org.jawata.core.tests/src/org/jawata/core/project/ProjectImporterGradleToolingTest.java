package org.jawata.core.project;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sprint 11 Phase C — Gradle Tooling API integration.
 *
 * <p>Each test writes a tiny ad-hoc Gradle project to a {@code @TempDir}
 * and asks {@link ProjectImporter#readGradleProjectModel(Path)} to extract
 * the source roots and resolved classpath via the embedded
 * {@code gradle-tooling-api} jar (loaded into {@code org.jawata.core}'s
 * classloader through the Bundle-ClassPath header).</p>
 *
 * <p>The Tooling API needs a Gradle distribution at runtime. On first run
 * it downloads one (~150 MB into {@code ~/.gradle/caches/dists}); subsequent
 * runs use the cache. CI environments without network access can set the
 * system property {@code jawata.skip.gradle=true} to skip these tests.</p>
 */
class ProjectImporterGradleToolingTest {

    /**
     * Allow CI / fast iteration to skip the network-dependent Tooling API tests.
     * Set {@code -Djawata.skip.gradle=true} on the surefire command line.
     */
    private static void assumeGradleAvailable() {
        assumeTrue(
            !"true".equalsIgnoreCase(System.getProperty("jawata.skip.gradle", "false")),
            "Gradle Tooling API tests skipped via jawata.skip.gradle=true"
        );
    }

    /**
     * Resolve the {@code @TempDir} through any symlinks before the test uses it.
     *
     * <p>Sprint 28a: these three tests failed on macOS the first time the CI matrix
     * ever ran. On macOS {@code /var} is a symlink to {@code /private/var}, and
     * JUnit hands out a temp directory spelled the short way while the Gradle
     * Tooling API reports the canonical one. Both sides then called
     * {@link Path#normalize()}, which collapses {@code .} and {@code ..} and
     * <em>never</em> resolves a symlink — so the assertions compared two spellings
     * of the same directory and declared them different.</p>
     *
     * <p>Canonicalising once, here, removes the symlink from the comparison so each
     * test asserts what its name says: that the Tooling API reports the right source
     * roots and classpath entries.</p>
     *
     * <p><b>Not covered, deliberately:</b> what the importer should return when a
     * <em>caller</em> hands it a project path that runs through a symlink. That is a
     * real question — the importer passes Gradle's canonical answer straight through,
     * so such a caller gets source roots spelled differently from the root it supplied
     * — but it is a product question, not this test's subject, and it is recorded
     * rather than silently folded in here.</p>
     */
    private static Path canonicalRoot(Path tempDir) throws IOException {
        return tempDir.toRealPath();
    }

    @Test
    @DisplayName("Tooling API extracts the standard sourceSets (src/main/java + src/test/java) from a plain java-plugin project")
    void gradle_returnsActualSourceSets(@TempDir Path tempDir) throws IOException {
        assumeGradleAvailable();

        Path root = canonicalRoot(tempDir);
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'simple-gradle'\n");
        Files.writeString(root.resolve("build.gradle"),
            "plugins { id 'java' }\n");
        Files.createDirectories(root.resolve("src/main/java/com/example"));
        Files.createDirectories(root.resolve("src/test/java/com/example"));
        Files.writeString(root.resolve("src/main/java/com/example/Main.java"),
            "package com.example; public class Main {}\n");

        Optional<ProjectImporter.GradleProjectModel> model =
            ProjectImporter.readGradleProjectModel(root);

        assertTrue(model.isPresent(), "Tooling API should resolve a model for a java-plugin project");
        List<Path> srcs = model.get().srcPaths();
        assertTrue(srcs.contains(root.resolve("src/main/java")),
            "Standard src/main/java should be reported as a source directory; got " + srcs);
        assertTrue(srcs.contains(root.resolve("src/test/java")),
            "Standard src/test/java should be reported as a source directory; got " + srcs);
    }

    @Test
    @DisplayName("Tooling API resolves declared file-based dependencies into the classpath")
    void gradle_returnsActualDependencies(@TempDir Path tempDir) throws IOException {
        assumeGradleAvailable();

        // Use a flat-dir repo with a local jar to avoid any network dependency
        // for declared classpath deps. The jar's bytes don't matter — Gradle
        // resolves it as a path-typed classpath entry without inspecting it.
        Path root = canonicalRoot(tempDir);
        Files.createDirectories(root.resolve("libs"));
        Files.write(root.resolve("libs/dummy-1.0.0.jar"), new byte[]{});

        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'simple-gradle-deps'\n");
        Files.writeString(root.resolve("build.gradle"),
            "plugins { id 'java' }\n" +
            "repositories { flatDir { dirs 'libs' } }\n" +
            "dependencies { implementation files('libs/dummy-1.0.0.jar') }\n");
        Files.createDirectories(root.resolve("src/main/java"));

        Optional<ProjectImporter.GradleProjectModel> model =
            ProjectImporter.readGradleProjectModel(root);

        assertTrue(model.isPresent());
        List<Path> jars = model.get().classpathJars();
        Path dummyJar = root.resolve("libs/dummy-1.0.0.jar");
        assertTrue(jars.contains(dummyJar),
            "Declared file-based dependency should appear on the classpath; got " + jars);
    }

    @Test
    @DisplayName("Tooling API honours sourceSets.main.java.srcDirs overrides")
    void gradle_customSrcDir(@TempDir Path tempDir) throws IOException {
        assumeGradleAvailable();

        Path root = canonicalRoot(tempDir);
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'simple-gradle-custom'\n");
        Files.writeString(root.resolve("build.gradle"),
            "plugins { id 'java' }\n" +
            "sourceSets {\n" +
            "    main { java { srcDirs = ['custom-src'] } }\n" +
            "    test { java { srcDirs = ['custom-test'] } }\n" +
            "}\n");
        Files.createDirectories(root.resolve("custom-src/com/example"));
        Files.createDirectories(root.resolve("custom-test/com/example"));
        Files.writeString(root.resolve("custom-src/com/example/Main.java"),
            "package com.example; public class Main {}\n");

        Optional<ProjectImporter.GradleProjectModel> model =
            ProjectImporter.readGradleProjectModel(root);

        assertTrue(model.isPresent());
        List<Path> srcs = model.get().srcPaths();
        assertTrue(srcs.contains(root.resolve("custom-src")),
            "Custom src dir should be reported; got " + srcs);
        assertTrue(srcs.contains(root.resolve("custom-test")),
            "Custom test dir should be reported; got " + srcs);
        assertFalse(srcs.contains(root.resolve("src/main/java")),
            "Standard src/main/java was overridden — should NOT appear; got " + srcs);
    }
}
