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
 * Sprint 28d-rescue row 54 — Replace Primitive with Object: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves here and what it does not.
 * This row is a NEW operation, so a golden recorded from it is a snapshot of itself. It pins
 * the exact outcome and fails the day that changes for any reason. Behaviour preservation
 * lives in the compile gate and in {@code ReplacePrimitiveWithObjectToolTest}'s refusals.</p>
 *
 * <p><b>What the golden is uniquely good for here is the TWO-FILE OUTCOME.</b> This row's
 * defining claim is that it migrates usages the caller never pointed it at, and the unit test
 * asserts a handful of substrings in each file. The golden pins BOTH files whole — so a change
 * that kept every asserted substring and altered anything else about either file, including
 * the file the operation was not given, moves it.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class ReplacePrimitiveWithObjectParityTest {

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
    @DisplayName("row 54 pins the generated record and every migrated usage, in BOTH files")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "data", "replace_primitive",
            (service, cache) -> new DataTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "replace_primitive");
                Path file = pkg.resolve("ReplacePrimitiveTargets.java");
                args.put("filePath", file.toString());
                try {
                    // The PUBLIC field, deliberately: it is the one with a reader in another
                    // file, so the golden covers the cross-file half rather than only the
                    // declaring one.
                    int line = lineOf(file, "public String carrier");
                    args.put("line", line);
                    args.put("column", Files.readString(file, StandardCharsets.UTF_8)
                        .split("\n", -1)[line].indexOf("carrier"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return args;
            },
            List.of("ReplacePrimitiveTargets.java", "ReplacePrimitiveUser.java"));
    }
}
