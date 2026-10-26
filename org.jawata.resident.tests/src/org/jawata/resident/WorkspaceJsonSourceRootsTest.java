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

    private static Path write(Path dir, String json) throws Exception {
        Path file = dir.resolve("workspace.json");
        Files.writeString(file, json);
        return file;
    }

    @Test
    @DisplayName("the declared folders are read per project path, main and test apart")
    void declaredFoldersAreReadPerProject(@TempDir Path dir) throws Exception {
        Path file = write(dir, """
            {"version":1,"name":"w","projects":["/ws/core","/ws/tools"],
             "sourceRoots":{"/ws/core":{"main":["/ws/core/java"],"test":["/ws/core/test"]},
                            "/ws/tools":{"main":["/ws/tools/src"],"test":[]}}}
            """);

        Map<Path, DeclaredSourceRoots> roots = WorkspaceJson.readSourceRoots(file);

        assertAll(
            () -> assertEquals(2, roots.size(), "got: " + roots),
            () -> assertEquals(List.of(Path.of("/ws/core/java")), roots.get(Path.of("/ws/core")).main()),
            () -> assertEquals(List.of(Path.of("/ws/core/test")), roots.get(Path.of("/ws/core")).test()),
            () -> assertEquals(List.of(), roots.get(Path.of("/ws/tools")).test()));
    }

    @Test
    @DisplayName("a folder given relative is the project's own: it is resolved against the project path")
    void relativeFoldersAreTheProjects(@TempDir Path dir) throws Exception {
        Path file = write(dir, """
            {"projects":["/ws/core"],"sourceRoots":{"/ws/core":{"main":["java"],"test":["test"]}}}
            """);

        DeclaredSourceRoots core = WorkspaceJson.readSourceRoots(file).get(Path.of("/ws/core"));

        assertAll(
            () -> assertEquals(List.of(Path.of("/ws/core/java")), core.main()),
            () -> assertEquals(List.of(Path.of("/ws/core/test")), core.test()));
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
        Path file = write(dir, """
            {"sourceRoots":{"/ws/empty":{"main":[],"test":[]},"/ws/odd":"src","/ws/core":{"main":["/ws/core/java"]}}}
            """);

        Map<Path, DeclaredSourceRoots> roots = WorkspaceJson.readSourceRoots(file);

        assertAll(
            // Mounting no folder at all would load a project that looks healthy and holds nothing: the failure Sprint 28 closed.
            () -> assertFalse(roots.containsKey(Path.of("/ws/empty")), "got: " + roots),
            () -> assertFalse(roots.containsKey(Path.of("/ws/odd")), "got: " + roots),
            () -> assertTrue(roots.containsKey(Path.of("/ws/core")), "a missing 'test' is an empty one: " + roots));
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
