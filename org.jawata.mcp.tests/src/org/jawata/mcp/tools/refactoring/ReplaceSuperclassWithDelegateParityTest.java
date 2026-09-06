package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.tools.HierarchyTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

/**
 * Sprint 28d-rescue row 57 — Replace Superclass with Delegate: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves and what it does not. This row
 * makes four edits to one file that must all land together — the {@code extends} removed, a
 * field inserted, a {@code super(...)} rewritten into a construction, and each inherited call
 * given a receiver — and THREE of the four are individually valid Java without the others. A
 * containment check sees each in isolation; the golden sees the file.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class ReplaceSuperclassWithDelegateParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 57 pins the removed extends, the delegate field, the rewired constructor "
        + "and the forwarded call")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "hierarchy", "replace_superclass_with_delegate",
            (service, cache) -> new HierarchyTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("direction", "replace_superclass_with_delegate");
                args.put("typeName", "com.example.Petty");
                return args;
            },
            List.of("Petty.java", "Ledgering.java"));
    }
}
