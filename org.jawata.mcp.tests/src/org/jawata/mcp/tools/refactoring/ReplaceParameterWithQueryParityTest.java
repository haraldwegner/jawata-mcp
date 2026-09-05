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
 * Sprint 28d-rescue row 53 — Replace Parameter with Query: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves and what it does not. This row
 * WRITES its change rather than wrapping an engine, so the golden's subject is the whole
 * outcome: the shortened signature, the derivation the body now performs, and BOTH shortened
 * call sites in the other file.</p>
 *
 * <p>The call sites are what a substring assertion is worst at here. The three target methods
 * differ only in their bodies, and the desk calls each of them; a change that shortened the
 * wrong method's callers, or shortened one caller and not its neighbour, would still satisfy
 * every containment the unit test can express. The golden holds the desk's whole text, so a
 * caller that moved and should not have is a divergence rather than a silence.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class ReplaceParameterWithQueryParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 53 pins the shortened signature, the derivation, and both shortened callers")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "change_method_signature", "replace_parameter_with_query",
            (service, cache) -> new ChangeMethodSignatureTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "replace_parameter_with_query");
                args.put("symbol", "com.example.DerivedParameterTargets#discounted");
                args.put("parameter", "rate");
                return args;
            },
            List.of("DerivedParameterTargets.java", "DerivedParameterDesk.java"));
    }
}
