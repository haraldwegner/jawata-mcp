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
 * Sprint 28d-rescue row 37 — Remove Setting Method: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves here and what it does not.
 * This row is a NEW operation, so a golden recorded from it is a snapshot of itself.
 * Behaviour preservation lives in the compile gate and in the four refusals
 * {@code RemoveSettingMethodToolTest} pins.</p>
 *
 * <p><b>What the golden is uniquely good for here is the THREE EDITS AT ONCE.</b> This row
 * removes a method, rewrites a statement in another member, and adds a modifier to a third —
 * and the substring assertions can each say their own line landed while saying nothing about
 * what else moved in a file the row rewrote in three places. The golden pins the file whole,
 * so an edit that took a neighbouring line with it shows up even where no test names that
 * line.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class RemoveSettingMethodParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 37 pins the removal, the constructor's new assignment, and the modifier")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "data", "remove_setting_method",
            (service, cache) -> new DataTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "remove_setting_method");
                Path file = pkg.resolve("SettingMethodTargets.java");
                args.put("filePath", file.toString());
                try {
                    // The canonical shape: its constructor is the only caller, so this run
                    // exercises all three edits rather than the removal alone.
                    String[] lines = Files.readString(file, StandardCharsets.UTF_8)
                        .split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].contains("public void setCarrier(String carrier)")) {
                            args.put("line", i);
                            args.put("column", lines[i].indexOf("setCarrier"));
                            return args;
                        }
                    }
                    throw new AssertionError("PROOF OF LIFE: the fixture no longer declares"
                        + " setCarrier");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            },
            List.of("SettingMethodTargets.java"));
    }
}
