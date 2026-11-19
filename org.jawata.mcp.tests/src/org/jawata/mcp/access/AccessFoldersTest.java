package org.jawata.mcp.access;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stage 27 (z4): access is the workspace as it stands on disk, whoever started first. Its folders are the repository each loaded Java project lies
 * in and the folders of the VS Code workspace file those repositories belong to; nothing is read from a launch event, and a change to the file
 * is seen by the next question without a restart. A folder in neither is not covered.
 */
class AccessFoldersTest {

    private static Path folder(Path under, String name) throws IOException {
        return Files.createDirectories(under.resolve(name));
    }

    private static Path projectIn(Path repo, String name) throws IOException {
        Files.createDirectories(repo.resolve(".git"));
        return Files.createDirectories(repo.resolve(name));
    }

    private static void workspaceFile(Path file, String folders) throws IOException {
        Files.writeString(file, "{\n  // the folders of this window\n  \"folders\": [" + folders + "],\n  \"settings\": {}\n}\n");
    }

    private static boolean covers(List<Path> covered, Path place) {
        return covered.stream().anyMatch(folder -> place.toAbsolutePath().normalize().startsWith(folder));
    }

    @Test
    @DisplayName("the repository that encloses a loaded Java project is covered, and the project's own folder when it lies in no repository")
    void theEnclosingRepositoryIsCovered(@TempDir Path root) throws Exception {
        Path inRepo = projectIn(folder(root, "engine"), "core");
        Path loose = folder(root, "loose-project");

        List<Path> covered = new AccessFolders().of(List.of(inRepo, loose));

        assertAll(
            () -> assertTrue(covers(covered, root.resolve("engine")), "the repository of the project is not covered: " + covered),
            () -> assertTrue(covers(covered, root.resolve("engine").resolve("docs")), "a folder beside the project in its repository is not covered"),
            () -> assertTrue(covers(covered, loose), "a project in no repository is not covered by its own folder"),
            () -> assertFalse(covers(covered, root.resolve("elsewhere")), "a folder in neither was covered: " + covered));
    }

    @Test
    @DisplayName("the folders of the workspace file beside the repositories are covered when it lists one of them, and a workspace file that lists none of them is not read")
    void theWorkspaceFilesFoldersAreCovered(@TempDir Path root) throws Exception {
        Path core = projectIn(folder(root, "engine"), "core");
        folder(root, "chat");
        folder(root, "studio");
        folder(root, "strangers");
        workspaceFile(root.resolve("dev.code-workspace"), "{\"path\": \"engine\"}, {\"path\": \"chat\"}, {\"path\": \"studio\"}");
        workspaceFile(root.resolve("other.code-workspace"), "{\"path\": \"strangers\"}");

        List<Path> covered = new AccessFolders().of(List.of(core));

        assertAll(
            () -> assertTrue(covers(covered, root.resolve("chat")), "a folder of the workspace file is not covered: " + covered),
            () -> assertTrue(covers(covered, root.resolve("studio")), covered.toString()),
            () -> assertFalse(covers(covered, root.resolve("strangers")), "the folder of a workspace file that does not hold the project was covered: " + covered));
    }

    @Test
    @DisplayName("a folder added to the workspace file is covered by the next question without a restart, and a folder taken out stops being covered")
    void aChangeToTheWorkspaceFileIsSeenWithoutARestart(@TempDir Path root) throws Exception {
        Path core = projectIn(folder(root, "engine"), "core");
        folder(root, "chat");
        folder(root, "notes");
        Path file = root.resolve("dev.code-workspace");
        workspaceFile(file, "{\"path\": \"engine\"}, {\"path\": \"chat\"}");
        AccessFolders access = new AccessFolders();

        List<Path> before = access.of(List.of(core));
        workspaceFile(file, "{\"path\": \"engine\"}, {\"path\": \"notes\"}");
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        List<Path> after = access.of(List.of(core));

        assertAll(
            () -> assertTrue(covers(before, root.resolve("chat")) && !covers(before, root.resolve("notes")), "before: " + before),
            () -> assertTrue(covers(after, root.resolve("notes")), "a folder added to the file was not seen: " + after),
            () -> assertFalse(covers(after, root.resolve("chat")), "a folder taken out of the file is still covered: " + after));
    }

    @Test
    @DisplayName("a hire bound to a folder of the workspace file that is no Java project has the workspace, whoever started first, and one bound to a folder in neither has none")
    void aHireBoundInAFolderOfTheWorkspaceFileIsInItsWorkspace(@TempDir Path root) throws Exception {
        Path core = projectIn(folder(root, "engine"), "core");
        Path enterprise = folder(root, "enterprise");
        Path stranger = folder(root, "stranger");
        workspaceFile(root.resolve("dev.code-workspace"), "{\"path\": \"engine\"}, {\"path\": \"enterprise\"}");
        SessionFolders sessions = new SessionFolders();
        List<Path> beside = new AccessFolders().of(List.of(core));

        try (SessionFolders.Binding inside = sessions.bind("plan-in", enterprise); SessionFolders.Binding outside = sessions.bind("plan-out", stranger)) {
            assertAll(
                () -> assertFalse(sessions.workspaceOf("plan-in", List.of(core), beside).isEmpty(), "a hire in a folder of the workspace file has no workspace"),
                () -> assertFalse(covers(sessions.workspaceOf("plan-out", List.of(core), beside), stranger), "the folder of no source that a hire named was covered"));
        }
    }

    @Test
    @DisplayName("C27 audit: a workspace file that lists the filesystem root or the person's home folder does not make them writable, and its other folders still are")
    void aWorkspaceFileCannotWidenAccessToTheRootOrTheHome(@TempDir Path root) throws Exception {
        Path core = projectIn(folder(root, "engine"), "core");
        folder(root, "chat");
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        String listed = "{\"path\": \"engine\"}, {\"path\": \"chat\"}, {\"path\": \"/\"}, {\"path\": \"" + home.toString().replace("\\", "\\\\") + "\"}";
        workspaceFile(root.resolve("cloned.code-workspace"), listed);

        List<Path> covered = new AccessFolders().of(List.of(core));

        assertAll(
            () -> assertFalse(covers(covered, home.resolve("somewhere-private")), "the home folder was made covered by a workspace file: " + covered),
            () -> assertFalse(covers(covered, home.getRoot().resolve("etc")), "the filesystem root was made covered by a workspace file: " + covered),
            () -> assertTrue(covers(covered, root.resolve("chat")), "CONTROL: the folders of the file that are not the root or the home were refused: " + covered));
    }

    @Test
    @DisplayName("C27: a workspace file inside a repository, which may have come with a clone, is not read for its folders, while the one in the folder above the repositories is")
    void aWorkspaceFileInsideARepositoryIsNotRead(@TempDir Path root) throws Exception {
        Path repo = folder(root, "engine");
        Path core = projectIn(repo, "core");
        folder(root, "chat");
        folder(root, "planted");
        workspaceFile(repo.resolve("shipped.code-workspace"), "{\"path\": \"../engine\"}, {\"path\": \"../planted\"}");
        workspaceFile(root.resolve("mine.code-workspace"), "{\"path\": \"engine\"}, {\"path\": \"chat\"}");

        List<Path> covered = new AccessFolders().of(List.of(core));

        assertAll(
            () -> assertFalse(covers(covered, root.resolve("planted")), "a folder listed by a workspace file inside the repository was covered: " + covered),
            () -> assertTrue(covers(covered, root.resolve("chat")), "CONTROL: the workspace file in the folder above was not read: " + covered));
    }

    private static void editorFolders(Path file, Path... folders) throws IOException {
        Files.writeString(file, String.join("\n", java.util.Arrays.stream(folders).map(Path::toString).toList()));
    }

    @Test
    @DisplayName("his word 2026-11-20: the folders the editor window has open are covered, read from what the editor handed the studio, with no workspace file beside the repositories and before any Java project has loaded, and a folder the window does not hold is not")
    void theEditorWindowsOpenFoldersAreCovered(@TempDir Path root) throws Exception {
        Path trading = folder(root, "Projects");
        Path docs = folder(root, "notes");
        Path stranger = folder(root, "stranger");
        Path handed = root.resolve("editor-folders.txt");
        editorFolders(handed, trading, docs);
        AccessFolders access = new AccessFolders(handed);

        List<Path> loading = access.of(List.of());
        List<Path> loaded = access.of(List.of(projectIn(folder(root, "engine"), "core")));

        assertAll(
            () -> assertTrue(covers(loading, trading.resolve("jats2").resolve("bundle")) && covers(loading, docs), "while loading, the window's folders are not covered: " + loading),
            () -> assertTrue(covers(loaded, trading) && covers(loaded, docs) && covers(loaded, root.resolve("engine")), "with a loaded project, the window's folders are not covered: " + loaded),
            () -> assertFalse(covers(loaded, stranger), "a folder the window does not hold was covered: " + loaded),
            () -> assertFalse(covers(new AccessFolders(root.resolve("absent.txt")).of(List.of()), trading), "CONTROL: with no list from the editor its folders were covered"));
    }

    @Test
    @DisplayName("his word 2026-11-20: a folder the editor window opens later is covered by the next question without a restart of the engine, and a folder it closed stops being covered")
    void aChangeOfTheEditorWindowsFoldersIsSeenWithoutARestart(@TempDir Path root) throws Exception {
        Path first = folder(root, "first");
        Path later = folder(root, "later");
        Path handed = root.resolve("editor-folders.txt");
        editorFolders(handed, first);
        AccessFolders access = new AccessFolders(handed);

        List<Path> before = access.of(List.of());
        editorFolders(handed, later);
        Files.setLastModifiedTime(handed, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        List<Path> after = access.of(List.of());

        assertAll(
            () -> assertTrue(covers(before, first) && !covers(before, later), "before: " + before),
            () -> assertTrue(covers(after, later), "a folder the window opened later was not seen: " + after),
            () -> assertFalse(covers(after, first), "a folder the window closed is still covered: " + after));
    }

    @Test
    @DisplayName("audit 2026-11-20: a workspace file beside the repositories that lists module folders inside a repository, not the repository itself, is read")
    void aWorkspaceFileListingModulesInsideARepositoryIsRead(@TempDir Path root) throws Exception {
        Path repo = folder(root, "jats");
        Path core = projectIn(repo, "core");
        folder(repo, "ui");
        folder(root, "docs");
        workspaceFile(root.resolve("trading.code-workspace"), "{\"path\": \"jats/core\"}, {\"path\": \"jats/ui\"}, {\"path\": \"docs\"}");

        List<Path> covered = new AccessFolders().of(List.of(core));

        assertTrue(covers(covered, root.resolve("docs")), "a workspace file listing folders inside the repository was not read: " + covered);
    }

    @Test
    @DisplayName("his ruling 2026-11-18 (\"always the union of the VSCode workspace and the jawata workspace\"): a hire bound to a folder outside it still has the whole workspace, and the folder it names does not widen it")
    void aHireBoundOutsideStillHasTheUnionAndItsFolderDoesNotWiden(@TempDir Path root) throws Exception {
        Path core = projectIn(folder(root, "engine"), "core");
        Path worktree = folder(root, "tmp-worktree");
        SessionFolders sessions = new SessionFolders();

        try (SessionFolders.Binding outside = sessions.bind("plan-out", worktree)) {
            List<Path> workspace = sessions.workspaceOf("plan-out", List.of(core), List.of());
            assertAll(
                () -> assertTrue(covers(workspace, core), "a hire bound outside the workspace lost the loaded project: " + workspace),
                () -> assertFalse(covers(workspace, worktree), "the folder the hire named widened the workspace: " + workspace));
        }
    }

    @Test
    @DisplayName("an absolute path and a file uri in the workspace file are read as the folders they name, and a file that is not json is skipped, not fatal")
    void absolutePathsAndUrisAreReadAndAnUnreadableFileIsSkipped(@TempDir Path root) throws Exception {
        Path core = projectIn(folder(root, "engine"), "core");
        Path far = folder(root, "far");
        Path near = folder(root, "near");
        workspaceFile(root.resolve("dev.code-workspace"),
            "{\"path\": \"engine\"}, {\"path\": \"" + far.toString().replace("\\", "\\\\") + "\"}, {\"uri\": \"" + near.toUri() + "\"}");
        Files.writeString(root.resolve("broken.code-workspace"), "this is not json at all {");

        List<Path> covered = new AccessFolders().of(List.of(core));

        assertAll(
            () -> assertTrue(covers(covered, far), "an absolute path of the file was not read: " + covered),
            () -> assertTrue(covers(covered, near), "a file uri of the file was not read: " + covered),
            () -> assertEquals(true, covers(covered, root.resolve("engine")), "the unreadable file cost the repository its cover"));
    }
}
