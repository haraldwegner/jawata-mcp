package org.jawata.mcp.tools.refactoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.CompileWorkspaceTool;
import org.jawata.mcp.tools.RefactorToStateTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28g S5 / D9 change 4 — {@code refactor_to_state} reaching the shape real code has.
 *
 * <p>The spec's measure for this change is "the sprint's own case", and that case was MEASURED
 * before any of it was built: {@code execution.Slot} declares no {@code int} state field and no
 * state enum — its state is spread over {@code position}, {@code pendingEntry},
 * {@code activeOrder}, {@code tpOrder}, {@code heldPlacement} and more, read through
 * {@code isFree()} / {@code hasPosition()} / {@code isExitInProgress()}. The existing path
 * required {@code switch} on a private {@code int} with {@code static final int} labels, so it
 * could not touch any of it. Asking for a switch was asking for a shape the code does not have.
 * </p>
 *
 * <p>The generated selector is the design: with no field to store the state in and no
 * transition to hook, the state is COMPUTED from the fields, by evaluating the chain's own
 * conditions in the chain's own order. That is what makes this path leave everything outside
 * the method alone — and the order is also the whole correctness argument, since overlapping
 * conditions then resolve exactly as they did before.</p>
 */
class StateFromNullableFieldsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactorToStateTool tool;
    private ObjectMapper mapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("dead-api");
        tool = new RefactorToStateTool(() -> service,
            new org.jawata.mcp.refactoring.RefactoringChangeCache());
        mapper = new ObjectMapper();
        projectPath = service.getProjectRoot();
    }

    @Test
    @DisplayName("three nullable-field states become three classes and a computed selector")
    void theChainBecomesStateClasses() throws Exception {
        ToolResponse r = refactor("if (pendingEntry == null && position == null)",
            List.of("Free", "Holding", "Exiting"));
        assertTrue(r.isSuccess(), () -> "the refactoring failed: " + describe(r));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("undoChangeId"), "it must be reversible");
        assertEquals(List.of("FreeState", "HoldingState", "ExitingState"), data.get("states"),
            () -> "one state class per branch, named by the caller: " + data);
        // The claim that distinguishes this path from the switch path, asserted rather than
        // described: nothing outside the method is rewritten because there is nothing to
        // rewrite — no stored field, so no transition sites.
        assertEquals("computed", data.get("selector"), () -> "" + data);
        assertEquals(0, ((Number) data.get("transitionsRewritten")).intValue(),
            () -> "a computed selector rewrites no transition: " + data);

        String after = Files.readString(occupancyPath());
        assertTrue(after.contains("interface OccupancyState"),
            () -> "the state interface must be generated:\n" + after);
        // WHOLE LINES, read off the engine's own output. contains("FreeState") is true of the
        // class declaration as well, so it would not say the SELECTOR reaches it.
        assertTrue(after.contains("if (pendingEntry == null && position == null) {"),
            () -> "the selector must carry the chain's own first condition:\n" + after);
        assertTrue(after.contains("return new FreeState();"),
            () -> "and return the state the caller named for it:\n" + after);
        assertTrue(after.contains("state().advance(by);"),
            () -> "the method must become one delegating call:\n" + after);

        // THE ORDER, which is the whole correctness argument and which nothing asserted until a
        // mutation was aimed at it. The selector may not reorder the conditions: a reader does
        // not have to believe the branches are mutually exclusive, because overlapping ones
        // resolve exactly as they did before — and that is true ONLY in source order.
        String selector = bodyOf(after, "private OccupancyState state()");
        int first = selector.indexOf("pendingEntry == null && position == null");
        int second = selector.indexOf("position != null && exitOrder == null");
        assertTrue(first >= 0 && second >= 0,
            () -> "both conditions must reach the selector:\n" + selector);
        assertTrue(first < second,
            () -> "the selector must test the conditions in the chain's own order, or "
                + "overlapping branches resolve differently than they did:\n" + selector);
        // The bodies MOVED rather than being copied: the original branch bodies are gone from
        // advance(), which a containment check on the whole file cannot see — so this reads the
        // delegating method's own text.
        assertFalse(bodyOf(after, "public void advance(int by)").contains("ticks += by * 2"),
            () -> "the branch bodies must have moved out of advance():\n" + after);

        CompileWorkspaceTool compile = new CompileWorkspaceTool(() -> service);
        @SuppressWarnings("unchecked")
        Map<String, Object> cdata =
            (Map<String, Object>) compile.execute(mapper.createObjectNode()).getData();
        assertEquals(0, ((Number) cdata.get("errorCount")).intValue(),
            () -> "the generated states and the delegation must compile; diagnostics="
                + cdata.get("diagnostics"));
    }

    @Test
    @DisplayName("a branch that assigns a field a condition tests is refused — that is a transition")
    void aBranchThatTransitionsIsRefused() throws Exception {
        ToolResponse r = refactor("if (pendingEntry == null && position == null) {\n            position",
            List.of("Free", "Holding", "Idle"));
        assertFalse(r.isSuccess(), "the object would change state inside the state's own method");
        String message = String.valueOf(r.getError().getMessage());
        assertTrue(message.contains("assigns `position`"),
            () -> "the refusal must name the field that is written: " + message);
        assertTrue(message.contains("transition"),
            () -> "and say why that is a design decision: " + message);
    }

    @Test
    @DisplayName("a comparison is refused — the selector evaluates each condition a second time")
    void aComparisonIsRefused() throws Exception {
        ToolResponse r = refactor("if (ticks > 0)", List.of("Ticking", "Holding", "Idle"));
        assertFalse(r.isSuccess(), "a comparison is not a null test");
        assertTrue(String.valueOf(r.getError().getMessage()).contains("ticks > 0"),
            () -> "the refusal must quote the condition it will not take: "
                + r.getError().getMessage());
    }

    @Test
    @DisplayName("the state names have NO DEFAULT, and the refusal says how many are wanted")
    void theStateNamesAreRequired() throws Exception {
        ToolResponse none = refactor("if (pendingEntry == null && position == null)", List.of());
        assertFalse(none.isSuccess(), "nothing in the code names these states");
        assertTrue(String.valueOf(none.getError().getMessage()).contains("3 of them here"),
            () -> "the refusal must say how many: " + none.getError().getMessage());

        // AND THE COUNT IS CHECKED, not just the presence — two names for three states would
        // otherwise generate a class called `nullState`.
        ToolResponse tooFew = refactor("if (pendingEntry == null && position == null)",
            List.of("Free", "Holding"));
        assertFalse(tooFew.isSuccess(), "a short list must not be padded");
        assertTrue(String.valueOf(tooFew.getError().getMessage()).contains("2 given"),
            () -> "and say how many it got: " + tooFew.getError().getMessage());
    }

    @Test
    // RENAMED at C5, because the old name claimed more than the body does. It read "THE
    // CONTROL: the int-switch shape still works, untouched by this path", and this test never
    // drives a switch — it positions on a plain method and reads the refusal. The comment
    // below already admitted that, so the name and the body disagreed in the same screen. The
    // genuine switch control is RefactorToStateToolTest, which is named here so a reader
    // looking for it is not left thinking this was it.
    @DisplayName("a position that is neither shape is refused by a message naming BOTH"
        + " (the int-switch control proper is RefactorToStateToolTest)")
    void neitherShapeIsRefusedNamingBoth() throws Exception {
        // The switch path's own fixture lives in another project, so this asserts the narrower
        // thing that matters here: a position that is neither a switch nor an if-chain is
        // refused by a message naming BOTH shapes, which is what says the old path survived
        // rather than being replaced.
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", occupancyPath().toString());
        int[] pos = posOf(Files.readString(occupancyPath()), "public int ticks()");
        args.put("line", pos[0]);
        args.put("column", pos[1]);
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "a plain method is neither shape");
        String message = String.valueOf(r.getError().getMessage());
        assertTrue(message.contains("switch") && message.contains("if-chain"),
            () -> "the refusal must offer both shapes: " + message);
    }

    private Path occupancyPath() {
        return projectPath.resolve("src/main/java/com/example/Occupancy.java");
    }

    private ToolResponse refactor(String needle, List<String> states) throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", occupancyPath().toString());
        int[] pos = posOf(Files.readString(occupancyPath()), needle);
        args.put("line", pos[0]);
        args.put("column", pos[1]);
        if (!states.isEmpty()) {
            com.fasterxml.jackson.databind.node.ArrayNode array = args.putArray("states");
            states.forEach(array::add);
        }
        return tool.execute(args);
    }

    /** Zero-based [line, column] of the first occurrence of {@code needle}. */
    private static int[] posOf(String text, String needle) {
        int idx = text.indexOf(needle);
        if (idx < 0) {
            throw new AssertionError("needle not found: " + needle);
        }
        int line = 0;
        int col = 0;
        for (int i = 0; i < idx; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }
        return new int[]{line, col};
    }

    /**
     * The text of ONE method, from its signature to the next member — because a whole-file
     * containment check cannot express "gone from HERE", which is the defect row 61 shipped
     * three of before a helper like this fixed them.
     */
    private static String bodyOf(String source, String signature) {
        int from = source.indexOf(signature);
        if (from < 0) {
            throw new AssertionError("method not found: " + signature);
        }
        int depth = 0;
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(from, i + 1);
                }
            }
        }
        return source.substring(from);
    }

    private static String describe(ToolResponse r) {
        return r.getError() == null ? "(no error)"
            : r.getError().getReason() + " / " + r.getError().getMessage();
    }
}
