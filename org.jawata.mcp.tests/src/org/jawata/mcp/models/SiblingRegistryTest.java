package org.jawata.mcp.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mcp#27 stage 1 — the half that says WHO to ask.
 *
 * <p>Every case here is about the registry being read HONESTLY: a machine with no studio has no
 * siblings and that is not a fault; a server must never peek itself; and a row that cannot be
 * addressed is dropped rather than guessed at. Asking is a separate concern and is not
 * exercised here — nothing in this class opens a socket.</p>
 */
class SiblingRegistryTest {

    private static Path registry(Path dir, String json) throws Exception {
        Files.writeString(dir.resolve(SiblingRegistry.FILE_NAME), json);
        return dir;
    }

    @Test
    @DisplayName("mcp#27: the siblings are everyone in the registry except this server")
    void selfIsExcludedAndTheRestAreReturned(@TempDir Path dir) throws Exception {
        registry(dir, """
            {"residents": [
              {"workspaceName": "javata-dev",    "port": 8081, "token": "t1"},
              {"workspaceName": "orb-strategy",  "port": 8082, "token": "t2"},
              {"workspaceName": "patterns",      "port": 8083, "token": "t3"}
            ]}
            """);

        List<SiblingRegistry.Sibling> others = SiblingRegistry.others(dir, "orb-strategy");

        assertAll(
            () -> assertEquals(2, others.size(), "got: " + others),
            // A server that peeked ITSELF would report its own miss back as a sibling's
            // answer, which is worse than not peeking at all.
            () -> assertTrue(others.stream().noneMatch(s -> "orb-strategy".equals(s.workspaceName())),
                "this server must not be among its own siblings: " + others),
            () -> assertEquals(8081, others.get(0).port(), "got: " + others),
            () -> assertEquals("t3", others.get(1).token(), "got: " + others));
    }

    @Test
    @DisplayName("mcp#27: NO registry is a machine with one resident, not a failure")
    void anAbsentRegistryIsAnEmptyAnswer(@TempDir Path dir) {
        // The ordinary case: a resident launched by hand, by Cursor or by Claude Code has no
        // studio behind it. Throwing here would make a missing hint break a search.
        assertEquals(List.of(), SiblingRegistry.others(dir, "javata-dev"),
            "an absent registry means there is nobody to ask");
        assertEquals(List.of(), SiblingRegistry.others(null, "javata-dev"),
            "and neither does a server that cannot say where its registry would be");
    }

    @Test
    @DisplayName("mcp#27: a row that cannot be ADDRESSED is dropped, not guessed at")
    void unaddressableRowsAreDropped(@TempDir Path dir) throws Exception {
        registry(dir, """
            {"residents": [
              {"workspaceName": "",            "port": 8081, "token": "t1"},
              {"workspaceName": "no-port",                   "token": "t2"},
              {"workspaceName": "zero-port",   "port": 0,    "token": "t3"},
              {"workspaceName": "reachable",   "port": 8084, "token": "t4"}
            ]}
            """);

        List<SiblingRegistry.Sibling> others = SiblingRegistry.others(dir, "javata-dev");

        // A nameless row cannot be quoted back to the caller and a portless one cannot be
        // called at all; either would turn into an invented address the moment it was used.
        assertEquals(1, others.size(), "only the addressable row survives: " + others);
        assertEquals("reachable", others.get(0).workspaceName(), "got: " + others);
    }

    @Test
    @DisplayName("mcp#27: garbage is survivable — a hint that cannot be built must not fail a search")
    void unreadableRegistryDoesNotThrow(@TempDir Path dir) throws Exception {
        registry(dir, "{ this is not json");

        assertEquals(List.of(), SiblingRegistry.others(dir, "javata-dev"),
            "a broken registry answers empty rather than throwing into a search");
    }

    @Test
    @DisplayName("mcp#27: the registry is found from the data dir, with or without a session subdir")
    void theRegistryIsFoundFromEitherDataDirShape(@TempDir Path root) throws Exception {
        // Studio's layout: <data_root>/workspaces/<workspace>/, with the registry one level
        // above every workspace. The launcher MAY inject a session subdir, so the resident does
        // not know its own depth — which is exactly why JawataApplication already walks up for
        // its own workspace.json rather than resolving a fixed path.
        Path workspaces = Files.createDirectories(root.resolve("workspaces"));
        Path direct = Files.createDirectories(workspaces.resolve("javata-dev"));
        Path withSession = Files.createDirectories(direct.resolve("a1b2c3-session"));
        registry(workspaces, """
            {"residents": [{"workspaceName": "patterns", "port": 8090, "token": "t"}]}
            """);

        assertAll(
            () -> assertEquals(workspaces, SiblingRegistry.locate(direct),
                "found from the workspace dir itself"),
            () -> assertEquals(workspaces, SiblingRegistry.locate(withSession),
                "and from the session subdir the launcher injects"),
            () -> assertEquals(1, SiblingRegistry.around(withSession, "javata-dev").size(),
                "and `around` joins the walk to the read"));
    }

    @Test
    @DisplayName("mcp#27: the walk is BOUNDED — it must not adopt a stranger's registry")
    void theWalkDoesNotClimbToTheRoot(@TempDir Path root) throws Exception {
        // An unbounded walk would keep climbing until it found SOME residents.json — one
        // belonging to a different install, or to nobody. A wrong registry is worse than none,
        // because every address in it is confidently askable.
        registry(root, """
            {"residents": [{"workspaceName": "stranger", "port": 9999, "token": "t"}]}
            """);
        Path deep = Files.createDirectories(
            root.resolve("a").resolve("b").resolve("c").resolve("d").resolve("e"));

        assertEquals(null, SiblingRegistry.locate(deep),
            "a registry five levels up is not this server's registry");
    }

    @Test
    @DisplayName("THE CONTROL — a null self name excludes NOBODY, rather than excluding everyone")
    void anUnnamedServerExcludesNothing(@TempDir Path dir) throws Exception {
        // Without this, an implementation comparing names with `selfName.equals(...)` would
        // throw, and one comparing the other way round would silently drop every row whose
        // name is null-ish. A server that does not know its own name cannot recognise itself
        // in a list, and the honest consequence is that it asks everyone.
        registry(dir, """
            {"residents": [
              {"workspaceName": "javata-dev", "port": 8081, "token": "t1"},
              {"workspaceName": "patterns",   "port": 8082, "token": "t2"}
            ]}
            """);

        assertEquals(2, SiblingRegistry.others(dir, null).size(),
            "an unnamed server cannot exclude itself, so it asks everyone");
    }
}
