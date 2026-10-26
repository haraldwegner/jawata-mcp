package org.jawata.resident.workspace;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.jawata.resident.fixtures.TestProjectHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28k, Stage 8 (contract S4, engine half): a member that declares its source folders in {@code workspace.json} mounts exactly
 * those, the test ones marked test, instead of having them discovered; a member without an entry loads as before.
 *
 * <p>The fixture is studio's {@code intellij-without-build/core}, an IntelliJ module with no build file whose {@code .iml} names
 * {@code java} as main and {@code test} as test. A {@code src} folder holding a class is added to a copy of it, so that what is
 * <i>declared</i> and what would be <i>discovered</i> differ and a load that ignores the declaration fails.</p>
 */
class DeclaredSourceRootsLoadTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @TempDir
    Path scratch;

    private Path core;
    private Path dataDir;
    private JdtServiceImpl service;
    private WorkspaceFileWatcher watcher;

    @BeforeEach
    void setUp() throws Exception {
        core = scratch.resolve("ws").resolve("core");
        copyTree(helper.getFixturePath("intellij-without-build").resolve("core"), core);
        Path stray = core.resolve("src").resolve("demo");
        Files.createDirectories(stray);
        Files.writeString(stray.resolve("Stray.java"), "package demo;\npublic class Stray {}\n");
        dataDir = Files.createDirectories(scratch.resolve("data"));
        service = helper.disposeAfter(new JdtServiceImpl());
    }

    @AfterEach
    void tearDown() {
        if (watcher != null) {
            watcher.close();
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
    }

    private void load(String sourceRootsJson) throws Exception {
        String roots = sourceRootsJson == null ? "" : ",\"sourceRoots\":{\"" + core + "\":" + sourceRootsJson + "}";
        Files.writeString(dataDir.resolve("workspace.json"), "{\"version\":1,\"name\":\"s4\",\"projects\":[\"" + core + "\"]" + roots + "}");
        watcher = new WorkspaceFileWatcher(dataDir, service);
        watcher.start();
        assertEquals(1, service.allProjects().size(), "the member loaded: " + watcher.loadFailures());
    }

    /** The mounted source folders by real location, each with whether the model marks it test. */
    private Map<Path, Boolean> mountedRoots() throws Exception {
        IJavaProject project = service.allProjects().iterator().next().javaProject();
        Map<Path, Boolean> roots = new TreeMap<>();
        for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
                continue;
            }
            boolean test = false;
            for (IClasspathAttribute attribute : root.getRawClasspathEntry().getExtraAttributes()) {
                test |= IClasspathAttribute.TEST.equals(attribute.getName()) && "true".equals(attribute.getValue());
            }
            roots.put(root.getResource().getLocation().toFile().toPath().toRealPath(), test);
        }
        return roots;
    }

    @Test
    @DisplayName("a member with an entry mounts exactly its declared folders, the test one marked test, and not the folder beside them")
    void aDeclaredMemberMountsWhatItDeclared() throws Exception {
        load("{\"main\":[\"" + core.resolve("java") + "\"],\"test\":[\"" + core.resolve("test") + "\"]}");

        Map<Path, Boolean> mounted = mountedRoots();

        assertAll(
            () -> assertEquals(Map.of(core.resolve("java").toRealPath(), false, core.resolve("test").toRealPath(), true), mounted,
                "got: " + mounted),
            () -> assertTrue(service.getAllJavaFiles().stream().noneMatch(f -> f.getFileName().toString().equals("Stray.java")),
                "src was not declared, so Stray is not part of the model"));
    }

    @Test
    @DisplayName("a changed declaration of a loaded member takes effect: the studio rewrites it when the module file changes")
    void aChangedDeclarationReloadsTheMember() throws Exception {
        load(null);
        assertTrue(mountedRoots().containsKey(core.resolve("src").toRealPath()), "discovered at first");

        Files.writeString(dataDir.resolve("workspace.json"), "{\"version\":1,\"name\":\"s4\",\"projects\":[\"" + core + "\"],"
            + "\"sourceRoots\":{\"" + core + "\":{\"main\":[\"" + core.resolve("java") + "\"],\"test\":[\"" + core.resolve("test")
            + "\"]}}}");

        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        boolean reloaded = false;
        while (!reloaded && System.nanoTime() < deadline) {
            Thread.sleep(250);
            reloaded = service.allProjects().size() == 1 && !mountedRoots().containsKey(core.resolve("src").toRealPath());
        }
        assertTrue(reloaded, "the member was not reloaded with the folders it now declares: " + mountedRoots());
    }

    @Test
    @DisplayName("THE CONTROL — the same member with no entry is discovered as before, so the declaration above is what left src out")
    void anUndeclaredMemberIsDiscovered() throws Exception {
        load(null);

        Map<Path, Boolean> mounted = mountedRoots();

        assertTrue(mounted.containsKey(core.resolve("src").toRealPath()),
            "discovery finds the folder the declaration left out: " + mounted);
    }
}
