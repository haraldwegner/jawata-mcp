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
 * Sprint 28d-rescue row 45 — Replace Derived Variable with Query: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves here and what it does not.
 * This row is a NEW operation, so a golden recorded from it is a snapshot of itself.
 * Behaviour preservation lives in the compile gate and in
 * {@code ReplaceDerivedVariableWithQueryToolTest}'s five refusals.</p>
 *
 * <p><b>What the golden is uniquely good for here is the DELETIONS.</b> This row is the only
 * one in Stage 5 whose main effect is removal — a field and every assignment to it — and a
 * substring assertion can say a line is gone but not that nothing ELSE went with it. The
 * golden pins both files whole, so a rewrite that deleted one statement too many shows up
 * even where no test names that statement.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class ReplaceDerivedVariableWithQueryParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 45 pins the query, the deletions, and the reader in the other file")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "data", "replace_derived_variable",
            (service, cache) -> new DataTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "replace_derived_variable");
                Path file = pkg.resolve("DerivedVariableTargets.java");
                args.put("filePath", file.toString());
                try {
                    // The PUBLIC field, deliberately: it is the one with a reader in another
                    // file, so the golden covers the cross-file half as well as the
                    // deletions.
                    String[] lines = Files.readString(file, StandardCharsets.UTF_8)
                        .split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].contains("public int grossAmount;")) {
                            args.put("line", i);
                            args.put("column", lines[i].indexOf("grossAmount"));
                            return args;
                        }
                    }
                    throw new AssertionError("PROOF OF LIFE: the fixture no longer declares"
                        + " grossAmount");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            },
            List.of("DerivedVariableTargets.java", "DerivedVariableUser.java"));
    }
}
