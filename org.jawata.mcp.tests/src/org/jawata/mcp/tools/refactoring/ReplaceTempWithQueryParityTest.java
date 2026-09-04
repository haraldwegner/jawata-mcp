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
 * Sprint 28d-rescue row 58 — Replace Temp with Query: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves here and what it does not.
 * In short: this row is a new operation, so a golden recorded from it is a snapshot of
 * itself. It pins the exact outcome on the fixture and fails the day that changes for any
 * reason — a JDT upgrade, a refactor of the rule, an edit to the fixture. Behaviour
 * preservation lives in the compile gate and in ReplaceTempWithQueryTest's refusals.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class ReplaceTempWithQueryParityTest {

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
    @DisplayName("row 58 pins the query and every use that now reads it")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "extract", "temp_to_query",
            (service, cache) -> new ExtractTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "temp_to_query");
                Path file = pkg.resolve("TempHolder.java");
                args.put("filePath", file.toString());
                try {
                    args.put("line", lineOf(file, "int basePrice = quantity * 7;"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                args.put("column", 12);

                return args;
            },
            List.of("TempHolder.java"));
    }
}
