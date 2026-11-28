package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.ltk.core.refactoring.NullChange;
import org.jawata.mcp.change.GatedApply;
import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.fixtures.JavaLifecycle;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ApplyRefactoringTool;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.UndoRefactoringTool;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 28h z194 — a COUPLED signature change that is STAGED and then committed with the apply tool
 * must behave as {@code change_method_signature}'s description says, like the direct path:
 * applied anyway, {@code compileVerified:false}, the compiler errors it introduced as the
 * worklist. Before this, the staged entry carried no gate mode, so the apply tool always ran
 * the gate in its UNDO mode and refused the change as REFACTORING_BROKE_COMPILE.
 */
class StagedCoupledSignatureChangeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private ChangeMethodSignatureTool signatureTool;
    private ApplyRefactoringTool applyTool;
    private UndoRefactoringTool undoTool;
    private Path target;

    @BeforeEach
    void setUp() throws Exception {
        wireOnFreshCopy();
    }

    /** Loads a fresh copy of the project and builds every tool over it and one shared cache. */
    private void wireOnFreshCopy() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        signatureTool = new ChangeMethodSignatureTool(() -> service, cache);
        applyTool = new ApplyRefactoringTool(() -> service, cache, JavaLifecycle.changes(service));
        undoTool = new UndoRefactoringTool(() -> service, cache, JavaLifecycle.changes(service));
        target = service.getProjectRoot().resolve("src/main/java/com/example/RefactoringTarget.java");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse response) {
        return (Map<String, Object>) response.getData();
    }

    private ObjectNode signatureArgs(boolean stage) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "change_signature");
        args.put("filePath", target.toString());
        args.put("line", 71);  // formatMessage
        args.put("column", 18);
        if (stage) {
            args.put("auto_apply", false);
        }
        return args;
    }

    private ObjectNode coupledArgs(boolean stage) {
        ObjectNode args = signatureArgs(stage);
        args.put("newReturnType", "void");
        return args;
    }

    private ToolResponse stage(ObjectNode args) {
        ToolResponse staged = signatureTool.execute(args);
        assertTrue(staged.isSuccess(), () -> "staging must succeed: " + staged.getError());
        return staged;
    }

    private ToolResponse apply(String changeId) {
        return applyTool.execute(objectMapper.createObjectNode().put("changeId", changeId));
    }

    @Test
    @DisplayName("28h z194: a staged coupled change, applied, stands with its worklist and undoes exactly")
    void aStagedCoupledChangeIsAppliedWithItsWorklist() throws Exception {
        String original = Files.readString(target);

        ToolResponse staged = stage(coupledArgs(true));
        String changeId = (String) data(staged).get("changeId");
        assertNotNull(changeId, "staging answers a changeId: " + data(staged));
        assertEquals(original, Files.readString(target), "staging writes nothing");

        ToolResponse applied = apply(changeId);
        assertTrue(applied.isSuccess(), () -> "the coupled change is applied anyway, got: " + applied.getError());
        Map<String, Object> applyData = data(applied);
        assertEquals(Boolean.FALSE, applyData.get("compileVerified"), "the answer says the code is not verified");
        Object introduced = applyData.get("introducedErrors");
        assertTrue(introduced instanceof List<?> list && !list.isEmpty(),
            "the introduced errors are the worklist: " + applyData);
        assertTrue(Files.readString(target).contains("void formatMessage"),
            "the signature change stands on disk");
        String undoChangeId = (String) applyData.get("undoChangeId");
        assertNotNull(undoChangeId, "an undo handle is given");

        ToolResponse undone = undoTool.execute(
            objectMapper.createObjectNode().put("undoChangeId", undoChangeId));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(target), "undo restores the original text exactly");
    }

    @Test
    @DisplayName("28h z194: staged-then-applied and direct report the same number of introduced errors")
    void stagedAndDirectPathsAgreeOnTheWorklist() throws Exception {
        ToolResponse direct = signatureTool.execute(coupledArgs(false));
        assertTrue(direct.isSuccess(), () -> "direct path: " + direct.getError());
        int directCount = ((List<?>) data(direct).get("introducedErrors")).size();
        assertTrue(directCount > 0, "the direct path has a worklist: " + data(direct));

        wireOnFreshCopy();
        String changeId = (String) data(stage(coupledArgs(true))).get("changeId");
        ToolResponse applied = apply(changeId);
        assertTrue(applied.isSuccess(), () -> "staged path: " + applied.getError());
        Object stagedList = data(applied).get("introducedErrors");
        assertTrue(stagedList instanceof List<?>, "the staged path answers a worklist: " + data(applied));
        assertEquals(directCount, ((List<?>) stagedList).size(),
            "both paths introduce the same number of errors");
    }

    @Test
    @DisplayName("28h z194: CONTROL — a staged change that breaks nothing is applied and verified")
    void aStagedChangeThatBreaksNothingIsVerified() throws Exception {
        ObjectNode args = signatureArgs(true);
        args.put("newName", "formatOutput");
        String changeId = (String) data(stage(args)).get("changeId");

        ToolResponse applied = apply(changeId);
        assertTrue(applied.isSuccess(), () -> String.valueOf(applied.getError()));
        assertEquals(Boolean.TRUE, data(applied).get("compileVerified"), "nothing was introduced: " + data(applied));
        assertFalse(data(applied).containsKey("introducedErrors"), "so there is no worklist");
        assertTrue(Files.readString(target).contains("formatOutput("), "the new name is on disk");
    }

    @Test
    @DisplayName("28h z194: the cache keeps the gate mode a change was staged with")
    void theCacheKeepsTheGateMode() throws Exception {
        String changeId = (String) data(stage(coupledArgs(true))).get("changeId");
        assertEquals(GatedApply.Mode.REPORT, cache.peek(changeId).orElseThrow().gateMode(),
            "a coupled signature change is staged under REPORT");

        String plain = cache.put(RefactoringChangeCache.Kind.STAGED, new NullChange("plain"),
            "plain", "", List.of());
        assertEquals(GatedApply.Mode.UNDO, cache.peek(plain).orElseThrow().gateMode(),
            "the five-argument put keeps the strict mode");
    }
}
