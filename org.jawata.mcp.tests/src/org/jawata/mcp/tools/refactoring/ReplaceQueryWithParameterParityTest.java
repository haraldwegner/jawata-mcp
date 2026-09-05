package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Sprint 28d-rescue row 55 — Replace Query with Parameter: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves and what it does not. This row
 * WRAPS JDT's engine rather than implementing the rewrite, so the golden's subject is the shape
 * the engine produces THROUGH OUR CONFIGURATION — and here that configuration is unusually
 * load-bearing. The parameter is named in the one window between JDT's two condition checks;
 * set a moment earlier it throws, a moment later it is silently replaced by JDT's own guess.
 * A golden is what notices if that window moves, because the row would still succeed and still
 * compile — it would just quietly stop honouring the name it was given.</p>
 *
 * <p>Both files the row rewrites are pinned: the method's own, and the CALLER's in another
 * file, which is where the query it stopped asking now gets asked.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class ReplaceQueryWithParameterParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 55 pins the collapsed body, the new parameter's NAME, and the query "
        + "moved into the caller")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "change_method_signature", "replace_query_with_parameter",
            (service, cache) -> new ChangeMethodSignatureTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "replace_query_with_parameter");
                args.put("queryCall", "defaultTemperature");
                args.put("parameterName", "wantedTemperature");
                Path file = pkg.resolve("QueryParameterTargets.java");
                args.put("filePath", file.toString());
                try {
                    String[] lines = Files.readString(file, StandardCharsets.UTF_8)
                        .split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].contains("public int heatingPlan(")) {
                            args.put("line", i);
                            args.put("column", lines[i].indexOf("heatingPlan"));
                            return args;
                        }
                    }
                    throw new AssertionError("PROOF OF LIFE: the fixture no longer declares"
                        + " heatingPlan");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            },
            List.of("QueryParameterTargets.java", "QueryParameterDesk.java"));
    }
}
