package org.jawata.mcp.access;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Sprint 28h, S13 (D20, ACL4): a task on one project is denied a sibling project that sits in the same manifest. The claim is read at the
 * decision, through {@link AccessCheck}, with two projects loaded: the session bound to the first is refused the second and a session nobody
 * bound keeps both. Each case has the control that must not trigger it.
 */
class SessionFoldersTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private Path root;
    private Path alpha;
    private Path beta;
    private Path policy;
    private final SessionFolders folders = new SessionFolders();

    @BeforeEach
    void layOutTwoProjects() throws IOException {
        root = Files.createTempDirectory("jawata-session-folders-").toRealPath();
        alpha = Files.createDirectories(root.resolve("manifest").resolve("alpha"));
        beta = Files.createDirectories(root.resolve("manifest").resolve("beta"));
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
        return new AccessCheck(policy, session -> folders.workspaceOf(session, List.of(alpha, beta)), root.resolve("home"), root.resolve("scratch"));
    }

    private String refusalFor(String session, Path place) throws IOException {
        return check().refuse(session, OM.readTree("{\"file_path\":" + OM.writeValueAsString(place.toString()) + "}")).orElse("");
    }

    @Test
    @DisplayName("ACL4: a session bound to one project is refused a sibling in the same manifest, and is let into its own")
    void aBoundSessionIsRefusedItsSibling() throws IOException {
        try (SessionFolders.Binding ignored = folders.bind("hire-1", alpha)) {
            assertAll(
                () -> assertTrue(refusalFor("hire-1", beta.resolve("B.java")).contains("outside every place the access policy allows"),
                    "THE CLAIM: the sibling project was let through"),
                () -> assertEquals("", refusalFor("hire-1", alpha.resolve("A.java")), "CONTROL: the session's own project was refused"));
        }
    }

    @Test
    @DisplayName("ACL4, the control: a session nobody bound keeps every loaded project, which is the reading before a hire is bound")
    void anUnboundSessionKeepsTheWholeManifest() throws IOException {
        try (SessionFolders.Binding ignored = folders.bind("hire-1", alpha)) {
            assertAll(
                () -> assertEquals("", refusalFor("a-persons-client", alpha.resolve("A.java"))),
                () -> assertEquals("", refusalFor("a-persons-client", beta.resolve("B.java"))));
        }
    }

    @Test
    @DisplayName("ACL4: a folder inside a project narrows to that folder, so a hire in one module is refused the project's other modules")
    void aFolderInsideAProjectNarrowsToIt() throws IOException {
        Path module = Files.createDirectories(alpha.resolve("module-one"));
        Path other = Files.createDirectories(alpha.resolve("module-two"));
        try (SessionFolders.Binding ignored = folders.bind("hire-1", module)) {
            assertAll(
                () -> assertEquals("", refusalFor("hire-1", module.resolve("M.java"))),
                () -> assertFalse(refusalFor("hire-1", other.resolve("N.java")).isEmpty()));
        }
    }

    @Test
    @DisplayName("ACL4: naming a folder never widens: a hire told to work outside every loaded project is refused everything the workspace entry allows")
    void aFolderOutsideEveryProjectAllowsNothing() throws IOException {
        Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
        try (SessionFolders.Binding ignored = folders.bind("hire-1", elsewhere)) {
            assertAll(
                () -> assertFalse(refusalFor("hire-1", elsewhere.resolve("E.java")).isEmpty(), "the unlisted folder itself was let through"),
                () -> assertFalse(refusalFor("hire-1", alpha.resolve("A.java")).isEmpty(), "a bound session with no workspace was given a project"));
        }
    }

    @Test
    @DisplayName("ACL4: the binding lasts the run: after it closes the session is concerned with the whole manifest again, and a run that states no folder binds nothing")
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
