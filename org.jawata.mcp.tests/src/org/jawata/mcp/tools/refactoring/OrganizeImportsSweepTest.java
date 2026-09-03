package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.OrganizeImportsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The broad scopes of {@code organize_imports} — what {@code optimize_imports_workspace}
 * used to be.
 *
 * <p>Sprint 13 shipped the sweep as its own tool with its own import engine. Sprint
 * 28d-rescue folded the scope into this tool and retired that second engine, so these
 * are the same contract tests pointed at the surviving front door: a fixture file is
 * given three unused imports, the sweep removes them, and a second sweep removes
 * nothing.</p>
 *
 * <p>Two things the fold CHANGED are asserted here rather than assumed, because a fold
 * that quietly alters behaviour is the failure this test exists to catch. The default
 * scope is now {@code file}, so a sweep must ASK for its scope — the old tool swept by
 * default. And a sweep is one reversible change: it reports an undo handle, which the
 * old per-file writer had no way to offer.</p>
 */
class OrganizeImportsSweepTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private OrganizeImportsTool tool;
    private ObjectMapper objectMapper;
    private Path bloatedFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        tool = new OrganizeImportsTool(() -> service, new RefactoringChangeCache());
        objectMapper = new ObjectMapper();

        // Mutate an existing fixture file to add bogus imports. Editing in
        // place avoids the linked-folder new-file refresh quirk Sprint 12
        // hit with compile_workspace.
        Path projectRoot = service.allProjects().iterator().next().projectRoot();
        bloatedFile = projectRoot.resolve("src/main/java/com/example/HelloWorld.java");
        String original = Files.readString(bloatedFile, StandardCharsets.UTF_8);
        // Inject 3 unused imports right after the package declaration.
        String mutated = original.replace("package com.example;",
            "package com.example;\n\n"
                + "import java.util.Set;\n"
                + "import java.io.IOException;\n"
                + "import java.util.HashMap;");
        Files.writeString(bloatedFile, mutated, StandardCharsets.UTF_8);
        service.getJavaProject().getProject().refreshLocal(
            org.eclipse.core.resources.IResource.DEPTH_INFINITE,
            new org.eclipse.core.runtime.NullProgressMonitor());
    }

    private ObjectNode args(String scope) {
        ObjectNode a = objectMapper.createObjectNode();
        a.put("scope", scope);
        return a;
    }

    @Test
    @DisplayName("workspace scope removes unused imports across the project")
    void workspaceScope_removesUnusedImports() throws Exception {
        ToolResponse r = tool.execute(args("workspace"));
        assertTrue(r.isSuccess(), "tool must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("workspace", data.get("scope"));
        assertTrue(((Number) data.get("filesProcessed")).intValue() >= 1,
            "expected at least 1 file processed; got: " + data);

        // The 3 unused imports we injected must be removed from HelloWorld.
        String content = Files.readString(bloatedFile, StandardCharsets.UTF_8);
        String diag = "data=" + data + "\n--- file (" + content.length() + " chars) ---\n"
            + content + "\n--- end ---";
        assertTrue(!content.contains("import java.util.Set;"),
            "unused Set must be gone; " + diag);
        assertTrue(!content.contains("import java.util.HashMap;"),
            "unused HashMap must be gone; " + diag);
        assertTrue(!content.contains("import java.io.IOException;"),
            "unused IOException must be gone; " + diag);
    }

    @Test
    @DisplayName("a second sweep on the cleaned workspace removes nothing")
    void idempotent_secondRun_removesNothing() {
        ToolResponse first = tool.execute(args("workspace"));
        assertTrue(first.isSuccess(), "first run must succeed; got: " + first.getError());

        ToolResponse second = tool.execute(args("workspace"));
        assertTrue(second.isSuccess(), "second run must succeed; got: " + second.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) second.getData();
        assertNotNull(data);
        assertEquals(0, ((Number) data.get("importsRemoved")).intValue(),
            "second run must not remove any imports; data=" + data);
    }

    @Test
    @DisplayName("project scope works the same way")
    void projectScope_removesUnusedImports() throws Exception {
        ToolResponse r = tool.execute(args("project"));
        assertTrue(r.isSuccess(), "tool must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("project", data.get("scope"));
        String content = Files.readString(bloatedFile, StandardCharsets.UTF_8);
        assertTrue(!content.contains("import java.util.Set;"),
            "unused Set must be gone; data=" + data);
    }

    @Test
    @DisplayName("a sweep is ONE reversible change, which the retired tool could not offer")
    void sweepIsReversible() {
        ToolResponse r = tool.execute(args("workspace"));
        assertTrue(r.isSuccess(), "tool must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("undoChangeId"),
            "the sweep must return an undo handle — the tool this replaced wrote every"
                + " file in place through a working copy, so a sweep across a whole"
                + " workspace had no way back at all. data=" + data);
        assertTrue(data.get("changedFiles") instanceof List<?> changed && !changed.isEmpty(),
            "and it must name what it changed; data=" + data);
        assertTrue(data.containsKey("skippedFiles"),
            "a sweep always reports its skips, even when there are none — a short list"
                + " and a clean list must not look identical. data=" + data);
    }

    @Test
    @DisplayName("the default scope is a single file, so a sweep must ask for itself")
    void defaultScopeIsFile() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(!r.isSuccess(),
            "with no scope and no filePath this is a file-scope call missing its file,"
                + " NOT a silent whole-workspace mutation");
        assertTrue(String.valueOf(r.getError()).contains("scope=workspace"),
            "and the refusal must name the way to ask for a sweep; got: " + r.getError());
    }
}
