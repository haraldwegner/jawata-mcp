package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.tools.ExtractTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Sprint 28d-rescue row 5 — Combine Functions into Class: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves here and what it does not.
 * In short: this row is a new operation, so a golden recorded from it is a snapshot of
 * itself. It pins the exact outcome on the fixture and fails the day that changes for any
 * reason — a JDT upgrade, a refactor of the rule, an edit to the fixture. Behaviour
 * preservation lives in the compile gate and in CombineFunctionsIntoClassTest's refusals.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class CombineFunctionsIntoClassParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    /** The nth line containing the marker, zero-based — the caret is FOUND, never counted. */
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
    @DisplayName("row 5 pins the generated class and the reconstructed call sites")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "extract", "combine_functions",
            (service, cache) -> new ExtractTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "combine_functions");
                Path file = pkg.resolve("ReadingFunctions.java");
                args.put("filePath", file.toString());
                args.put("newTypeName", "ReadingCharge");
                args.putArray("functions").add("baseCharge").add("taxThreshold");

                return args;
            },
            List.of("ReadingFunctions.java", "ReadingCharge.java", "ReadingCaller.java"));
    }
}
