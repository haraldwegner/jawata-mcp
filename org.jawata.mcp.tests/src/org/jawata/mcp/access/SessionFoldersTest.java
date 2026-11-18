package org.jawata.mcp.access;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h Stage 27 (p), his ruling 2026-11-18: {@code "Every subagent should see the complete jawata workspace it is working on"} and
 * {@code "this is always the union of the VSCode workspace and the jawata workspace"}. It replaces the one-project confinement of ACL4: a hire
 * working in any folder of the workspace may reach the whole of it, and a folder outside both is still refused everything. The workspace is
 * two Java projects the engine loaded and one folder only the editor holds (a TypeScript or documents folder), so a rule that forgets the
 * editor's folders fails the cases about {@code docs}. Each case has the control that must not trigger it.
 */
class SessionFoldersTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private Path root;
    private Path alpha;
    private Path beta;
    private Path docs;
    private Path policy;
    private final SessionFolders folders = new SessionFolders();

    @BeforeEach
    void layOutTheWorkspace() throws IOException {
        root = Files.createTempDirectory("jawata-session-folders-").toRealPath();
        alpha = Files.createDirectories(root.resolve("manifest").resolve("alpha"));
        beta = Files.createDirectories(root.resolve("manifest").resolve("beta"));
        docs = Files.createDirectories(root.resolve("manifest").resolve("docs"));
        policy = root.resolve("access-policy.json");
        Files.writeString(policy, "{\"allow\":[\"${workspace}\"],\"deny\":[]}");
    }

    @AfterEach
    void removeThem() throws IOException {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private AccessCheck check() {
        return new AccessCheck(policy, session -> folders.workspaceOf(session, List.of(alpha, beta), List.of(docs)), root.resolve("home"), root.resolve("scratch"));
    }

    private String refusalFor(String session, Path place) throws IOException {
        return check().refuse(session, OM.readTree("{\"file_path\":" + OM.writeValueAsString(place.toString()) + "}")).orElse("");
    }

    @Test
    @DisplayName("(p): a hire working in one project may reach a sibling project and the editor's own folder, which replaces the one-project confinement")
    void aBoundSessionSeesTheWholeWorkspace() throws IOException {
        try (SessionFolders.Binding ignored = folders.bind("hire-1", alpha)) {
            assertAll(
                () -> assertEquals("", refusalFor("hire-1", beta.resolve("B.java")), "THE CLAIM: the sibling project was refused"),
                () -> assertEquals("", refusalFor("hire-1", docs.resolve("plan.md")), "THE CLAIM: the editor's own folder was refused"),
                () -> assertEquals("", refusalFor("hire-1", alpha.resolve("A.java")), "CONTROL: the session's own project was refused"));
        }
    }

    @Test
    @DisplayName("(p): a hire whose working folder is a folder only the editor holds gets the whole workspace, which is what every TypeScript and Rust hire needs")
    void aNonJavaWorkspaceFolderGetsTheWholeWorkspace() throws IOException {
        try (SessionFolders.Binding ignored = folders.bind("hire-1", docs)) {
            assertAll(
                () -> assertEquals("", refusalFor("hire-1", docs.resolve("plan.md")), "the session's own non-Java folder was refused"),
                () -> assertEquals("", refusalFor("hire-1", alpha.resolve("A.java")), "a bound session in a non-Java folder was given no project"),
                () -> assertEquals("", refusalFor("hire-1", beta.resolve("B.java"))));
        }
    }

    @Test
    @DisplayName("(p), the control: a session nobody bound keeps the whole workspace, which is the reading before a hire is bound")
    void anUnboundSessionKeepsTheWholeWorkspace() throws IOException {
        try (SessionFolders.Binding ignored = folders.bind("hire-1", alpha)) {
            assertAll(
                () -> assertEquals("", refusalFor("a-persons-client", alpha.resolve("A.java"))),
                () -> assertEquals("", refusalFor("a-persons-client", beta.resolve("B.java"))),
                () -> assertEquals("", refusalFor("a-persons-client", docs.resolve("plan.md"))));
        }
    }

    @Test
    @DisplayName("(p): a folder inside a project reaches the whole workspace, so a hire in one module is no longer refused the project's other modules")
    void aFolderInsideAProjectSeesTheWholeWorkspace() throws IOException {
        Path module = Files.createDirectories(alpha.resolve("module-one"));
        Path other = Files.createDirectories(alpha.resolve("module-two"));
        try (SessionFolders.Binding ignored = folders.bind("hire-1", module)) {
            assertAll(
                () -> assertEquals("", refusalFor("hire-1", module.resolve("M.java"))),
                () -> assertEquals("", refusalFor("hire-1", other.resolve("N.java"))),
                () -> assertEquals("", refusalFor("hire-1", docs.resolve("plan.md"))));
        }
    }

    @Test
    @DisplayName("(p): naming a folder never widens: a hire told to work outside both workspaces is refused everything the workspace entry allows")
    void aFolderOutsideBothWorkspacesAllowsNothing() throws IOException {
        Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
        try (SessionFolders.Binding ignored = folders.bind("hire-1", elsewhere)) {
            assertAll(
                () -> assertFalse(refusalFor("hire-1", elsewhere.resolve("E.java")).isEmpty(), "the unlisted folder itself was let through"),
                () -> assertFalse(refusalFor("hire-1", alpha.resolve("A.java")).isEmpty(), "a bound session with no workspace was given a project"),
                () -> assertFalse(refusalFor("hire-1", docs.resolve("plan.md")).isEmpty(), "a bound session with no workspace was given the editor's folder"));
        }
    }

    @Test
    @DisplayName("(p): the editor's folders come from one setting, a path list: blanks and repeats are dropped, and a folder a project already holds is not listed twice")
    void theSettingIsAPathList() {
        String setting = " " + docs + File.pathSeparator + File.pathSeparator + docs + File.pathSeparator + alpha + File.pathSeparator;
        assertEquals(List.of(PlaceNames.normalise(docs), PlaceNames.normalise(alpha)), SessionFolders.editorFolders(setting));
        assertTrue(SessionFolders.editorFolders(null).isEmpty(), "a missing setting named a folder");
        assertTrue(SessionFolders.editorFolders("   ").isEmpty(), "a blank setting named a folder");
        assertEquals(List.of(PlaceNames.normalise(alpha), PlaceNames.normalise(beta), PlaceNames.normalise(docs)),
            folders.workspaceOf("nobody", List.of(alpha, beta), SessionFolders.editorFolders(docs + File.pathSeparator + alpha)));
    }

    @Test
    @DisplayName("ACL4, kept: the binding lasts the run: after it closes the session is concerned with the whole workspace again, and a run that states no folder binds nothing")
    void theBindingEndsWithTheRun() throws IOException {
        SessionFolders.Binding binding = folders.bind("hire-1", alpha);
        // the registry keeps a place under its one name (case folded where the filesystem ignores case, as on macOS), not as it was spelled
        assertEquals(PlaceNames.normalise(alpha), folders.of("hire-1").orElseThrow());
        binding.close();
        assertAll(
            () -> assertTrue(folders.of("hire-1").isEmpty(), "the binding outlived the run"),
            () -> assertEquals("", refusalFor("hire-1", beta.resolve("B.java"))),
            () -> {
                try (SessionFolders.Binding none = folders.bind("hire-2", null)) {
                    assertTrue(folders.of("hire-2").isEmpty(), "a run that stated no folder was bound to one");
                }
            });
    }
}
