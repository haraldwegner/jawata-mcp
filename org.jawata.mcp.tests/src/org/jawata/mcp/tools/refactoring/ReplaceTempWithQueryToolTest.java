package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ExtractTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue, row 58 — Replace Temp with Query, through {@code extract}.
 *
 * <p>It is COMPOSED, so what is worth asserting is what composition adds rather than what
 * either half does: that both halves ran against the same file in sequence, that the second
 * found its target after the first had moved it, and that the pair comes back through ONE
 * undo handle. The second of those is the one that would break silently — a recipe step
 * carrying a stale position lands mid-token and produces something that may still
 * compile.</p>
 */
class ReplaceTempWithQueryToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ExtractTool tool;
    private ObjectMapper mapper;
    private Path fixture;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new ExtractTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        fixture = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example/TempHolder.java");
    }

    private String read() throws Exception {
        return Files.readString(fixture, StandardCharsets.UTF_8);
    }

    private int lineOf(String marker) throws Exception {
        String[] lines = read().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                return i;
            }
        }
        throw new AssertionError("PROOF OF LIFE: the fixture no longer has " + marker);
    }

    private ToolResponse replace(String marker, String methodName) throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "temp_to_query");
        args.put("filePath", fixture.toString());
        args.put("line", lineOf(marker));
        args.put("column", 12);
        if (methodName != null) {
            args.put("methodName", methodName);
        }
        return tool.execute(args);
    }

    @Test
    @DisplayName("the temp becomes a query, every use reads it, and one undo handle covers the pair")
    void theTempBecomesAQuery() throws Exception {
        assertTrue(read().contains("int basePrice = quantity * 7;"),
            "PROOF OF LIFE: the temp must exist before this runs");

        ToolResponse r = replace("int basePrice = quantity * 7;", "basePrice");
        assertTrue(r.isSuccess(), "the recipe must run; got: " + r.getError());

        String after = read();
        assertTrue(after.contains("private int basePrice()"),
            "the initializer became a query:\n" + after);
        assertFalse(after.contains("int basePrice = quantity * 7;"),
            "and the temp is gone:\n" + after);
        // ALL THREE uses — the ternary reads the temp in its condition and in both arms.
        // Their presence is the evidence that step two found its target after step one had
        // rewritten the line; a stale position would have inlined nothing, or landed
        // somewhere else entirely.
        assertEquals(4, countOf(after, "basePrice()"),
            "the declaration plus all three uses now read the query:\n" + after);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("undoChangeId"),
            "one handle for the pair is what composing them buys: " + data);
    }

    @Test
    @DisplayName("auto_apply=false is REFUSED — a recipe has no single change to stage")
    void stagingIsRefused() throws Exception {
        String before = read();
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "temp_to_query");
        args.put("filePath", fixture.toString());
        args.put("line", lineOf("int basePrice = quantity * 7;"));
        args.put("column", 12);
        args.put("auto_apply", false);

        ToolResponse r = tool.execute(args);

        // This shipped PUBLISHING auto_apply — the schema wrapper adds it to every
        // refactoring — and silently ignoring it, so a caller who asked to preview got
        // their workspace rewritten. An architect watch at C6 found it. Both composed
        // operations that predate this one refuse the same way for the same reason: the
        // second step is built against the workspace the first produced, so the change to
        // preview does not exist until the first has already been applied.
        assertFalse(r.isSuccess(), "a preview that mutates is worse than no preview");
        String error = String.valueOf(r.getError());
        assertTrue(error.contains("COMPOSED"),
            "and the refusal must say why, not merely decline: " + error);
        assertTrue(error.contains("extract kind=method") && error.contains("inline kind=variable"),
            "naming the two halves a caller can stage themselves: " + error);
        assertEquals(before, read(), "and nothing may have been written");
    }

    @Test
    @DisplayName("a temp that is assigned twice is refused, pointing at Split Variable")
    void aReassignedTempIsRefused() throws Exception {
        String before = read();
        ToolResponse r = replace("int running = quantity * 2;", null);

        assertFalse(r.isSuccess(), "a query returning the initializer would be wrong after"
            + " the second assignment");
        String error = String.valueOf(r.getError());
        assertTrue(error.contains("Split Variable"),
            "and the refusal must name the step that comes first: " + error);
        assertEquals(before, read(), "nothing may change on a refusal");
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
                at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
