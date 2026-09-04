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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue, row 49 — Replace Inline Code with Function Call, on
 * {@code extract kind=method}.
 *
 * <p>The plan guessed this row might already be a setter on an engine jawata wraps, having
 * measured that {@code setReplaceDuplicates} had zero references here. It was right. So the
 * row is not a new kind: it is {@code extract kind=method} finishing its job, and the pair
 * of tests below is about the DEFAULT rather than the capability.</p>
 *
 * <p>The control matters more than the happy path here. Turning the flag off must leave the
 * second occurrence alone — otherwise the default is not a choice, it is the only behaviour
 * and the parameter is decoration.</p>
 */
class ReplaceInlineCodeWithFunctionCallTest {

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
            .resolve("src/main/java/com/example/InlineCodeTwice.java");
    }

    private String read() throws Exception {
        return Files.readString(fixture, StandardCharsets.UTF_8);
    }

    private int lineOf(String marker, int occurrence) throws Exception {
        String[] lines = read().split("\n", -1);
        int seen = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker) && seen++ == occurrence) {
                return i;
            }
        }
        throw new AssertionError("PROOF OF LIFE: fewer than " + (occurrence + 1)
            + " lines contain " + marker);
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
                at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    /** Extract the FIRST occurrence of the two-statement pair, as `scale`. */
    private ToolResponse extractFirstPair(Boolean replaceDuplicates) throws Exception {
        int start = lineOf("int scaled = base * 3;", 0);
        int end = lineOf("int shifted = scaled + 7;", 0);
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "method");
        args.put("filePath", fixture.toString());
        args.put("startLine", start);
        args.put("startColumn", 8);
        args.put("endLine", end);
        args.put("endColumn", 8 + "int shifted = scaled + 7;".length());
        args.put("methodName", "scale");
        if (replaceDuplicates != null) {
            args.put("replaceDuplicates", replaceDuplicates);
        }
        return tool.execute(args);
    }

    @Test
    @DisplayName("THE DEFAULT is unchanged: an extract touches only the selection, and says how many others there are")
    void theDefaultLeavesTheOtherOccurrenceAlone() throws Exception {
        assertEquals(2, countOf(read(), "int scaled = base * 3;"),
            "PROOF OF LIFE: the fixture must hold the pair twice before this runs");

        ToolResponse r = extractFirstPair(null);
        assertTrue(r.isSuccess(), "the extraction must run; got: " + r.getError());

        // `extract kind=method` has shipped since Sprint 16b and
        // ARCHITECTURE-fowler-full.md §8 lists the behaviour of the shipped refactorings
        // under Do not touch. Row 49 shipped with the flag ON for one day; an architect
        // watch at C6 named the clause and it was right. This test is the guard that the
        // reversal stays reversed.
        assertEquals(2, countOf(read(), "int scaled = base * 3;"),
            "the second occurrence is untouched by an extract that did not ask:\n" + read());

        @SuppressWarnings("unchecked")
        Map<String, Object> byDefault = (Map<String, Object>) r.getData();
        // AND THE COUNT IS STILL REPORTED, which is what keeps row 49 from being a flag
        // nobody chooses: the caller could not have counted the other occurrences, and now
        // they have the number that tells them to ask.
        assertEquals(1, ((Number) byDefault.get("otherOccurrences")).intValue(),
            "the count is reported even when nothing was done about it: " + byDefault);
        assertEquals(false, byDefault.get("replaceDuplicates"), byDefault.toString());
    }

    @Test
    @DisplayName("replaceDuplicates=true makes the other occurrence a call too")
    void theDuplicateIsReplaced() throws Exception {
        assertEquals(2, countOf(read(), "int scaled = base * 3;"),
            "PROOF OF LIFE: the fixture must hold the pair twice before this runs");

        ToolResponse r = extractFirstPair(true);
        assertTrue(r.isSuccess(), "the extraction must run; got: " + r.getError());

        String after = read();
        // ONE copy survives, and it is the one inside the new method — which is where the
        // code was moved TO. Zero would mean the extraction lost it.
        assertEquals(1, countOf(after, "int scaled = base * 3;"),
            "the only surviving copy is the extracted method's own body:\n" + after);
        assertEquals(1, countOf(after, "private int scale(int base)"),
            "declared once:\n" + after);
        assertEquals(2, countOf(after, "= scale(base);"),
            "and BOTH original sites call it — the second one is the row, since nothing"
                + " asked for it explicitly:\n" + after);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        // The number the caller could not have counted themselves. Reporting it is how they
        // learn the code they selected existed in more than one place.
        assertEquals(1, ((Number) data.get("otherOccurrences")).intValue(),
            "and the response says how many others there were: " + data);
        assertEquals(true, data.get("replaceDuplicates"), "with what was done to them: " + data);
    }

    @Test
    @DisplayName("replaceDuplicates=false is the same as omitting it — the control on the flag")
    void theFlagCanBeTurnedOff() throws Exception {
        ToolResponse r = extractFirstPair(false);
        assertTrue(r.isSuccess(), "the extraction must run; got: " + r.getError());

        String after = read();
        // TWO copies: the extracted method's own body, and the second method's, which was
        // left exactly as it was. With the flag on there is one.
        assertEquals(2, countOf(after, "int scaled = base * 3;"),
            "the second occurrence is untouched, so the default is a real choice and not"
                + " the only behaviour:\n" + after);
        assertEquals(1, countOf(after, "= scale(base);"),
            "and only the selected site calls the new method:\n" + after);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(1, ((Number) data.get("otherOccurrences")).intValue(),
            "the count is still reported when nothing was done about it — that is what tells"
                + " a caller the flag was worth setting: " + data);
        assertEquals(false, data.get("replaceDuplicates"), data.toString());
    }
}
