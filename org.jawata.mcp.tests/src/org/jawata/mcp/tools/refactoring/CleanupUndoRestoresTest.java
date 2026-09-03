package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ApplyCleanupTool;
import org.jawata.mcp.tools.UndoRefactoringTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue — the rows whose undo handle nothing asserted.
 *
 * <p>C3's per-row contract opens with "a staged change with an undo handle". Six rows
 * assert it through their fork slices. Three — consolidate_conditional (7),
 * control_flag_to_break (44) and split_loop (63) — have no fork slice, because their
 * shapes occur nowhere in the corpus, and so nothing asserted their undo at all. A C3
 * audit found the gap.</p>
 *
 * <p>These assert the STRONGER thing, because a handle that does not work is worse than
 * no handle: apply the rewrite, then run the undo and require the file back
 * byte-for-byte. The intermediate assertion that the file actually CHANGED is what stops
 * the restore from being trivially satisfied by a rewrite that did nothing.</p>
 */
class CleanupUndoRestoresTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private void assertUndoRestores(String kind, String fixture) throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        ObjectMapper mapper = new ObjectMapper();
        Path file = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example/" + fixture);
        String before = Files.readString(file, StandardCharsets.UTF_8);

        ObjectNode args = mapper.createObjectNode();
        args.put("kind", kind);
        args.put("filePath", file.toString());
        ToolResponse applied = new ApplyCleanupTool(() -> service, cache).execute(args);
        assertTrue(applied.isSuccess(), kind + " must apply; got: " + applied.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) applied.getData();
        String undoId = (String) data.get("undoChangeId");
        assertNotNull(undoId, "the contract's first clause is an undo handle: " + data);
        assertTrue(!undoId.isBlank(), "and a blank one is not a handle: " + data);

        // Without this, a rewrite that did nothing would "restore" perfectly.
        String after = Files.readString(file, StandardCharsets.UTF_8);
        assertNotEquals(before, after, kind + " changed nothing, so the undo below proves"
            + " nothing either");

        ToolResponse undone = new UndoRefactoringTool(() -> service, cache)
            .execute(mapper.createObjectNode().put("undoChangeId", undoId));
        assertTrue(undone.isSuccess(), "the undo must run; got: " + undone.getError());
        assertEquals(before, Files.readString(file, StandardCharsets.UTF_8),
            "the handle must put the file back exactly as it was — a rewrite you cannot"
                + " reverse is one nobody should run on their own code");
    }

    @Test
    @DisplayName("row 7 consolidate_conditional applies and reverses")
    void consolidateConditional() throws Exception {
        assertUndoRestores("consolidate_conditional", "ConsolidateTargets.java");
    }

    @Test
    @DisplayName("row 44 control_flag_to_break applies and reverses")
    void controlFlagToBreak() throws Exception {
        assertUndoRestores("control_flag_to_break", "ControlFlagTargets.java");
    }

    @Test
    @DisplayName("row 63 split_loop applies and reverses")
    void splitLoop() throws Exception {
        assertUndoRestores("split_loop", "SplitLoopTargets.java");
    }
}
