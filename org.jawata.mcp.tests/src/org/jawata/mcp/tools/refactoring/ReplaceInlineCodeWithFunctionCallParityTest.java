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
 * Sprint 28d-rescue row 49 — Replace Inline Code with Function Call: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves here and what it does not.
 * In short: this row is a new operation, so a golden recorded from it is a snapshot of
 * itself. It pins the exact outcome on the fixture and fails the day that changes for any
 * reason — a JDT upgrade, a refactor of the rule, an edit to the fixture. Behaviour
 * preservation lives in the compile gate and in ReplaceInlineCodeWithFunctionCallTest's refusals.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class ReplaceInlineCodeWithFunctionCallParityTest {

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
    @DisplayName("row 49 pins the extraction WITH replaceDuplicates set, which is the row")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "extract", "replace_inline_code",
            (service, cache) -> new ExtractTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "method");
                Path file = pkg.resolve("InlineCodeTwice.java");
                args.put("filePath", file.toString());
                try {
                    args.put("startLine", lineOf(file, "int scaled = base * 3;"));
                    args.put("endLine", lineOf(file, "int shifted = scaled + 7;"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                args.put("startColumn", 8);
                args.put("endColumn", 8 + "int shifted = scaled + 7;".length());
                args.put("methodName", "scale");
                args.put("replaceDuplicates", true);

                return args;
            },
            List.of("InlineCodeTwice.java"));
    }
}
