package org.jawata.mcp.tools.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S3 / D7b — a signature change must rewrite the TARGET call and not the call it
 * happens to sit inside.
 *
 * <p>The field report: changing a constructor whose calls were the second argument of a
 * six-argument {@code new OrbEventProcessor(...)} produced a diff replacing the WHOLE OUTER
 * CALL at four test sites — five arguments of a constructor that was not the target, gone. It
 * was caught only because the change was staged rather than applied, which is why this test
 * stages too and reads the diff.</p>
 */
class ANestedTargetCallDoesNotEatItsEnclosingCallTest {

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
    @DisplayName("the four enclosing calls keep their own arguments")
    void theEnclosingCallsSurvive() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "change_signature");
        args.put("symbol", "com.example.NestedCallTargets.Inner#Inner");
        ArrayNode params = args.putArray("newParameters");
        ObjectNode seed = params.addObject();
        seed.put("name", "seed");
        seed.put("type", "int");
        seed.put("defaultValue", "0");
        args.put("auto_apply", false);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "staging failed: " + r.getError());

        // THE RESULT SIDE ONLY, and that is the whole point of this helper rather than a
        // `contains` over the response. The first version of this test searched the WHOLE
        // rendered response and PASSED while the defect was live, because a unified diff
        // carries the old text on its `-` lines — so it matched the very literals the change
        // was deleting. That is the vacuous-assertion shape this sprint keeps producing, and
        // it is why nothing here may read the before-side.
        String added = addedLines(String.valueOf(dataOf(r).get("diff")));

        // Each enclosing call must still carry its OWN arguments. The defect replaced the
        // whole `new Outer(new Inner(..), label, scale)` with `new Outer(0)` — the target's
        // new argument list grafted onto a constructor that was never the target, at all four
        // sites. The labels are deliberately distinctive so a substring cannot match by luck.
        for (String label : new String[] {
                "siteOneLabel", "siteTwoLabel", "siteThreeLabel", "siteFourLabel" }) {
            assertTrue(added.contains(label),
                () -> "the enclosing call lost its own arguments at " + label + " — that is "
                    + "D7b: the TARGET call is an argument, and the call it sits INSIDE was "
                    + "rewritten instead. Added lines were:\n" + added);
        }
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }
}
