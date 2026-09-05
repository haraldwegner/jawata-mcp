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
 * Sprint 28d-rescue row 46 — Replace Error Code with Exception: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves and what it does not. This row
 * rewrites a return into a throw and edits the method's {@code throws} clause, so the golden's
 * subject is the shape of both — and, more than in most rows, the three SIBLING methods in the
 * same file, each of which returns the same sentinel and each of which this run must leave exactly
 * as it found it.</p>
 *
 * <p>That last part is what a containment check is worst at here: the sentinel appears four times
 * in this fixture, so an assertion that it is "gone" can only ever be scoped to one method's text.
 * The golden holds the whole file.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class ReplaceErrorCodeWithExceptionParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 46 pins the throw, the throws clause, and the three siblings it left alone")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "change_method_signature",
            "replace_error_code_with_exception",
            (service, cache) -> new ChangeMethodSignatureTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "replace_error_code_with_exception");
                args.put("symbol", "com.example.ErrorCodeTargets#readingAt");
                args.put("errorValue", "-1");
                args.put("exceptionType", "IllegalStateException");
                args.put("message", "no reading at that slot");
                return args;
            },
            List.of("ErrorCodeTargets.java"));
    }
}
