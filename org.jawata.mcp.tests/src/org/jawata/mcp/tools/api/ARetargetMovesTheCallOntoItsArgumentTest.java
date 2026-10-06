package org.jawata.mcp.tools.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28g S5 / D9 row 1 — a retarget can move the call onto the object it was passing in.
 *
 * <p>The census counts <strong>18</strong> hand edits of one shape across Sprint 8, and names the
 * gap exactly: {@code retargetCallsTo} <em>"keeps the receiver; the sprint's 18 retargets change
 * it (`callback.x(s, …)` → `s.x(…)`)"</em>. The behaviour had moved onto the object the manager
 * was passing in, so every site had to change what it invoked ON — and the tool could only change
 * the name, which left eighteen edits to a human.</p>
 *
 * <p>Every assertion reads the diff's ADDED side alone. A unified diff carries the old text on its
 * {@code -} lines, so a {@code contains} over the whole response matches the very literals the
 * change is deleting — the vacuous shape this sprint caught in D7b's first test, which passed
 * while the defect was live.</p>
 */
class ARetargetMovesTheCallOntoItsArgumentTest {

    private static final String TARGET = "com.example.ReceiverRetargetTargets.Callback#launchExit";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ChangeMethodSignatureTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new ChangeMethodSignatureTool(() -> service, new RefactoringChangeCache());
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("all four sites invoke on their own argument, which drops out of the list")
    void theArgumentBecomesTheReceiver() {
        ToolResponse r = retarget("launchExit", "slot");
        assertTrue(r.isSuccess(), () -> "staging failed: " + describe(r));
        String added = addedLines(String.valueOf(dataOf(r).get("diff")));

        // THE WHOLE EXPRESSION PER SITE, not a substring. `contains("launchExit")` is true of
        // the call before the change as well, so it would measure nothing; and the receiver is
        // the only thing that moves, so it is the receiver each line has to show.
        for (String expected : new String[] {
                "primary.launchExit(\"siteOneFill\")",
                "secondary.launchExit(\"siteTwoTimeout\")",
                "primary.launchExit(\"siteThreeFlatten\")",
                // The non-trivial receiver: built from the argument NODE, so an index expression
                // survives. A rewrite assembled from the parameter's NAME would emit `slot.`
                // here and compile against nothing.
                "pool[0].launchExit(\"siteFourAbort\")" }) {
            assertTrue(added.contains(expected),
                () -> "a site did not move onto its argument — expected " + expected
                    + " among the added lines:\n" + added);
        }

        // AND THE OLD RECEIVER IS GONE FROM WHAT WAS WRITTEN. Without this the mode could be
        // adding the new receiver while leaving the old call in place beside it.
        assertFalse(added.contains("callback.launchExit"),
            () -> "the call is still invoked on the old receiver:\n" + added);
    }

    @Test
    @DisplayName("the response says which argument became the receiver, not just how many edits")
    void theModeIsReported() {
        Map<String, Object> data = dataOf(retarget("launchExit", "slot"));
        assertEquals("slot", data.get("receiverFromParameter"),
            () -> "a caller cannot see WHAT the call is now invoked on from an edit count: "
                + data.keySet());
        assertNotNull(data.get("receiverNote"), () -> "and the note that says the declaration is "
            + "deliberately unchanged, which is the half that decides whether it compiles: "
            + data.keySet());
    }

    @Test
    @DisplayName("WITHOUT the parameter the receiver is kept — the mode is conditional")
    void aPlainRetargetStillKeepsTheReceiver() {
        // THE CONTROL THAT MAKES THE FIRST TEST MEAN SOMETHING. A change that moved the call
        // onto an argument unconditionally would satisfy every assertion above and would have
        // broken the behaviour the other 294 census rows rely on.
        ToolResponse r = retarget("launchExit", null);
        assertTrue(r.isSuccess(), () -> "staging failed: " + describe(r));
        String added = addedLines(String.valueOf(dataOf(r).get("diff")));

        assertTrue(added.isEmpty() || added.contains("callback.launchExit"),
            () -> "a retarget with no receiverFromParameter must leave the receiver alone:\n"
                + added);
        assertFalse(added.contains("primary.launchExit"),
            () -> "the receiver moved without being asked to:\n" + added);
    }

    @Test
    @DisplayName("a name that is not a parameter is refused WITH the names that are")
    void anUnknownParameterIsRefusedByName() {
        ToolResponse r = retarget("launchExit", "slotz");
        assertFalse(r.isSuccess(), "a misremembered parameter name must not be ignored");
        String message = String.valueOf(r.getError().getMessage());
        // The refusal has to be ACTIONABLE: "not found" leaves the caller guessing, the list
        // does not. Both real parameter names are required so a partial list cannot pass.
        assertTrue(message.contains("slot") && message.contains("reason"),
            () -> "the refusal must name the parameters that DO exist: " + message);
    }

    @Test
    @DisplayName("a constructor is refused — there is no receiver to move the call onto")
    void aConstructorIsRefused() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "change_signature");
        args.put("symbol", "com.example.ReceiverRetargetTargets.Slot#Slot");
        args.put("retargetCallsTo", "launchExit");
        args.put("receiverFromParameter", "name");
        args.put("auto_apply", false);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "a constructor has no expression slot to rewrite");
        assertTrue(String.valueOf(r.getError().getMessage()).contains("constructor"),
            () -> "and the refusal should say so: " + r.getError().getMessage());
    }

    @Test
    @DisplayName("the parameter alone is refused — it names no method to call on the new receiver")
    void theReceiverParameterNeedsTheMethodName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "change_signature");
        args.put("symbol", TARGET);
        args.put("receiverFromParameter", "slot");
        args.put("auto_apply", false);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "receiverFromParameter without retargetCallsTo must not be silently ignored");
        assertTrue(String.valueOf(r.getError().getMessage()).contains("retargetCallsTo"),
            () -> "the refusal should name what is missing: " + r.getError().getMessage());
    }

    private ToolResponse retarget(String to, String receiverFromParameter) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "change_signature");
        args.put("symbol", TARGET);
        args.put("retargetCallsTo", to);
        if (receiverFromParameter != null) {
            args.put("receiverFromParameter", receiverFromParameter);
        }
        args.put("auto_apply", false);
        return tool.execute(args);
    }

    /** The result side of a unified diff — the added lines, without the leading marker. */
    private static String addedLines(String diff) {
        StringBuilder out = new StringBuilder();
        for (String line : diff.split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                out.append(line.substring(1)).append('\n');
            }
        }
        return out.toString();
    }

    private static String describe(ToolResponse r) {
        return r.getError() == null ? "(no error)"
            : r.getError().getReason() + " / " + r.getError().getMessage();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolResponse r) {
        Object data = r.getData();
        assertNotNull(data, "a successful stage carries data");
        return (Map<String, Object>) data;
    }
}
