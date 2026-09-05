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
 * Sprint 28d-rescue row 2 — Change Reference to Value: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves here and what it does not.</p>
 *
 * <p><b>What the golden is uniquely good for here is the GENERATED EQUALITY.</b> The row's
 * last step emits an equals and a hashCode whose exact bodies decide what the class MEANS —
 * which fields are compared, whether a primitive is compared with {@code ==} or through
 * {@code Objects.equals}, what happens for null. No substring assertion states any of that,
 * and a change to the generator that quietly dropped a field from the comparison would leave
 * every named assertion green. The golden pins the emitted text, so it cannot.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class ChangeReferenceToValueParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 2 pins the removals, the direct assignments, and the generated equality")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "data", "reference_to_value",
            (service, cache) -> new DataTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "reference_to_value");
                Path file = pkg.resolve("ReferenceToValueTargets.java");
                args.put("filePath", file.toString());
                try {
                    String[] lines = Files.readString(file, StandardCharsets.UTF_8)
                        .split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].contains("public static class Money")) {
                            args.put("line", i);
                            args.put("column", lines[i].indexOf("Money"));
                            return args;
                        }
                    }
                    throw new AssertionError("PROOF OF LIFE: the fixture no longer declares"
                        + " Money");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            },
            List.of("ReferenceToValueTargets.java"));
    }
}
