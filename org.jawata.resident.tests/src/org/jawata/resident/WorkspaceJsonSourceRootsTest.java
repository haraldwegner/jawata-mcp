package org.jawata.resident;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jawata.resident.project.DeclaredSourceRoots;
import org.jawata.resident.workspace.WorkspaceJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28k, Stage 8 (contract S4, engine half): the source folders a member declares in {@code workspace.json}. Studio writes them
 * for an IntelliJ module that has no build file, from its {@code .iml}: {@code "sourceRoots": { "<project path>": { "main": [...],
 * "test": [...] } }}, additive beside {@code projects}.
 */
class WorkspaceJsonSourceRootsTest {

    /** Absolute on every OS: a path that starts with a slash has no drive on Windows, where the reader makes it absolute against one. */
    private static final Path WS = Path.of("").toAbsolutePath().getRoot().resolve("ws");

    /** A path as JSON text: forward slashes, which every OS reads and which need no escaping. */
    private static String json(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static Path write(Path dir, String json) throws Exception {
        Path file = dir.resolve("workspace.json");
        Files.writeString(file, json);
        return file;
    }

    @Test
    @DisplayName("the declared folders are read per project path, main and test apart")
    void declaredFoldersAreReadPerProject(@TempDir Path dir) throws Exception {
        Path core = WS.resolve("core");
        Path tools = WS.resolve("tools");
        Path file = write(dir, """
            {"version":1,"name":"w","projects":["%1$s","%2$s"],
             "sourceRoots":{"%1$s":{"main":["%1$s/java"],"test":["%1$s/test"]},
                            "%2$s":{"main":["%2$s/src"],"test":[]}}}
            """.formatted(json(core), json(tools)));

        Map<Path, DeclaredSourceRoots> roots = WorkspaceJson.readSourceRoots(file);

        assertAll(
            () -> assertEquals(2, roots.size(), "got: " + roots),
            () -> assertEquals(List.of(core.resolve("java")), roots.get(core).main()),
            () -> assertEquals(List.of(core.resolve("test")), roots.get(core).test()),
            () -> assertEquals(List.of(), roots.get(tools).test()));
    }

    @Test
    @DisplayName("a folder given relative is the project's own: it is resolved against the project path")
    void relativeFoldersAreTheProjects(@TempDir Path dir) throws Exception {
        Path project = WS.resolve("core");
        Path file = write(dir, """
            {"projects":["%1$s"],"sourceRoots":{"%1$s":{"main":["java"],"test":["test"]}}}
            """.formatted(json(project)));

        DeclaredSourceRoots core = WorkspaceJson.readSourceRoots(file).get(project);

        assertAll(
            () -> assertEquals(List.of(project.resolve("java")), core.main()),
            () -> assertEquals(List.of(project.resolve("test")), core.test()));
    }

    @Test
    @DisplayName("a file with no sourceRoots declares none: every member loads as before")
    void noSourceRootsDeclaresNone(@TempDir Path dir) throws Exception {
        assertAll(
            () -> assertEquals(Map.of(), WorkspaceJson.readSourceRoots(write(dir, "{\"projects\":[\"/ws/core\"]}"))),
            () -> assertEquals(Map.of(), WorkspaceJson.readSourceRoots(write(dir, "{\"sourceRoots\":[]}"))),
            () -> assertEquals(Map.of(), WorkspaceJson.readSourceRoots(dir.resolve("absent.json"))),
            () -> assertEquals(Map.of(), WorkspaceJson.readSourceRoots(write(dir, "{ not json"))));
    }

    @Test
    @DisplayName("an entry that names no folder at all is not an entry: the member is discovered instead of mounted empty")
    void anEntryWithNoFoldersIsNotAnEntry(@TempDir Path dir) throws Exception {
        Path empty = WS.resolve("empty");
        Path odd = WS.resolve("odd");
        Path core = WS.resolve("core");
        Path file = write(dir, """
            {"sourceRoots":{"%1$s":{"main":[],"test":[]},"%2$s":"src","%3$s":{"main":["%3$s/java"]}}}
            """.formatted(json(empty), json(odd), json(core)));

        Map<Path, DeclaredSourceRoots> roots = WorkspaceJson.readSourceRoots(file);

        assertAll(
            // Mounting no folder at all would load a project that looks healthy and holds nothing: the failure Sprint 28 closed.
            () -> assertFalse(roots.containsKey(empty), "got: " + roots),
            () -> assertFalse(roots.containsKey(odd), "got: " + roots),
            () -> assertTrue(roots.containsKey(core), "a missing 'test' is an empty one: " + roots));
    }

    @Test
    @DisplayName("a folder is main or test as declared: the test set answers isTest, and all lists main then test")
    void testNessComesFromTheDeclaration() {
        DeclaredSourceRoots roots = new DeclaredSourceRoots(List.of(Path.of("/ws/core/java")), List.of(Path.of("/ws/core/test")));

        assertAll(
            () -> assertTrue(roots.isTest(Path.of("/ws/core/test"))),
            () -> assertFalse(roots.isTest(Path.of("/ws/core/java"))),
            () -> assertEquals(List.of(Path.of("/ws/core/java"), Path.of("/ws/core/test")), roots.all()));
    }
}
