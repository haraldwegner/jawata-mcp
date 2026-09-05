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
 * Sprint 28d-rescue row 22 — Introduce Special Case: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves here and what it does not.
 * This row is a NEW operation, so a golden recorded from it is a snapshot of itself. It pins
 * the exact outcome and fails the day that changes for any reason. Behaviour preservation
 * lives in the compile gate and in {@code IntroduceSpecialCaseToolTest}'s four refusals.</p>
 *
 * <p><b>What the golden is uniquely good for here is the NEUTRALITY POLICY.</b> Which value
 * counts as "nothing" per return type — empty string, zero, false, an empty collection — is
 * a table of decisions living in one method, and every one of them is visible in the
 * generated text. A change to any single entry moves this golden, where the unit test only
 * asserts the four types its fixture happens to use.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class IntroduceSpecialCaseParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static int lineOf(Path file, String marker) throws Exception {
        String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                return i;
            }
        }
        throw new AssertionError("PROOF OF LIFE: " + file.getFileName() + " no longer has "
            + marker);
    }

    @Test
    @DisplayName("row 22 pins the generated null object and every neutral answer in it")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "data", "special_case",
            (service, cache) -> new DataTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "special_case");
                Path file = pkg.resolve("SpecialCaseTargets.java");
                args.put("filePath", file.toString());
                try {
                    args.put("line", lineOf(file, "public static class Customer {"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                args.put("column", 30);
                return args;
            },
            List.of("SpecialCaseTargets.java"));
    }
}
