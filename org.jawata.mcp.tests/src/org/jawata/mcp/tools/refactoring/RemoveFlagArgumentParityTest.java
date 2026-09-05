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
 * Sprint 28d-rescue row 35 — Remove Flag Argument: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves and what it does not. This row
 * GENERATES two methods, and the golden's subject is everything a containment check is blind to:
 * their whole text — modifiers, javadoc, parameter list — where in the class they were placed, and
 * in which order.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class RemoveFlagArgumentParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 35 pins both generated methods, where they landed, and the moved callers")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "change_method_signature", "remove_flag_argument",
            (service, cache) -> new ChangeMethodSignatureTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "remove_flag_argument");
                args.put("symbol", "com.example.FlagArgumentTargets#price");
                args.put("parameter", "premium");
                args.put("whenTrue", "atPremium");
                args.put("whenFalse", "atStandard");
                return args;
            },
            List.of("FlagArgumentTargets.java", "FlagArgumentDesk.java"));
    }
}
