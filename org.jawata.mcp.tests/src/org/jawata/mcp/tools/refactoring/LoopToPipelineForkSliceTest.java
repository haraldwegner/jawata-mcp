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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 50 ON CODE WE DID NOT AUTHOR.
 *
 * <p>Every row of this sprint owes a demonstration on the fork corpus, because a fixture
 * written alongside a rewrite is written to suit it. A fixture proves the rule does what
 * its author meant; only somebody else's code proves it survives contact with how people
 * actually write.</p>
 *
 * <p>The slice was chosen by MEASUREMENT, not by browsing. Running
 * {@code find_modernization(kind=loop_to_stream)} over the whole fork named four
 * candidates in {@code map-reduce}, more than in any other single module, and running
 * every Stage 3 kind against the vendored result showed this row is the one it can
 * demonstrate. Nothing here was picked because it was easy.</p>
 *
 * <p>{@code MapReduce.mapReduce} builds a list by looping and adding: the plainest form
 * of the accumulation this row replaces, written by someone explaining map/reduce who had
 * never heard of the operation.</p>
 */
class LoopToPipelineForkSliceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ApplyCleanupTool tool;
    private ObjectMapper mapper;
    private Path mapReduceFile;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("fork-map-reduce");
        tool = new ApplyCleanupTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        mapReduceFile = helper.getTempDirectory()
            .resolve("fork-map-reduce/src/main/java/com/iluwatar/MapReduce.java");
    }

    private ToolResponse run() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "loop_to_pipeline");
        args.put("filePath", mapReduceFile.toString());
        return tool.execute(args);
    }

    @Test
    @DisplayName("the fork's accumulation loop becomes a pipeline")
    void theForksLoopBecomesAPipeline() throws Exception {
        String before = Files.readString(mapReduceFile, StandardCharsets.UTF_8);
        assertTrue(before.contains("for (String input : inputs)"),
            "PROOF OF LIFE: the vendored slice must still carry the loop this test is"
                + " about. If the fork pin moved, this test is measuring nothing.");
        assertTrue(before.contains("mapped.add(Mapper.map(input));"),
            "PROOF OF LIFE: and the accumulation inside it.");

        ToolResponse r = run();
        assertTrue(r.isSuccess(), "the cleanup must run on foreign code; got: " + r.getError());
        String after = Files.readString(mapReduceFile, StandardCharsets.UTF_8);

        assertFalse(after.contains("for (String input : inputs)"),
            "the loop was the thing being replaced:\n" + after);
        assertTrue(after.contains(".stream()"),
            "and a pipeline is what replaces it:\n" + after);
        assertTrue(after.contains("Mapper"),
            "the work the loop did must still be done, by the same collaborator:\n" + after);
    }

    @Test
    @DisplayName("it is compile-verified and reversible on foreign code, exactly as on our own")
    void theResultCompilesAndCanBeUndone() {
        ToolResponse r = run();
        // The apply pipeline compiles the modified files and UNDOES the change when new
        // errors appear, so a success here is a compile-verified success. That is the
        // value of running on code nobody wrote for this test: a rewrite tuned to a
        // fixture meets shapes it was never shown, and the gate is what catches it.
        assertTrue(r.isSuccess(),
            "a rewrite that breaks foreign code is refused and reverted by the compile"
                + " gate, and the refusal reads like this: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("undoChangeId"),
            "and it is reversible on foreign code exactly as on our own: " + data);
        assertTrue(data.get("filesModified") instanceof List<?> files && !files.isEmpty(),
            "with the file named: " + data);
    }
}
