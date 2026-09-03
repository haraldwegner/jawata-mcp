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
