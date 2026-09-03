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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 62 ON CODE WE DID NOT AUTHOR.
 *
 * <p>Every row of this sprint owes a demonstration on the fork corpus, because a fixture
 * written alongside a rewrite is written to suit it. This slice was found by
 * MEASUREMENT and not by browsing: every Lombok-free module in the fork was aggregated
 * into one project of 74 files and every Stage 3 kind run against it, which is also how
 * we know which rows the corpus cannot demonstrate at all.</p>
 *
 * <p>{@code AdaptiveRateLimiter.check} declares {@code key}, then computes an unrelated
 * {@code current}, then finally uses {@code key}. The declaration and its use are
 * separated by a statement that has nothing to do with either, which is precisely the
 * distance this row closes.</p>
 */
class SlideStatementsForkSliceTest {

    private static final String KEY_DECL = "String key = serviceName + \":\" + operationName;";
    private static final String CURRENT_DECL = "int current = currentLimit.get();";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ApplyCleanupTool tool;
    private ObjectMapper mapper;
    private Path adaptive;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("fork-rate-limiting");
        tool = new ApplyCleanupTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        adaptive = service.allProjects().iterator().next().projectRoot().resolve(
            "src/main/java/com/iluwatar/rate/limiting/pattern/AdaptiveRateLimiter.java");
    }

    private ToolResponse run() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "slide_declaration");
        args.put("filePath", adaptive.toString());
        return tool.execute(args);
    }

    @Test
    @DisplayName("the fork's declaration slides down to the statement that uses it")
    void theDeclarationMovesToItsUse() throws Exception {
        String before = Files.readString(adaptive, StandardCharsets.UTF_8);
        int keyBefore = before.indexOf(KEY_DECL);
        int currentBefore = before.indexOf(CURRENT_DECL);
        assertTrue(keyBefore >= 0 && currentBefore >= 0,
            "PROOF OF LIFE: the vendored slice must still carry both declarations. If the"
                + " fork pin moved, this test is measuring nothing.");
        assertTrue(keyBefore < currentBefore,
            "PROOF OF LIFE: and `key` must start ABOVE the unrelated statement, which is"
                + " the distance this row exists to close.");

        ToolResponse r = run();
        assertTrue(r.isSuccess(), "the cleanup must run on foreign code; got: " + r.getError());
        String after = Files.readString(adaptive, StandardCharsets.UTF_8);

        int keyAfter = after.indexOf(KEY_DECL);
        int currentAfter = after.indexOf(CURRENT_DECL);
        assertTrue(keyAfter >= 0 && currentAfter >= 0,
            "both declarations must survive — this moves code and removes none:\n" + after);
        assertTrue(currentAfter < keyAfter,
            "`key` must now sit BELOW the statement it used to be separated from, next to"
                + " the line that reads it:\n" + after);
    }

    @Test
    @DisplayName("it is compile-verified and reversible on foreign code, exactly as on our own")
    void theResultCompilesAndCanBeUndone() {
        ToolResponse r = run();
        // The apply pipeline compiles the modified files and UNDOES the change when new
        // errors appear, so a success here is a compile-verified success. Moving a
        // declaration past a statement is the kind of edit whose damage compiles: slide
        // it past something that reads it and the program is wrong, not broken.
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
