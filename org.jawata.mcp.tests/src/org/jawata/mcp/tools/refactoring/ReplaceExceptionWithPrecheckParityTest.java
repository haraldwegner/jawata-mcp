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
 * Sprint 28d-rescue row 47 — Replace Exception with Precheck: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves and what it does not. This row
 * WRITES its change, and what the unit test cannot express is the SHAPE of what it wrote: where
 * the guard was placed relative to the statement it guards, whether the handler's braces and body
 * came across intact, and what the surrounding methods look like afterwards — all of which a
 * {@code contains} on one condition is blind to.</p>
 *
 * <p>The other eight methods in the fixture are the real subject. Every one of them is a case this
 * row REFUSES, so the golden also pins that a run acting on one method leaves all of them exactly
 * as they were.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class ReplaceExceptionWithPrecheckParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 47 pins the guard it wrote, where it put it, and the eight methods it left")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "change_method_signature", "replace_exception_with_precheck",
            (service, cache) -> new ChangeMethodSignatureTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "replace_exception_with_precheck");
                args.put("symbol", "com.example.PrecheckTargets#readingAt");
                return args;
            },
            List.of("PrecheckTargets.java"));
    }
}
