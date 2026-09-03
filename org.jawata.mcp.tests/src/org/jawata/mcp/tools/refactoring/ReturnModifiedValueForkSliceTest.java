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
 * Row 60 ON CODE WE DID NOT AUTHOR — and it is the only such code in the fork.
 *
 * <p>The shape was searched for exhaustively rather than sampled: every Lombok-free
 * module aggregated and probed (116 files, main and test), then every Lombok-using
 * module (1210 files). {@code App.createLobSerializer} is the one place it occurs.</p>
 *
 * <p>The method declares {@code LobSerializer serializer}, assigns it in each arm of an
 * if/else, and returns it at the end. That is exactly the local whose only job is to
 * carry the answer, written by someone demonstrating serialized large objects who had
 * never heard of this operation.</p>
 */
class ReturnModifiedValueForkSliceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ApplyCleanupTool tool;
    private ObjectMapper mapper;
    private Path app;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("fork-serialized-lob");
        tool = new ApplyCleanupTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        app = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/iluwatar/slob/App.java");
    }

    private ToolResponse run() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "return_modified_value");
        args.put("filePath", app.toString());
        return tool.execute(args);
    }

    @Test
    @DisplayName("the fork's answer-holding local goes, and each branch returns directly")
    void theHolderBecomesDirectReturns() throws Exception {
        String before = Files.readString(app, StandardCharsets.UTF_8);
        assertTrue(before.contains("LobSerializer serializer;"),
            "PROOF OF LIFE: the vendored slice must still declare the holder this test is"
                + " about. If the fork pin moved, this test is measuring nothing.");
        assertTrue(before.contains("return serializer;"),
            "PROOF OF LIFE: and hand it back at the end.");

        ToolResponse r = run();
        assertTrue(r.isSuccess(), "the cleanup must run on foreign code; got: " + r.getError());
        String after = Files.readString(app, StandardCharsets.UTF_8);

        assertFalse(after.contains("LobSerializer serializer;"),
            "the variable existed only to hold the answer, so it goes:\n" + after);
        assertFalse(after.contains("return serializer;"),
            "and the trailing return with it:\n" + after);
        assertTrue(after.contains("return new ClobSerializer();"),
            "each branch returns its own value:\n" + after);
        assertTrue(after.contains("return new BlobSerializer();"), after);
    }

    @Test
    @DisplayName("it is compile-verified and reversible on foreign code, exactly as on our own")
    void theResultCompilesAndCanBeUndone() {
        ToolResponse r = run();
        // Worth stating for THIS slice: four of its eight files use Lombok and JDT does
        // not run Lombok's processor here, so they carry unresolved references to
        // generated accessors. The file being rewritten is not one of them, and the
        // pipeline still reports the change compile-verified.
        assertTrue(r.isSuccess(),
            "a rewrite that breaks foreign code is refused and reverted by the compile"
                + " gate, and the refusal reads like this: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertTrue(Boolean.TRUE.equals(data.get("compileVerified")),
            "and the verification actually ran: " + data);
        assertNotNull(data.get("undoChangeId"), "reversible like any other change: " + data);
        assertTrue(data.get("filesModified") instanceof List<?> files && !files.isEmpty(),
            "with the file named: " + data);
    }
}
