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
 * Sprint 28d-rescue row 27 — Parameterize Function: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves and what it does not. What it
 * pins here that a substring assertion would not: the SIBLING method, which this row
 * deliberately leaves alone. Folding it in is a separate redirect on the same door, so a change
 * that quietly started folding it would still compile, still pass every assertion about the
 * parameterized method, and be a different refactoring — the golden is what notices.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class ParameterizeFunctionParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 27 pins the parameterized method, the untouched sibling, and the "
        + "constant moved into the caller")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "change_method_signature", "parameterize_function",
            (service, cache) -> new ChangeMethodSignatureTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "parameterize_function");
                args.put("literal", "1.1");
                args.put("parameterName", "factor");
                Path file = pkg.resolve("ParameterizeTargets.java");
                args.put("filePath", file.toString());
                try {
                    String[] lines = Files.readString(file, StandardCharsets.UTF_8)
                        .split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].contains("public double tenPercentRaise(")) {
                            args.put("line", i);
                            args.put("column", lines[i].indexOf("tenPercentRaise"));
                            return args;
                        }
                    }
                    throw new AssertionError("PROOF OF LIFE: the fixture no longer declares"
                        + " tenPercentRaise");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            },
            List.of("ParameterizeTargets.java", "ParameterizeDesk.java"));
    }
}
