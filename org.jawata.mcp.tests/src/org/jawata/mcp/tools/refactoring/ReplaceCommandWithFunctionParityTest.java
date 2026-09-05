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
 * Sprint 28d-rescue row 41 — Replace Command with Function: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves and what it does not. This row
 * DELETES members and rewrites a signature, so the golden's subject is the whole shape of what is
 * left: which members survived, in what order, with what spacing — and the six sibling commands in
 * the same file, every one of them a case this row refuses, untouched by a run aimed at one.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class ReplaceCommandWithFunctionParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 41 pins the emptied class, the new signature, and the uses it rewrote")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "change_method_signature", "replace_command_with_function",
            (service, cache) -> new ChangeMethodSignatureTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "replace_command_with_function");
                args.put("typeName", "com.example.CommandTargets.Discount");
                return args;
            },
            List.of("CommandTargets.java", "CommandDesk.java"));
    }
}
