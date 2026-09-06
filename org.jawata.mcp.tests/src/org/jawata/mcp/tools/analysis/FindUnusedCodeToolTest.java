package org.jawata.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindUnusedCodeTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindUnusedCodeToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindUnusedCodeTool tool;
    private ObjectMapper objectMapper;
    /** Kept so a test can read the FILE a row points at, which is the only way to see a base error. */
    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        tool = new FindUnusedCodeTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    /**
     * THE REPORTED LINE IS 1-BASED, CHECKED AGAINST THE FILE ITSELF.
     *
     * <p>This tool emitted {@code getLineNumber(...) - 1} and a raw {@code getColumnNumber},
     * so its rows were 0-based while {@code Finding} — the record every other producer fills —
     * documents 1-based. Nothing noticed for as long as nothing joined these rows to a door.
     * C8b round 2 widened the cure join to read this tool's rows, and the single conversion
     * site then subtracted a SECOND time, so every {@code unused} cure named the line above
     * the member.</p>
     *
     * <p><b>Why the assertion is absolute rather than relative.</b> Every check that builds an
     * address from a row and asks a door about it converts by the same rule it is trying to
     * test, so it cannot see a base error — both sides move together and the wrong address is
     * reported as accepted. Opening the file can see it.</p>
     *
     * <p><b>And why this check lives HERE rather than over the whole catalogue.</b> The
     * general form — "a finding's line contains the symbol it names" — was written, run, and
     * removed: it fails for eleven kinds that are correct, because a finding often points at a
     * CONSTRUCT rather than a declaration ({@code switch_statements} at the {@code switch},
     * {@code message_chains} at the chain, {@code loops} at the {@code for}), and
     * {@code encapsulation} reports at the class while naming a field. This tool is different
     * by construction: it addresses {@code getName()}, so the name IS on the line, and the
     * claim is true here and nowhere near universal.</p>
     */
    @Test @DisplayName("the reported line is 1-based: the member's name is on it")
    void theReportedLineIsOneBased() throws Exception {
        String relative = "src/main/java/com/example/UnusedCode.java";
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", relative);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "the detector must run; got: " + r.getError());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
            (List<Map<String, Object>>) getData(r).get("unusedItems");
        assertTrue(items != null && !items.isEmpty(),
            "PROOF OF LIFE: the fixture must name something, or the loop below checks nothing");

        java.nio.file.Path file = service.getProjectRoot().resolve(relative).normalize();
        List<String> lines = java.nio.file.Files.readAllLines(file);
        for (Map<String, Object> item : items) {
            Object rawLine = item.get("line");
            assertInstanceOf(Integer.class, rawLine, "every row must carry a line: " + item);
            int line = (Integer) rawLine;
            String name = String.valueOf(item.get("name"));
            assertTrue(line >= 1 && line <= lines.size(),
                "a 1-based line must be inside the file: " + name + " at " + line
                    + ", file has " + lines.size() + " lines");
            assertTrue(lines.get(line - 1).contains(name),
                "'" + name + "' is reported on 1-based line " + line + ", which reads: "
                    + lines.get(line - 1).strip() + "\n  the line BELOW reads: "
                    + (line < lines.size() ? lines.get(line).strip() : "(end of file)")
                    + "\n  a row one line short of its member is the 0-based base this tool"
                    + " used to emit, and every cure rendered from it points there too.");
        }
    }

    @Test @DisplayName("finds unused code comprehensively")
    void findsUnusedCodeComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/UnusedCode.java");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertNotNull(data.get("unusedItems"));
        assertNotNull(data.get("unusedFieldCount"));
        assertNotNull(data.get("unusedMethodCount"));
        assertNotNull(data.get("totalUnused"));
    }

    @Test @DisplayName("supports filtering options")
    void supportsFilteringOptions() {
        ObjectNode noFields = objectMapper.createObjectNode();
        noFields.put("filePath", "src/main/java/com/example/UnusedCode.java");
        noFields.put("includeFields", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items1 = (List<Map<String, Object>>) getData(tool.execute(noFields)).get("unusedItems");
        assertFalse(items1.stream().anyMatch(i -> "Field".equals(i.get("kind"))));

        ObjectNode noMethods = objectMapper.createObjectNode();
        noMethods.put("filePath", "src/main/java/com/example/UnusedCode.java");
        noMethods.put("includeMethods", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items2 = (List<Map<String, Object>>) getData(tool.execute(noMethods)).get("unusedItems");
        assertFalse(items2.stream().anyMatch(i -> "Method".equals(i.get("kind"))));
    }

    @Test @DisplayName("analyzes whole project")
    void analyzesWholeProject() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        assertNotNull(getData(r).get("totalUnused"));
    }
}
