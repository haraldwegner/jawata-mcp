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
    private ObjectMapper mapper;
    private Path target;
    private String before;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new RefactorToPatternTool(() -> service, new RefactoringChangeCache());
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
        assertNotNull(data.get("undoChangeId"), "a multi-step recipe owes one undo: " + data);
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
