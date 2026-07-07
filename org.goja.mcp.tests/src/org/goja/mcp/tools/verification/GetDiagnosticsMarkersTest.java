package org.goja.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.core.workspace.StrictDiskSync;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.GetDiagnosticsTool;
import org.goja.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 21e (item C) — get_diagnostics honesty: {@code cu.reconcile()} returns a null
 * AST for non-working-copies, so the tool NEVER reported problems for closed files.
 * Now it reads the file's post-build PROBLEM markers (per-call fresh since 21d's
 * build-on-change guard) — an externally written broken file, never opened, finally
 * shows its syntax error; the clean fixture still reports zero.
 */
class GetDiagnosticsMarkersTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @Test
    @DisplayName("THE gap case: a closed file's PROBLEM marker appears in get_diagnostics")
    void closed_file_problem_marker_is_reported() throws Exception {
        // The JavaBuilder never runs in this Tycho test runtime (CompileWorkspaceToolTest
        // proves its marker path with a SYNTHETIC marker for the same reason) — the
        // real-compiler end-to-end case runs on the deployed product at the sprint's
        // live-wire gate. This test pins MY half: reconcile yields a null AST for the
        // closed file, and the tool now reads the file's markers instead of reporting 0.
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        ToolRegistry registry = new ToolRegistry();
        registry.register(new GetDiagnosticsTool(() -> service));
        registry.setDiskSync(new StrictDiskSync(() -> service));

        // Prime the guard (first call reconciles the new root once).
        assertTrue(registry.callTool("get_diagnostics", mapper.createObjectNode()).isSuccess());

        // External write of a file — never opened, no working copy anywhere.
        Path broken = service.getProjectRoot()
            .resolve("src/main/java/com/example/Broken.java");
        Files.writeString(broken,
            "package com.example;\n\npublic class Broken {\n    void m() { int x = ; }\n}\n");
        // One dispatch pass so the 21d guard folds the new file into the workspace.
        assertTrue(registry.callTool("get_diagnostics", mapper.createObjectNode()).isSuccess());

        // Attach the PROBLEM marker the compiler would attach in the deployed runtime.
        org.eclipse.core.resources.IResource res =
            service.getCompilationUnit(broken).getResource();
        org.eclipse.core.resources.IMarker marker =
            res.createMarker(org.eclipse.core.resources.IMarker.PROBLEM);
        marker.setAttribute(org.eclipse.core.resources.IMarker.SEVERITY,
            org.eclipse.core.resources.IMarker.SEVERITY_ERROR);
        marker.setAttribute(org.eclipse.core.resources.IMarker.MESSAGE,
            "Syntax error on token \"=\", expression expected after this token");
        marker.setAttribute(org.eclipse.core.resources.IMarker.LINE_NUMBER, 4); // 1-based
        marker.setAttribute(org.eclipse.core.resources.IMarker.CHAR_START, 58);
        marker.setAttribute(org.eclipse.core.resources.IMarker.CHAR_END, 59);

        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", broken.toString());
        ToolResponse r = registry.callTool("get_diagnostics", args);
        assertTrue(r.isSuccess(), "diagnostics call succeeds: " + r.getError());

        Map<String, Object> d = data(r);
        assertEquals(1, d.get("errorCount"), "closed-file error reported: " + d);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diags = (List<Map<String, Object>>) d.get("diagnostics");
        Map<String, Object> first = diags.get(0);
        assertEquals("error", first.get("severity"));
        assertTrue(String.valueOf(first.get("filePath")).contains("Broken.java"));
        assertEquals(3, first.get("line"), "marker line is 1-based, tool is 0-based");
        assertEquals(58, first.get("startOffset"));
        assertEquals(59, first.get("endOffset"));
        assertTrue(String.valueOf(first.get("message")).contains("Syntax error"));
    }

    @Test
    @DisplayName("clean fixture still reports zero errors through the marker path")
    void clean_fixture_reports_zero_errors() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        ToolRegistry registry = new ToolRegistry();
        registry.register(new GetDiagnosticsTool(() -> service));
        registry.setDiskSync(new StrictDiskSync(() -> service));

        ObjectNode args = mapper.createObjectNode();
        args.put("severity", "error");
        ToolResponse r = registry.callTool("get_diagnostics", args);
        assertTrue(r.isSuccess());
        Map<String, Object> d = data(r);
        assertEquals(0, d.get("errorCount"), "clean fixture stays zero: " + d);
        assertTrue((int) d.get("filesChecked") > 0, "whole-project walk unchanged");
    }
}
