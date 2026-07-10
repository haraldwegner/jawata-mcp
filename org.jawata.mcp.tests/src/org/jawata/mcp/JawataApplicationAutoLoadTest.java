package org.jawata.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for bug #5 (v1.7.1) — JawataApplication.findWorkspaceJson must
 * locate workspace.json both in the OSGi data dir directly AND one level
 * up to handle the JawataLauncher session-isolation UUID subdir.
 */
class JawataApplicationAutoLoadTest {

    @Test
    @DisplayName("findWorkspaceJson returns immediate-dir match when workspace.json sits at the OSGi data dir (direct invocation, no session subdir)")
    void findWorkspaceJson_immediateMatch_returnsThatPath(@TempDir Path dataDir) throws Exception {
        Path workspaceJson = dataDir.resolve("workspace.json");
        Files.writeString(workspaceJson, "{\"name\":\"T\",\"projects\":[],\"version\":1}");

        Path found = JawataApplication.findWorkspaceJson(dataDir);

        assertNotNull(found, "should find workspace.json directly in the data dir");
        assertEquals(workspaceJson.toRealPath(), found.toRealPath());
    }

    @Test
    @DisplayName("findWorkspaceJson walks up one level when OSGi data dir is a JawataLauncher session subdir (bug #5 fix)")
    void findWorkspaceJson_walksUpToParent_findsWorkspaceJsonAtWorkspaceRoot(@TempDir Path workspaceRoot) throws Exception {
        // Simulate the JawataLauncher layout: workspace.json at the root,
        // OSGi instance area at workspace/<uuid>/.
        Path workspaceJson = workspaceRoot.resolve("workspace.json");
        Files.writeString(workspaceJson, "{\"name\":\"T\",\"projects\":[],\"version\":1}");
        Path sessionDir = Files.createDirectory(workspaceRoot.resolve("abc12345"));

        Path found = JawataApplication.findWorkspaceJson(sessionDir);

        assertNotNull(found, "should walk up one level and find workspace.json at workspace root");
        assertEquals(workspaceJson.toRealPath(), found.toRealPath());
    }

    @Test
    @DisplayName("findWorkspaceJson prefers immediate-dir match over parent-dir match when both exist (no ambiguity)")
    void findWorkspaceJson_bothLevelsExist_prefersImmediate(@TempDir Path workspaceRoot) throws Exception {
        Files.writeString(workspaceRoot.resolve("workspace.json"),
            "{\"name\":\"PARENT\",\"projects\":[],\"version\":1}");
        Path sessionDir = Files.createDirectory(workspaceRoot.resolve("abc12345"));
        Path immediateJson = sessionDir.resolve("workspace.json");
        Files.writeString(immediateJson, "{\"name\":\"IMMEDIATE\",\"projects\":[],\"version\":1}");

        Path found = JawataApplication.findWorkspaceJson(sessionDir);

        assertNotNull(found);
        assertEquals(immediateJson.toRealPath(), found.toRealPath(),
            "immediate-dir match wins over parent-dir match");
    }

    @Test
    @DisplayName("findWorkspaceJson returns null when neither immediate dir nor parent dir has workspace.json")
    void findWorkspaceJson_neitherLevelHasFile_returnsNull(@TempDir Path workspaceRoot) throws Exception {
        Path sessionDir = Files.createDirectory(workspaceRoot.resolve("abc12345"));

        Path found = JawataApplication.findWorkspaceJson(sessionDir);

        assertNull(found);
    }

    @Test
    @DisplayName("findWorkspaceJson returns null on null dataDir input")
    void findWorkspaceJson_nullInput_returnsNull() {
        assertNull(JawataApplication.findWorkspaceJson(null));
    }

    @Test
    @DisplayName("findWorkspaceJson returns null when only a directory (not file) named 'workspace.json' exists at either level")
    void findWorkspaceJson_directoryNotFile_returnsNull(@TempDir Path workspaceRoot) throws Exception {
        // A directory named workspace.json should not satisfy isRegularFile.
        Files.createDirectory(workspaceRoot.resolve("workspace.json"));
        Path sessionDir = Files.createDirectory(workspaceRoot.resolve("abc12345"));
        Files.createDirectory(sessionDir.resolve("workspace.json"));

        Path found = JawataApplication.findWorkspaceJson(sessionDir);

        assertNull(found, "directory should not match isRegularFile probe");
    }

    // --- Sprint 21a (item A): stable workspace root resolution ----------------------------

    @Test
    @DisplayName("resolveWorkspaceRoot prefers the launcher-published jawata.workspace.root property")
    void resolveWorkspaceRoot_prefersLauncherProperty(@TempDir Path real, @TempDir Path other) {
        System.setProperty("jawata.workspace.root", real.toString());
        try {
            assertEquals(real, JawataApplication.resolveWorkspaceRoot(other));
        } finally {
            System.clearProperty("jawata.workspace.root");
        }
    }

    @Test
    @DisplayName("resolveWorkspaceRoot walks up to the workspace.json dir; falls back to dataDir; null-safe")
    void resolveWorkspaceRoot_walkUpAndFallback(@TempDir Path root, @TempDir Path bareRoot)
            throws Exception {
        Files.writeString(root.resolve("workspace.json"), "{\"name\":\"T\",\"projects\":[],\"version\":1}");
        Path sessionDir = Files.createDirectory(root.resolve("abc12345"));
        assertEquals(root.toRealPath(),
            JawataApplication.resolveWorkspaceRoot(sessionDir).toRealPath(),
            "session dir resolves to its workspace.json parent");

        // A root with no workspace.json anywhere (own @TempDir — the parent must not have one).
        Path bare = Files.createDirectory(bareRoot.resolve("bare"));
        assertEquals(bare, JawataApplication.resolveWorkspaceRoot(bare),
            "no workspace.json anywhere → the data dir itself");

        assertNull(JawataApplication.resolveWorkspaceRoot(null));
    }

    // --- Sprint 21a (item C): default memory roots -----------------------------------------

    @Test
    @DisplayName("defaultMemoryRoots layers CLAUDE.md up to $HOME, adds memory-dir convention + extra roots")
    void defaultMemoryRoots_layering(@TempDir Path home) throws Exception {
        Path proj = Files.createDirectories(home.resolve("CursorProjects").resolve("proj"));
        Files.writeString(home.resolve("CLAUDE.md"), "home rules");
        Files.writeString(home.resolve("CursorProjects").resolve("CLAUDE.md"), "dir rules");
        Files.writeString(proj.resolve("CLAUDE.md"), "project rules");
        Files.createDirectories(home.resolve(".claude"));
        Files.writeString(home.resolve(".claude").resolve("CLAUDE.md"), "global rules");
        Path memDir = Files.createDirectories(home.resolve(".claude").resolve("projects")
            .resolve(JawataApplication.sanitizeProjectDir(proj)).resolve("memory"));
        Path extra = Files.createDirectories(home.resolve("extra-root"));

        var roots = JawataApplication.defaultMemoryRoots(home, java.util.List.of(proj), extra.toString());

        assertTrue(roots.contains(extra), "explicit extra root first");
        assertTrue(roots.contains(home.resolve(".claude").resolve("CLAUDE.md")), "global CLAUDE.md");
        assertTrue(roots.contains(proj.resolve("CLAUDE.md")), "project CLAUDE.md");
        assertTrue(roots.contains(home.resolve("CursorProjects").resolve("CLAUDE.md")), "ancestor CLAUDE.md");
        assertTrue(roots.contains(home.resolve("CLAUDE.md")), "home-level CLAUDE.md");
        assertTrue(roots.contains(memDir), "Claude per-project memory dir convention");
        assertEquals(6, roots.size(), "nothing beyond the existing layered set");
    }

    @Test
    @DisplayName("Sprint 21b (item C2): autofind — ALL Claude memory dirs + Cursor & friends")
    void defaultMemoryRoots_autofind(@TempDir Path home) throws Exception {
        Path proj = Files.createDirectories(home.resolve("CursorProjects").resolve("proj"));

        // A Claude memory dir belonging to a DIFFERENT project (not in this workspace):
        // the store is user-level, so discovery must be too.
        Path otherMem = Files.createDirectories(home.resolve(".claude").resolve("projects")
            .resolve("-somewhere-else-project").resolve("memory"));
        // A projects entry WITHOUT a memory dir must not contribute anything.
        Files.createDirectories(home.resolve(".claude").resolve("projects").resolve("-memoryless"));

        // The other agents' per-project files.
        Path cursorRules = Files.createDirectories(proj.resolve(".cursor").resolve("rules"));
        Path cursorLegacy = Files.writeString(proj.resolve(".cursorrules"), "cursor legacy rules");
        Path agentsMd = Files.writeString(proj.resolve("AGENTS.md"), "# agents");
        Path copilot = Files.createDirectories(proj.resolve(".github"))
            .resolve("copilot-instructions.md");
        Files.writeString(copilot, "copilot rules");
        Path windsurf = Files.writeString(proj.resolve(".windsurfrules"), "windsurf rules");

        var roots = JawataApplication.defaultMemoryRoots(home, java.util.List.of(proj), null);

        assertTrue(roots.contains(otherMem), "every ~/.claude/projects/*/memory dir, not just this workspace's");
        assertTrue(roots.contains(cursorRules), "Cursor project rules dir");
        assertTrue(roots.contains(cursorLegacy), "legacy .cursorrules");
        assertTrue(roots.contains(agentsMd), "AGENTS.md convention");
        assertTrue(roots.contains(copilot), "GitHub Copilot instructions");
        assertTrue(roots.contains(windsurf), ".windsurfrules");
        assertFalse(roots.contains(home.resolve(".claude").resolve("projects")
            .resolve("-memoryless").resolve("memory")), "no phantom memory dirs");
    }

    @Test
    @DisplayName("readProjects parses all project paths; missing/broken file → empty list")
    void readProjects_parsesAll(@TempDir Path dir) throws Exception {
        Path json = dir.resolve("workspace.json");
        Files.writeString(json, "{\"name\":\"w\",\"projects\":[\"/a/one\",\"/b/two\"],\"version\":1}");
        assertEquals(java.util.List.of(Path.of("/a/one"), Path.of("/b/two")),
            JawataApplication.readProjects(json));
        assertTrue(JawataApplication.readProjects(dir.resolve("nope.json")).isEmpty());
    }

    // --- Sprint 21a (item B): provenance facets from workspace.json -----------------------

    @Test
    @DisplayName("readProvenance returns workspace name + first project path")
    void readProvenance_nameAndFirstProject(@TempDir Path dir) throws Exception {
        Path json = dir.resolve("workspace.json");
        Files.writeString(json,
            "{\"name\":\"jawata\",\"projects\":[\"/home/x/CursorProjects/jawata-mcp\"],\"version\":1}");

        String[] prov = JawataApplication.readProvenance(json);

        assertEquals("jawata", prov[0]);
        assertEquals("/home/x/CursorProjects/jawata-mcp", prov[1]);
    }

    @Test
    @DisplayName("readProvenance falls back to the parent dir name when 'name' is missing; nulls never throw")
    void readProvenance_fallbacks(@TempDir Path root) throws Exception {
        Path wsDir = Files.createDirectory(root.resolve("my-workspace"));
        Path json = wsDir.resolve("workspace.json");
        Files.writeString(json, "{\"projects\":[],\"version\":1}");

        String[] prov = JawataApplication.readProvenance(json);
        assertEquals("my-workspace", prov[0], "dir name stands in for a missing 'name'");
        assertNull(prov[1], "no projects → null projectId");

        String[] missing = JawataApplication.readProvenance(root.resolve("no-such.json"));
        assertNull(missing[0]);
        assertNull(missing[1]);
    }
}
