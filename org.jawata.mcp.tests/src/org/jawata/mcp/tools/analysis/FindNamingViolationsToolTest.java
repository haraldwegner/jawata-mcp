package org.jawata.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindNamingViolationsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindNamingViolationsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindNamingViolationsTool tool;
    private ObjectMapper objectMapper;
    /** Kept so a test can read the FILE a row points at — the only way to see a base error. */
    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        tool = new FindNamingViolationsTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    /**
     * THE REPORTED LINE IS 1-BASED — checked against the file, and SCOPED to the rows where
     * that claim is true.
     *
     * <p>This tool emitted {@code getLineNumber(...) - 1} at all six of its emission sites, so
     * every {@code naming} row a caller read sat one line above its subject. It shares a
     * response with {@code findings} rows built from {@code Finding}, which documents 1-based,
     * so one {@code find_quality_issue} answer carried two bases. C8b round 4 measured it; a
     * sibling merged producer, {@code largeClasses}, never pre-converted, which is what makes
     * 1-based the convention here rather than a preference.</p>
     *
     * <p><b>The scope is the point, and it is why this is not the whole-catalogue check that
     * was written and thrown away.</b> Four of the six sites address {@code
     * node.getStartPosition()} — a declaration's start INCLUDES its javadoc and annotations,
     * so the identifier is legitimately not on that line and "the name is on the line" would
     * report a defect where there is none. The two FIELD sites address the fragment itself,
     * where the name genuinely is at that position. Only those rows are checked, and the rest
     * are counted so a reader can see what is NOT covered rather than assume it is.</p>
     */
    @Test
    @DisplayName("a field violation's line is 1-based: the identifier is on it")
    void theReportedLineIsOneBasedForFieldRows() throws Exception {
        // THE WHOLE PROJECT, and each row read against ITS OWN file. Scoping this to one
        // fixture was the first attempt and its own proof-of-life refused it: that file's
        // three violations are all declaration-start addressed, so the test asserted nothing
        // and said so rather than passing. Which fixture happens to carry a badly-named FIELD
        // is not something this test should depend on.
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess(), "the detector must run; got: " + r.getError());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
            (List<Map<String, Object>>) getData(r).get("violations");
        assertTrue(rows != null && !rows.isEmpty(),
            "PROOF OF LIFE: the project must produce violations, or this checks nothing");

        int checked = 0;
        for (Map<String, Object> row : rows) {
            java.nio.file.Path file = service.getProjectRoot()
                .resolve(String.valueOf(row.get("filePath"))).normalize();
            if (!java.nio.file.Files.exists(file)) {
                continue;
            }
            List<String> lines = java.nio.file.Files.readAllLines(file);
            int line = (Integer) row.get("line");
            String name = String.valueOf(row.get("name"));
            assertTrue(line >= 1 && line <= lines.size(),
                "a 1-based line must be inside the file: " + name + " at " + line);
            checked++;

            String at = lines.get(line - 1).strip();
            // A DECLARATION'S FIRST LINE IS NEVER BLANK, which is what makes this sound in the
            // direction that matters: `getStartPosition()` is the javadoc, annotation or
            // modifier that opens the declaration, so a correct 1-based row always lands on
            // text. One line short lands ABOVE that — on the blank line or closing brace that
            // usually separates members — which is exactly what the audit measured: ten of ten
            // rows on blank lines before the fix.
            assertFalse(at.isEmpty(),
                "'" + name + "' is reported on 1-based line " + line + ", which is BLANK."
                    + " The line below reads: "
                    + (line < lines.size() ? lines.get(line).strip() : "(end of file)")
                    + "\n  A declaration's first line is never blank, so a blank one means the"
                    + " row is one short — the 0-based base this tool emitted at all six"
                    + " sites, while the `findings` rows it shares a response with are 1-based.");

            // Where the tool addresses the FRAGMENT rather than a declaration start, the
            // identifier really is at that position and the stronger claim holds.
            String kind = String.valueOf(row.get("kind"));
            if ("field".equals(kind) || "constant".equals(kind)) {
                assertTrue(at.contains(name),
                    "'" + name + "' is a field row, which addresses the fragment itself, so"
                        + " the identifier must be ON its line; that line reads: " + at);
            }
        }
        int drove = checked;
        assertTrue(checked >= 3,
            () -> "only " + drove + " violation rows were read against their files; with that"
                + " few an empty failure list says nothing about the coordinate base");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Violation Detection Tests ==========

    @Test
    @DisplayName("should find naming violations in file with bad names")
    void findsNamingViolations() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        int totalViolations = (int) data.get("totalViolations");
        assertTrue(totalViolations > 0, "Should find naming violations in DiAndReflectionPatterns.java");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) data.get("violations");
        assertFalse(violations.isEmpty());

        // Verify violation structure
        Map<String, Object> firstViolation = violations.get(0);
        assertNotNull(firstViolation.get("filePath"), "Should include file path");
        assertNotNull(firstViolation.get("line"), "Should include line number");
        assertNotNull(firstViolation.get("elementType"), "Should include element type");
        assertNotNull(firstViolation.get("name"), "Should include the name");
        assertNotNull(firstViolation.get("convention"), "Should include expected convention");
    }

    @Test
    @DisplayName("should detect Bad_Field_Name as field naming violation")
    void detectsBadFieldName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) getData(response).get("violations");

        boolean foundFieldViolation = violations.stream()
            .anyMatch(v -> "Bad_Field_Name".equals(v.get("name")) && "field".equals(v.get("elementType")));
        assertTrue(foundFieldViolation, "Should detect Bad_Field_Name as a field naming violation");
    }

    @Test
    @DisplayName("should detect Bad_Method_Name as method naming violation")
    void detectsBadMethodName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) getData(response).get("violations");

        boolean foundMethodViolation = violations.stream()
            .anyMatch(v -> "Bad_Method_Name".equals(v.get("name")) && "method".equals(v.get("elementType")));
        assertTrue(foundMethodViolation, "Should detect Bad_Method_Name as a method naming violation");
    }

    @Test
    @DisplayName("should detect badConstant as constant naming violation")
    void detectsBadConstantName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) getData(response).get("violations");

        boolean foundConstantViolation = violations.stream()
            .anyMatch(v -> "badConstant".equals(v.get("name")) && "constant".equals(v.get("elementType")));
        assertTrue(foundConstantViolation, "Should detect badConstant as a constant naming violation");
    }

    // ========== Clean File Tests ==========

    @Test
    @DisplayName("should return no violations for well-named file")
    void returnsNoViolationsForCleanFile() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/Calculator.java");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals(0, data.get("totalViolations"));
    }

    // ========== Project-Wide Scan Tests ==========

    @Test
    @DisplayName("should scan all files when no filePath specified")
    void scansAllFilesWhenNoPathSpecified() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        int filesScanned = (int) data.get("filesScanned");
        assertTrue(filesScanned > 1, "Should scan multiple files");
    }
}
