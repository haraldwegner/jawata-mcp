package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ApplyCleanupTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue, row 50 — Replace Loop with Pipeline.
 *
 * <p>This is the row with the demand: the finder behind it has reported 667 candidates on
 * this repository since Sprint 15, with no rewrite to answer them. It is also the row
 * where a wrong rewrite is hardest to spot by eye, because the result reads fluently
 * whether or not it computes the same thing.</p>
 *
 * <p>Two assertions here are about what the pipeline is NOT allowed to become. It must
 * collect rather than call {@code .toList()}, because that returns an unmodifiable list
 * and the original is an ArrayList somebody may still add to. And the collector is
 * written fully qualified, because the import engine cannot add an import headlessly, so
 * a short name would leave a file that does not compile — which the gate would catch, and
 * which would then look like the rewrite being impossible rather than one line being
 * wrong.</p>
 */
class LoopToPipelineToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ApplyCleanupTool tool;
    private ObjectMapper mapper;
    private Path target;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new ApplyCleanupTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        target = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example/PipelineTargets.java");
    }

    private String rewritten() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "loop_to_pipeline");
        args.put("filePath", target.toString());
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "the cleanup must run; got: " + r.getError());
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a filtered accumulation becomes filter, map and collect")
    void theFullShapeBecomesAPipeline() throws Exception {
        String body = methodBody(rewritten(), "activeNames");
        assertTrue(body.contains(".stream()"), "the walk becomes a stream:\n" + body);
        assertTrue(body.contains(".filter("), "the guard becomes a filter:\n" + body);
        assertTrue(body.contains(".map("), "and what was added becomes a map:\n" + body);
        assertFalse(body.contains("for ("), "the loop itself is gone:\n" + body);
    }

    @Test
    @DisplayName("no guard means no filter stage, and adding the element means no map stage")
    void theStagesAreOnlyTheOnesNeeded() throws Exception {
        String after = rewritten();

        String unfiltered = methodBody(after, "allNames");
        assertTrue(unfiltered.contains(".map("), "it still transforms:\n" + unfiltered);
        assertFalse(unfiltered.contains(".filter("),
            "there was no guard, so a filter stage would be a step that says nothing:\n"
                + unfiltered);

        String copy = methodBody(after, "copyOf");
        assertTrue(copy.contains(".stream()"), "still a pipeline:\n" + copy);
        assertFalse(copy.contains(".map("),
            "mapping x to x is a stage that does nothing and reads as though it does:\n"
                + copy);
    }

    @Test
    @DisplayName("it collects rather than calling toList, because the original list is mutable")
    void theCollectorPreservesMutability() throws Exception {
        String body = methodBody(rewritten(), "allNames");
        assertTrue(body.contains("java.util.stream.Collectors.toList()"),
            "fully qualified, because the import engine cannot ADD an import headlessly"
                + " and a short name would leave a file that does not compile:\n" + body);
        assertFalse(body.contains(".stream().map(person -> person.toString()).toList()"),
            "Stream.toList() returns an UNMODIFIABLE list. The original is an ArrayList a"
                + " caller may still add to, so that swap is a behaviour change wearing a"
                + " modernisation's clothes:\n" + body);
    }

    @Test
    @DisplayName("a loop doing two things is two jobs, and stays a loop")
    void aSecondStatementIsRefused() throws Exception {
        String body = methodBody(rewritten(), "alsoCounts");
        assertTrue(body.contains("for ("),
            "this loop also counts. A pipeline that dropped the counting would compute"
                + " something else; Split Loop is the refactoring for it:\n" + body);
    }

    @Test
    @DisplayName("an early exit has no pipeline equivalent that means the same thing")
    void aBreakIsRefused() throws Exception {
        String body = methodBody(rewritten(), "stopsEarly");
        assertTrue(body.contains("for ("),
            "findFirst and anyMatch are different computations with different results,"
                + " so this is not a rewrite that can be made mechanically:\n" + body);
    }

    @Test
    @DisplayName("a list that already holds something is not what collect produces")
    void aNonEmptyListIsRefused() throws Exception {
        String body = methodBody(rewritten(), "startsNonEmpty");
        assertTrue(body.contains("for ("),
            "collect builds a new list, so the header this one starts with would be"
                + " silently dropped:\n" + body);
    }

    @Test
    @DisplayName("an array has no stream(), and the import needed to give it one cannot be added")
    void anArrayIsRefused() throws Exception {
        String body = methodBody(rewritten(), "overAnArray");
        assertTrue(body.contains("for ("),
            "Arrays.stream would need an import, and the organize-imports engine cannot"
                + " add one headlessly — a known limit, refused rather than worked"
                + " around:\n" + body);
    }

    /** One method's source, stopping before the next member's javadoc. */
    private static String methodBody(String source, String methodName) {
        int start = source.indexOf(" " + methodName + "(");
        assertTrue(start >= 0, "method '" + methodName + "' is gone from the fixture");
        int end = source.length();
        for (String boundary : List.of("\n    /**", "\n    public ", "\n    private ")) {
            int next = source.indexOf(boundary, start);
            if (next >= 0) {
                end = Math.min(end, next);
            }
        }
        return source.substring(start, end);
    }
}
