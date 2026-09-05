package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.DataTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROW 65 (Split Variable) performed on code we did not author.
 *
 * <p>{@code Mapper.map} writes {@code for (String word : words) { word =
 * word.toLowerCase().replaceAll(...); ... }} — upstream assigning to the loop's own variable,
 * which is Fowler's case and not an accumulator. Nobody wrote it to be split; it is how the
 * MapReduce demo normalises a word before counting it.</p>
 *
 * <p><b>This file is here because the shape CORRECTED THE RULE.</b> The first version refused
 * any assignment whose value read the variable, which kills every straight-line derivation
 * including Fowler's own parameter example. The second refused any assignment inside a loop,
 * which kills this one. The condition that is actually true is narrower than both: a loop is
 * dangerous only when the variable is declared OUTSIDE it, because that is what makes one
 * pass read what the last one left. Two rules were wrong before foreign code settled it, and
 * neither wrongness was visible from a fixture — a fixture is written to match whatever rule
 * its author had in mind.</p>
 */
class SplitVariableForkSliceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DataTool tool;
    private Path mapper;
    private Path breaker;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("fork-map-reduce");
        tool = new DataTool(() -> service, new org.jawata.mcp.refactoring.RefactoringChangeCache());
        mapper = service.getProjectRoot()
            .resolve("src/main/java/com/iluwatar/Mapper.java");
        breaker = null;
    }

    private ToolResponse at(Path file, String marker, String variable, String newName)
            throws Exception {
        String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "split_variable");
                args.put("filePath", file.toString());
                args.put("line", i);
                args.put("column", lines[i].indexOf(variable));
                args.put("newName", newName);
                return tool.execute(args);
            }
        }
        throw new AssertionError("the vendored slice no longer contains: " + marker);
    }

    @Test
    @DisplayName("upstream's own loop variable splits — the shape that corrected the rule")
    void performsOnUpstreamsMapper() throws Exception {
        ToolResponse r = at(mapper, "word = word.toLowerCase()", "word", "normalised");
        assertTrue(r.isSuccess(), "upstream assigns to the for-each's OWN variable, which is"
            + " re-bound each pass and accumulates nothing — Fowler's case, not the one he"
            + " excludes: " + r.getError());

        String after = Files.readString(mapper, StandardCharsets.UTF_8);
        assertTrue(after.contains("String normalised = word.toLowerCase()"),
            "the assignment becomes a declaration of the second value:\n" + after);
        assertTrue(after.contains("!normalised.isEmpty()"),
            "and upstream's own guard, the next use, reads it:\n" + after);
        assertTrue(after.contains("wordCount.getOrDefault(normalised, 0)"),
            "as does the use nested two levels deeper inside the same iteration — which is"
                + " what proves 'every use after' reached past the first statement:\n" + after);
    }

    @Test
    @DisplayName("upstream's accumulator is refused, on the same file as the case above")
    void refusesUpstreamsAccumulator() throws Exception {
        // The CONTROL, and it is on the same foreign method: `wordCount` is declared before
        // the loop and written inside it. Without this the test above would be evidence that
        // the tool accepts anything in a loop rather than evidence that it tells the two
        // apart — which is the entire correction this file records.
        String before = Files.readString(mapper, StandardCharsets.UTF_8);
        ToolResponse r = at(mapper, "wordCount.put(word, wordCount.getOrDefault", "wordCount",
            "counts");
        assertFalse(r.isSuccess(), "wordCount is declared outside the loop; it is never"
            + " reassigned at all, so there is nothing to split either way");
        assertTrue(before.equals(Files.readString(mapper, StandardCharsets.UTF_8)),
            "and a refusal must leave upstream's file byte-for-byte untouched");
    }
}
