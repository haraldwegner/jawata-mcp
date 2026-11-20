package org.jawata.mcp.access;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
 * Sprint 28h Stage 27 (p), his rulings 2026-11-18 ({@code "this is always the union of the VSCode workspace and the jawata workspace"}) and
 * 2026-11-20 ({@code "the workspace of VSCode has to be included. Otherwise the jawata agents have no access"}). Every session, a hire's or a
 * person's, reaches the whole union, and a folder outside both is still refused. The workspace is two Java projects the engine loaded and one
 * folder only the editor holds (a TypeScript or documents folder), so a rule that forgets the editor's folders fails the cases about
 * {@code docs}. Each case has the control that must not trigger it.
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

    private String refusalFor(String session, Path place) throws IOException {
        AccessCheck check = new AccessCheck(policy, s -> folders.workspaceOf(s, List.of(alpha, beta), List.of(docs)), root.resolve("home"),
            root.resolve("scratch"));
        return check.refuse(session, OM.readTree("{\"file_path\":" + OM.writeValueAsString(place.toString()) + "}")).orElse("");
    }

    @Test
    @DisplayName("(p): every session reaches both loaded projects and the editor's own folder, whoever it is")
    void everySessionReachesTheWholeWorkspace() throws IOException {
        assertAll(
            () -> assertEquals("", refusalFor("hire-1", beta.resolve("B.java")), "THE CLAIM: a hire was refused a loaded project"),
            () -> assertEquals("", refusalFor("hire-1", docs.resolve("plan.md")), "THE CLAIM: a hire was refused the editor's own folder"),
            () -> assertEquals("", refusalFor("a-persons-client", alpha.resolve("A.java")), "a person's client was refused a loaded project"),
            () -> assertEquals("", refusalFor("a-persons-client", docs.resolve("plan.md")), "a person's client was refused the editor's folder"));
    }

    @Test
    @DisplayName("(p), the control: a folder outside both the loaded projects and the editor's folders is refused")
    void aFolderOutsideBothIsRefused() throws IOException {
        Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
        assertFalse(refusalFor("hire-1", elsewhere.resolve("E.java")).isEmpty(), "a folder outside the workspace was let through");
    }

    @Test
    @DisplayName("(p): a folder the projects and the editor both hold is listed once, projects first")
    void eachFolderIsListedOnce() {
        assertEquals(List.of(PlaceNames.normalise(alpha), PlaceNames.normalise(beta), PlaceNames.normalise(docs)),
            folders.workspaceOf("nobody", List.of(alpha, beta), List.of(docs, alpha)));
    }
}
