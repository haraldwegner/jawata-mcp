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
 * Sprint 28d-rescue row 56 — Replace Subclass with Delegate: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves and what it does not. This row
 * GENERATES SOURCE, and row 59's mutation measured what that means here: with a generated file's
 * references broken, nine of ten unit tests stayed green — every containment assertion AND the
 * compile gate — and only the golden noticed. The gate parses a created file without resolving
 * it, so for a generating row the golden is not one instrument among several. It is the one that
 * reads what was written.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class ReplaceSubclassWithDelegateParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 56 pins the generated behaviour class and the subclass it left standing")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "hierarchy", "replace_subclass_with_delegate",
            (service, cache) -> new HierarchyTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("direction", "replace_subclass_with_delegate");
                args.put("typeName", "com.example.Zeroed");
                return args;
            },
            List.of("Zeroed.java", "ZeroedBehaviour.java"));
    }
}
