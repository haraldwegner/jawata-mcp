package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.core.workspace.StrictDiskSync;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindPatternUsagesTool;
import org.jawata.mcp.tools.FindRefsTool;
import org.jawata.mcp.tools.GetCallHierarchyTool;
import org.jawata.mcp.tools.GetDiagnosticsTool;
import org.jawata.mcp.tools.SearchSymbolsTool;
import org.jawata.mcp.tools.ToolRegistry;
import org.jawata.mcp.tools.shared.FieldsProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 23 (D9, C13 decision B — Harald 2026-07-13) — the `fields` per-row
 * projection on the five list-heavy tools: search_symbols, find_references,
 * get_call_hierarchy, find_pattern_usages, get_diagnostics. No fields param =
 * full rows (back-compatible); with fields = each row carries exactly the
 * requested keys the row has; malformed fields = invalid-parameter, never a
 * silent full response.
 */
class FieldsProjectionTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper om = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(ToolResponse r, String key) {
        return (List<Map<String, Object>>) data(r).get(key);
    }

    private static void assertProjected(List<Map<String, Object>> rows, String... allowed) {
        assertFalse(rows.isEmpty(), "the projection proof needs at least one row");
        Set<String> allowedSet = Set.of(allowed);
        for (Map<String, Object> row : rows) {
            assertTrue(allowedSet.containsAll(row.keySet()),
                "row keys must be a subset of " + allowedSet + "; got: " + row.keySet());
        }
    }

    // ------------------------------------------------- helper semantics

    @Test
    @DisplayName("helper: absent param = full rows; projection keeps caller order, skips missing keys")
    void helperSemantics() {
        assertNull(FieldsProjection.parse(om.createObjectNode()), "absent fields → null");
        assertNull(FieldsProjection.parse(null), "null arguments → null");

        List<Map<String, Object>> rows = List.of(
            new java.util.LinkedHashMap<>(Map.of("a", 1, "b", 2, "c", 3)),
            new java.util.LinkedHashMap<>(Map.of("a", 4)));
        assertEquals(rows, FieldsProjection.project(rows, null), "null fields = unchanged rows");

        List<Map<String, Object>> projected = FieldsProjection.project(rows, List.of("c", "a"));
        assertEquals(List.of("c", "a"), List.copyOf(projected.get(0).keySet()),
            "caller-named order, not row order");
        assertEquals(Map.of("a", 4), projected.get(1),
            "a key the row does not carry is skipped, not nulled");

        ObjectNode notArray = om.createObjectNode().put("fields", "filePath");
        assertThrows(IllegalArgumentException.class, () -> FieldsProjection.parse(notArray),
            "a bare string is rejected");
        ObjectNode empty = om.createObjectNode();
        empty.putArray("fields");
        assertThrows(IllegalArgumentException.class, () -> FieldsProjection.parse(empty),
            "an empty array is rejected");
        ObjectNode nonString = om.createObjectNode();
        nonString.putArray("fields").add(7);
        assertThrows(IllegalArgumentException.class, () -> FieldsProjection.parse(nonString),
            "non-string entries are rejected");
    }

    // ------------------------------------------------- the five tools live

    @Test
    @DisplayName("search_symbols: fields=[name,line] rows carry only those keys; no fields = full rows")
    void searchSymbols() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        SearchSymbolsTool tool = new SearchSymbolsTool(() -> service);

        ObjectNode args = om.createObjectNode().put("query", "DocLinked").put("kind", "Class");
        args.putArray("fields").add("name").add("line");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        List<Map<String, Object>> results = rows(r, "results");
        assertProjected(results, "name", "line");
        assertTrue(results.stream().anyMatch(row -> "DocLinked".equals(row.get("name"))),
            "the projected rows still carry the values: " + results);

        ToolResponse full = tool.execute(
            om.createObjectNode().put("query", "DocLinked").put("kind", "Class"));
        assertTrue(full.isSuccess(), "got: " + full.getError());
        Map<String, Object> fullRow = rows(full, "results").get(0);
        assertTrue(fullRow.keySet().containsAll(Set.of("name", "kind", "qualifiedName")),
            "back-compat: no fields param = the full row; got: " + fullRow.keySet());
    }

    @Test
    @DisplayName("find_references front door: fields=[filePath,line]; malformed fields = invalid parameter")
    void findReferences() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        FindRefsTool tool = new FindRefsTool(() -> service);

        ObjectNode args = om.createObjectNode()
            .put("kind", "references")
            .put("symbol", "com.example.DocLinked#callee");
        args.putArray("fields").add("filePath").add("line");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertProjected(rows(r, "references"), "filePath", "line");

        ObjectNode bad = om.createObjectNode()
            .put("kind", "references")
            .put("symbol", "com.example.DocLinked#callee")
            .put("fields", "filePath");
        ToolResponse rejected = tool.execute(bad);
        assertFalse(rejected.isSuccess(), "a malformed fields param must be rejected");
        assertTrue(String.valueOf(rejected.getError()).contains("fields"),
            "the error names the offending param: " + rejected.getError());
    }

    @Test
    @DisplayName("get_call_hierarchy front door: incoming callers project to fields=[callerMethod]")
    void callHierarchy() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        GetCallHierarchyTool tool = new GetCallHierarchyTool(() -> service);

        ObjectNode args = om.createObjectNode()
            .put("direction", "incoming")
            .put("symbol", "com.example.DocLinked#callee");
        args.putArray("fields").add("callerMethod");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        List<Map<String, Object>> callers = rows(r, "callers");
        assertProjected(callers, "callerMethod");
        assertEquals("realCaller", callers.get(0).get("callerMethod"), "got: " + callers);
    }

    @Test
    @DisplayName("find_pattern_usages: annotation usages project to fields=[filePath,line]")
    void patternUsages() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        FindPatternUsagesTool tool = new FindPatternUsagesTool(() -> service);

        ObjectNode args = om.createObjectNode()
            .put("kind", "annotation")
            .put("query", "org.junit.jupiter.api.Test");
        args.putArray("fields").add("filePath").add("line");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertProjected(rows(r, "usages"), "filePath", "line");
    }

    @Test
    @DisplayName("get_diagnostics: a real compiler error's row projects to fields=[filePath,severity]")
    void diagnostics() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("compile-clean");
        ToolRegistry registry = new ToolRegistry();
        registry.register(new GetDiagnosticsTool(() -> service));
        registry.setDiskSync(new StrictDiskSync(() -> service));

        // Prime the guard, write a broken file externally, let the builder see it
        // (the GetDiagnosticsMarkersTest recipe — real markers, no synthetic rows).
        assertTrue(registry.callTool("get_diagnostics", om.createObjectNode()).isSuccess());
        Path broken = service.getProjectRoot()
            .resolve("src/main/java/com/example/Broken.java");
        Files.writeString(broken,
            "package com.example;\n\npublic class Broken {\n    void m() { int x = ; }\n}\n");
        assertTrue(registry.callTool("get_diagnostics", om.createObjectNode()).isSuccess());

        ObjectNode args = om.createObjectNode().put("filePath", broken.toString());
        args.putArray("fields").add("filePath").add("severity");
        ToolResponse r = registry.callTool("get_diagnostics", args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        List<Map<String, Object>> diags = rows(r, "diagnostics");
        assertProjected(diags, "filePath", "severity");
        assertEquals("error", diags.get(0).get("severity"), "got: " + diags);
        assertTrue(((Number) data(r).get("errorCount")).intValue() >= 1,
            "the summary counters survive the projection: " + data(r));
    }
}
