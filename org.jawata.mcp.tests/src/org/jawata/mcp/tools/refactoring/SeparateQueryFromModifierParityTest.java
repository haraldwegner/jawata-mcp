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
 * Sprint 28d-rescue row 61 — Separate Query from Modifier: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves and what it does not. This row
 * WRITES its change rather than wrapping an engine, so the golden's subject is unusually broad:
 * the generated query's whole text — its modifiers, its javadoc, where in the class it was
 * placed — plus the exact shape of both call sites, the one that was split and the one that was
 * deliberately left alone.</p>
 *
 * <p>The second of those is what a substring assertion is worst at. A change that started
 * splitting command-only calls would compile, would pass every assertion about the method it was
 * pointed at, and would add a read nobody asked for at every such site. The golden is what
 * notices.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class SeparateQueryFromModifierParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 61 pins the generated query, the split call, and the command-only call "
        + "it left alone")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "change_method_signature", "separate_query_from_modifier",
            (service, cache) -> new ChangeMethodSignatureTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "separate_query_from_modifier");
                args.put("symbol", "com.example.CommandQueryTargets#recordFailure");
                args.put("queryName", "failureCount");
                return args;
            },
            List.of("CommandQueryTargets.java", "CommandQueryDesk.java"));
    }
}
