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

    /** One editor window's list as the studio writes it: `<window>.txt` in the folder of lists, one folder to a line. */
    private static Path editorList(Path lists, String window, Path... folders) throws IOException {
        Files.createDirectories(lists);
        return Files.writeString(lists.resolve(window + ".txt"), String.join("\n", java.util.Arrays.stream(folders).map(Path::toString).toList()));
    }

    @Test
    @DisplayName("his word 2026-11-20: the folders every editor window has open are covered, each window by its own list, with no workspace file beside the repositories and before any Java project has loaded, and a folder no window holds is not")
    void theEditorWindowsOpenFoldersAreCovered(@TempDir Path root) throws Exception {
        Path trading = folder(root, "Projects");
        Path docs = folder(root, "notes");
        Path stranger = folder(root, "stranger");
        Path lists = root.resolve("editor-folders");
        editorList(lists, "window-a", trading);
        editorList(lists, "window-b", docs);
        AccessFolders access = new AccessFolders(lists, null);

        List<Path> loading = access.of(List.of());
        List<Path> loaded = access.of(List.of(projectIn(folder(root, "engine"), "core")));

        assertAll(
            () -> assertTrue(covers(loading, trading.resolve("jats2").resolve("bundle")) && covers(loading, docs), "while loading, the windows' folders are not covered: " + loading),
            () -> assertTrue(covers(loaded, trading) && covers(loaded, docs) && covers(loaded, root.resolve("engine")), "with a loaded project, a window's folders are not covered: " + loaded),
            () -> assertFalse(covers(loaded, stranger), "a folder no window holds was covered: " + loaded),
            () -> assertFalse(covers(new AccessFolders(root.resolve("absent"), null).of(List.of()), trading), "CONTROL: with no lists from the editor their folders were covered"));
    }

    @Test
    @DisplayName("second audit 2026-11-20 (finding 1): each reading hands on the projects' folders without the windows' folders, which is what a brain's file holds, so a folder a window closes is never kept in a brain's picture")
    void eachReadingHandsOnTheProjectsFoldersWithoutTheWindowsFolders(@TempDir Path root) throws Exception {
        Path core = projectIn(folder(root, "engine"), "core");
        Path docs = folder(root, "notes");
        Path lists = root.resolve("editor-folders");
        editorList(lists, "window-a", docs);
        List<List<Path>> handed = new java.util.ArrayList<>();
        AccessFolders access = new AccessFolders(lists, handed::add);

        List<Path> covered = access.of(List.of(core));

        assertAll(
            () -> assertTrue(covers(covered, docs), "CONTROL: the window's folder is not covered: " + covered),
            () -> assertEquals(1, handed.size(), "a reading did not hand on its projects' folders"),
            () -> assertTrue(covers(handed.get(0), core), "the projects' folders handed on miss the loaded project: " + handed),
            () -> assertFalse(covers(handed.get(0), docs), "a window's folder was handed on with the projects' folders: " + handed));
    }

    @Test
    @DisplayName("his word 2026-11-20 and audit bugs B and G: a folder a window opens later is covered at the next question without a restart, also when its list is rewritten within the same second, and a closed window's folders stop being covered")
    void aChangeOfAWindowsFoldersIsSeenWithoutARestart(@TempDir Path root) throws Exception {
        Path first = folder(root, "first");
        Path later = folder(root, "laterFolder");
        Path lists = root.resolve("editor-folders");
        Path list = editorList(lists, "window-a", first);
        FileTime written = Files.getLastModifiedTime(list);
        AccessFolders access = new AccessFolders(lists, null);

        List<Path> before = access.of(List.of());
        editorList(lists, "window-a", later);
        Files.setLastModifiedTime(list, written);
        List<Path> sameSecond = access.of(List.of());
        Files.delete(list);
        List<Path> closed = access.of(List.of());

        assertAll(
            () -> assertTrue(covers(before, first) && !covers(before, later), "before: " + before),
            () -> assertTrue(covers(sameSecond, later), "a list rewritten within the same time stamp was not seen (bug G): " + sameSecond),
            () -> assertFalse(covers(sameSecond, first), "a folder the window closed is still covered: " + sameSecond),
            () -> assertFalse(covers(closed, later), "a closed window's folders are still covered: " + closed));
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
