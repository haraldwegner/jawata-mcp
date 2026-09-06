package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.RefactorToPatternTool;
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
 * Sprint 28d-rescue, row 8 — Decompose Conditional, driven through its real front door.
 *
 * <p>The operation is worth nothing without the caller's names, so every case here
 * supplies them and the refusals are all about asking for something the code cannot
 * honestly give.</p>
 */
class DecomposeConditionalToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RefactorToPatternTool tool;
    private org.jawata.mcp.tools.RefactoringTool lifecycle;
    private ObjectMapper mapper;
    private Path target;
    private String before;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        // ONE cache, shared. The lifecycle door finds an undo handle only in the cache the
        // operation wrote it to, so two instances would make every undo here fail to resolve
        // and read as a defect in the row rather than in the wiring.
        RefactoringChangeCache cache = new RefactoringChangeCache();
        tool = new RefactorToPatternTool(() -> service, cache);
        lifecycle = new org.jawata.mcp.tools.RefactoringTool(() -> service, cache);
        mapper = new ObjectMapper();
        target = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example/DecomposeConditionalTargets.java");
        before = Files.readString(target, StandardCharsets.UTF_8);
    }

    /** Zero-based line of the first line containing this marker. */
    private int lineOf(String marker) {
        String[] lines = before.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                return i;
            }
        }
        throw new AssertionError("PROOF OF LIFE: the fixture no longer contains " + marker);
    }

    private ObjectNode argsFor(String marker) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "decompose_conditional");
        args.put("filePath", target.toString());
        args.put("line", lineOf(marker));
        args.put("column", 8);
        return args;
    }

    private String after() throws Exception {
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Fowler's example: the test and both branches become named calls")
    void allThreePartsAreNamed() throws Exception {
        ObjectNode args = argsFor("if (date.isBefore");
        args.put("conditionName", "notSummer");
        args.put("thenName", "applyWinterCharge");
        args.put("elseName", "applySummerCharge");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "the recipe must apply; got: " + r.getError());
        String after = after();

        assertTrue(after.contains("if (notSummer(date))"),
            "the condition reads as the question it asks:\n" + after);
        assertTrue(after.contains("applyWinterCharge()"), "the then-branch is named:\n" + after);
        assertTrue(after.contains("applySummerCharge()"), "the else-branch is named:\n" + after);
        assertFalse(after.contains("if (date.isBefore(SUMMER_START) || date.isAfter(SUMMER_END))"),
            "and the tangled form is gone:\n" + after);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(3, data.get("partsExtracted"), "three names, three extractions: " + data);
        assertNotNull(data.get("undoChangeId"),
            "a multi-step recipe owes ONE undo. That the handle RESOLVES and restores is"
                + " asserted by undoRevertsTheWholeRecipe below — this line only says the"
                + " row hands one back, and on its own it never said more.");
    }

    /**
     * C2's clause is <i>"each recipe reverts through its SINGLE undo handle"</i>, and until a
     * C2 audit read this file, the only undo assertion here was the {@code assertNotNull}
     * above.
     *
     * <p><b>A handle that is present and does not resolve looks identical to one that
     * works.</b> Row 8 extracts up to three methods in three separate steps; the failure this
     * guards is a per-step undo that reverts the last extraction and leaves the first two —
     * which would leave the fixture compiling, with two methods nobody asked for and a
     * condition still reading as its extracted call. Rows 2, 10, 36 and 58 all execute their
     * handle and compare byte-for-byte; this row was the one that did not.</p>
     */
    @Test
    @DisplayName("the recipe reverts through its single undo handle, every step of it")
    void undoRevertsTheWholeRecipe() throws Exception {
        ObjectNode args = argsFor("if (date.isBefore");
        args.put("conditionName", "notSummer");
        args.put("thenName", "applyWinterCharge");
        args.put("elseName", "applySummerCharge");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "the recipe must apply; got: " + r.getError());

        // THE CONTROL, and it is the whole reason this test is not the vacuous one it
        // replaces: if the row changed nothing, an undo that restored nothing would pass the
        // comparison below on the pristine fixture. This is the shape a C2 audit found in
        // row 2's fork slice, so it is asserted here rather than assumed.
        assertFalse(before.equals(after()),
            "the fixture must actually differ before an undo can mean anything");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        Object handle = data.get("undoChangeId");
        assertNotNull(handle, "the recipe must hand back ONE undo handle: " + data);

        ObjectNode undo = mapper.createObjectNode();
        undo.put("action", "undo");
        undo.put("undoChangeId", String.valueOf(handle));
        ToolResponse undone = lifecycle.execute(undo);
        assertTrue(undone.isSuccess(),
            "the handle must RESOLVE through the lifecycle door — a handle the cache cannot"
                + " find is exactly the state assertNotNull could not tell apart: "
                + undone.getError());

        assertEquals(before, after(),
            "and all THREE extractions come back. A per-step undo that reverted only the"
                + " last one would leave two methods nobody asked for behind, in a file that"
                + " still compiles — so nothing below this row could report it.");
    }

    @Test
    @DisplayName("a part you do not name is left exactly as it was")
    void anUnnamedPartIsUntouched() throws Exception {
        ObjectNode args = argsFor("if (date.isBefore");
        args.put("conditionName", "notSummer");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "the recipe must apply; got: " + r.getError());
        String after = after();

        assertTrue(after.contains("if (notSummer(date))"), "the named part moved:\n" + after);
        assertTrue(after.contains("charge = quantity * winterRate + winterServiceCharge;"),
            "and the branches, which were not named, are untouched:\n" + after);
        assertTrue(after.contains("charge = quantity * summerRate;"), after);
    }

    @Test
    @DisplayName("a SYMBOL naming a method finds that method's conditional")
    void aSymbolNamesTheMethodsConditional() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "decompose_conditional");
        // No filePath, no line: this is the form a finding carries. A symbol resolves to
        // the method's NAME, where there is no `if` at all, so the operation has to look
        // inside the method it names.
        args.put("symbol", "com.example.DecomposeConditionalTargets#computeCharge");
        args.put("conditionName", "notSummer");
        args.put("thenName", "applyWinterCharge");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "the symbol must resolve to the conditional; got: "
            + r.getError());
        String after = after();
        assertTrue(after.contains("if (notSummer(date))"),
            "the method's own conditional was the one decomposed:\n" + after);
        assertTrue(after.contains("applyWinterCharge()"), after);
    }

    @Test
    @DisplayName("a symbol naming a method with SEVERAL conditionals is refused, not guessed")
    void aSymbolWithSeveralConditionalsIsRefused() throws Exception {
        // An earlier version of this test pointed at a method holding ONE conditional and
        // passed on the write check instead — it would have passed with the whole symbol
        // fallback deleted, which an audit said plainly. This method has two.
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "decompose_conditional");
        args.put("symbol", "com.example.DecomposeConditionalTargets#twoDecisions");
        args.put("conditionName", "isPositive");

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "nothing in the symbol says which conditional was meant");
        assertTrue(String.valueOf(r.getError()).contains("2 conditionals"),
            "and the refusal must COUNT them, so the caller knows what to point at: "
                + r.getError());
        assertEquals(before, after(), "nothing may be written on a refusal");
    }

    @Test
    @DisplayName("a symbol naming a method with NO conditional says so")
    void aSymbolWithNoConditionalIsRefused() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "decompose_conditional");
        args.put("symbol", "com.example.DecomposeConditionalTargets#flatRate");
        args.put("conditionName", "whatever");

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "there is nothing to decompose here");
        assertTrue(String.valueOf(r.getError()).contains("none in the enclosing method"),
            "the two empty-handed cases must read differently — 'none here' and 'none"
                + " anywhere in this method' send the caller to different places: "
                + r.getError());
        assertEquals(before, after(), "nothing may be written on a refusal");
    }

    @Test
    @DisplayName("an else-if chain is refused — it is one decision with three arms")
    void anElseIfChainIsRefused() throws Exception {
        ObjectNode args = argsFor("if (n < 0)");
        args.put("conditionName", "isNegative");

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "naming one link of a chain describes it wrongly");
        assertTrue(String.valueOf(r.getError()).contains("else if"),
            "and the refusal must say so, and point at the row that owns the shape: "
                + r.getError());
        assertEquals(before, after(), "nothing may be written on a refusal");
    }

    @Test
    @DisplayName("a condition that assigns is refused — the write would move onto a parameter")
    void anAssigningConditionIsRefused() throws Exception {
        ObjectNode args = argsFor("if ((cached = (int) charge) > 0)");
        args.put("conditionName", "hasWork");

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "extracting this compiles and silently stops assigning the caller's field");
        assertEquals(before, after(), "nothing may be written on a refusal");
    }

    @Test
    @DisplayName("naming a branch that does not exist is an error, not a quiet no-op")
    void namingAnAbsentBranchIsRefused() throws Exception {
        ObjectNode args = argsFor("if (flag)");
        args.put("elseName", "otherwise");

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "there is no else branch here");
        assertEquals(before, after(), "nothing may be written on a refusal");
    }

    @Test
    @DisplayName("no names at all is refused — the names ARE the refactoring")
    void noNamesIsRefused() throws Exception {
        ToolResponse r = tool.execute(argsFor("if (date.isBefore"));
        assertFalse(r.isSuccess(), "without a name there is nothing worth doing");
        assertEquals(before, after(), "nothing may be written on a refusal");
    }

    @Test
    @DisplayName("staging is refused, in compose_method's own terms")
    void stagingIsRefused() throws Exception {
        ObjectNode args = argsFor("if (date.isBefore");
        args.put("conditionName", "notSummer");
        args.put("auto_apply", false);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "a multi-step recipe has no half-applied state to stage");
        assertEquals(before, after(), "and nothing may be written");
    }
}
