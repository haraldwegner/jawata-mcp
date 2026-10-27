package org.jawata.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindFieldWritesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindFieldWritesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindFieldWritesTool tool;
    private JdtServiceImpl service;
    private ObjectMapper objectMapper;
    private String refactoringTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        tool = new FindFieldWritesTool(() -> service);
        objectMapper = new ObjectMapper();
        Path projectPath = helper.getFixturePath("simple-maven");
        refactoringTargetPath = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("finds field writes with complete response")
    void findsFieldWritesComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 13);  // userName field
        args.put("column", 19);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("userName", data.get("field"));
        assertNotNull(data.get("declaringType"));
        assertNotNull(data.get("totalWriteLocations"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> writes = (List<Map<String, Object>>) data.get("writeLocations");
        assertNotNull(writes);
        // REL44-22: asserted, not guarded — the field is written in the fixture, and an `if`
        // let a search that found no write pass.
        assertFalse(writes.isEmpty(), "the fixture writes this field: " + data);
        Map<String, Object> write = writes.get(0);
        assertNotNull(write.get("line"));
        assertEquals("WRITE", write.get("accessType"));
    }

    @Test @DisplayName("Stage 11 (dogfood): a match with a resource and no compilation unit is not reported as a write")
    void aMatchWithoutACompilationUnitIsNotReportedAsAWrite() throws Exception {
        // The dogfood run listed the project's own folder as a write of a field, with no line and no column: a match whose element
        // is not in any source file still carries a resource. Built directly, because which search produces one depends on the
        // project's layout; what the tool must do with one does not.
        org.eclipse.jdt.core.IJavaProject project = service.allProjects().iterator().next().javaProject();
        org.eclipse.jdt.core.search.SearchMatch match = new org.eclipse.jdt.core.search.SearchMatch(project,
            org.eclipse.jdt.core.search.SearchMatch.A_ACCURATE, 0, 1,
            org.eclipse.jdt.core.search.SearchEngine.getDefaultSearchParticipant(), project.getProject());
        java.lang.reflect.Method createWriteInfo = FindFieldWritesTool.class.getDeclaredMethod("createWriteInfo",
            org.eclipse.jdt.core.search.SearchMatch.class, org.jawata.core.IJdtService.class);
        createWriteInfo.setAccessible(true);

        Object row = createWriteInfo.invoke(tool, match, service);

        assertNull(row, "a write a caller cannot open is not a write: " + row);
    }

    @Test @DisplayName("supports maxResults parameter")
    void supportsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 13);
        args.put("column", 19);
        args.put("maxResults", 1);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<?> writes = (List<?>) getData(r).get("writeLocations");
        assertTrue(writes.size() <= 1);
    }

    @Test @DisplayName("requires filePath, line, column parameters")
    void requiresParameters() {
        ObjectNode noFile = objectMapper.createObjectNode();
        noFile.put("line", 13);
        noFile.put("column", 19);
        assertFalse(tool.execute(noFile).isSuccess());

        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", refactoringTargetPath);
        noLine.put("column", 19);
        assertFalse(tool.execute(noLine).isSuccess());
    }

    @Test @DisplayName("Sprint 14 (bugs.md #12): non-field position now returns success with nearbyFieldCandidates instead of a hard refusal")
    void nonFieldPosition_returnsNearbyCandidates() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 20);  // a method, not a field
        args.put("column", 16);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(),
            "non-field position now succeeds with graceful-degradation candidate hints; got: " + r.getError());
        Map<String, Object> data = getData(r);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> writes = (List<Map<String, Object>>) data.get("writeLocations");
        assertTrue(writes.isEmpty(), "writeLocations must be empty when no field resolves");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) data.get("nearbyFieldCandidates");
        assertNotNull(candidates, "nearbyFieldCandidates must be present (even if empty)");
        String note = (String) data.get("note");
        assertNotNull(note, "explanatory note must be present");
        assertTrue(note.contains("not a field") || note.contains("No symbol"),
            "note must explain why we fell back to candidates; got: " + note);
    }

    @Test @DisplayName("Sprint 14 (bugs.md #12): position landing on a field still returns its write locations (no regression)")
    void fieldPosition_stillReturnsWriteLocations() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 13);
        args.put("column", 19);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> data = getData(r);
        assertEquals("userName", data.get("field"));
    }

    @Test @DisplayName("Sprint 14 (bugs.md #12): schema description discloses the position-based contract + v1.8.0 FQN overload hint")
    void schemaDescription_disclosesPositionContract() {
        String desc = tool.getDescription();
        assertTrue(desc.contains("filePath, line, column"),
            "description must spell out the (filePath, line, column) triple; got:\n" + desc);
        assertTrue(desc.contains("ZERO-BASED"),
            "description must keep the zero-based coordinate warning; got:\n" + desc);
        assertTrue(desc.contains("v1.8.0"),
            "description must flag the upcoming v1.8.0 FQN overload; got:\n" + desc);
    }

    @Test @DisplayName("Sprint 14 Phase B.2 (bugs.md #12 capability half): FQN form via 'symbol' param resolves a field and runs the search")
    void fqnForm_fieldFqn_runsWriteSearch() {
        ObjectNode args = objectMapper.createObjectNode();
        // HelloWorld has `private String greeting;` set in two constructors + setGreeting
        args.put("symbol", "com.example.HelloWorld#greeting");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "FQN field form must succeed for a known field; got: " + r.getError());
        Map<String, Object> data = getData(r);
        assertEquals("greeting", data.get("field"));
    }
}
