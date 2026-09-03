package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Row 8 parity battery — a tangled if becomes a named test and a named branch each side.
 *
 * <p>Pinned by RESULTING SOURCE rather than a planned diff, because this row is a
 * multi-step recipe and cannot be staged; {@link RowParity#recipeRow} carries the reason.
 * A REGRESSION LOCK, not a proof that behaviour is preserved — see {@link RowParity}.</p>
 *
 * <h2>It used to pin a defect, and no longer does</h2>
 *
 * <p>This golden's three generated methods were TAB-indented, in a fixture that uses
 * spaces, because this row routes through {@code ExtractMethodTool} and that engine was
 * being constructed without formatter options. Two central routes were tried and measured
 * as not reaching it; the answer was a constructor overload taking an options map, which
 * all three extract engines have. The golden was re-recorded and every golden in the tree
 * is now free of tabs.</p>
 *
 * <p>The paragraph this replaces said the defect was open and the fix a repo-wide scope
 * decision. It was written one commit before the fix and left standing after it — which
 * is worth leaving a mark about, since this is the file someone opens to learn what the
 * golden means.</p>
 */
class DecomposeConditionalParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("parity: decompose_conditional over DecomposeConditionalTargets.java")
    void parity() throws Exception {
        ObjectNode names = new ObjectMapper().createObjectNode();
        // The names are the caller's input, so they are part of what is pinned: a
        // different set of names is a different operation.
        names.put("conditionName", "notSummer");
        names.put("thenName", "applyWinterCharge");
        names.put("elseName", "applySummerCharge");
        RowParity.recipeRow(helper, "DecomposeConditionalTargets.java",
            "if (date.isBefore", names);
    }
}
