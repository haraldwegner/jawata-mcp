package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

/**
 * Sprint 28d-rescue row 28 — Preserve Whole Object: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves and what it does not. This row
 * WRITES its change, and the golden's subject is the part the unit test cannot express: the exact
 * ORDER the arguments end up in at each call site, and the fact that the six other methods in the
 * fixture — every one of them a case this row refuses — are untouched by a run aimed at one.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class PreserveWholeObjectParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 28 pins the folded signature, the argument order, and the callers it left")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "change_method_signature", "preserve_whole_object",
            (service, cache) -> new ChangeMethodSignatureTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "preserve_whole_object");
                args.put("symbol", "com.example.WholeObjectTargets#withinRange");
                args.putArray("parameters").add("low").add("high");
                return args;
            },
            List.of("WholeObjectTargets.java", "WholeObjectDesk.java"));
    }
}
