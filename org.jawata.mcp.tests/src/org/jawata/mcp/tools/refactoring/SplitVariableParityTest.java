package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.tools.DataTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Sprint 28d-rescue row 65 — Split Variable: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves here and what it does not.
 * This row is a NEW operation, so a golden recorded from it is a snapshot of itself.
 * Behaviour preservation lives in the compile gate and in {@code SplitVariableToolTest}'s
 * six refusals.</p>
 *
 * <p><b>What the golden is uniquely good for here is WHICH USES MOVED.</b> The split's whole
 * correctness is a boundary: uses before the assignment keep the old name, uses after take the
 * new one. The unit test asserts one on each side; the golden pins every line of the method,
 * so a change that moved the boundary by one statement — the defect this operation can
 * actually have — shows up whether or not a test happened to assert that line.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class SplitVariableParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 65 pins the new declaration and exactly which uses moved to it")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "data", "split_variable",
            (service, cache) -> new DataTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "split_variable");
                Path file = pkg.resolve("SplitVariableTargets.java");
                args.put("filePath", file.toString());
                args.put("newName", "area");
                try {
                    String[] lines = Files.readString(file, StandardCharsets.UTF_8)
                        .split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].contains("double temp = 2 * (height + width);")) {
                            args.put("line", i);
                            args.put("column", lines[i].indexOf("temp"));
                            return args;
                        }
                    }
                    throw new AssertionError("PROOF OF LIFE: the fixture no longer declares"
                        + " temp");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            },
            List.of("SplitVariableTargets.java"));
    }
}
