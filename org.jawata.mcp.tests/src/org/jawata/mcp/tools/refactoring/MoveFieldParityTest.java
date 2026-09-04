package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.tools.MoveTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Sprint 28d-rescue row 23 — Move Field: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves here and what it does not.
 * In short: this row is a new operation, so a golden recorded from it is a snapshot of
 * itself. It pins the exact outcome on the fixture and fails the day that changes for any
 * reason — a JDT upgrade, a refactor of the rule, an edit to the fixture. Behaviour
 * preservation lives in the compile gate and in MoveFieldTest's refusals.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class MoveFieldParityTest {

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
    @DisplayName("row 23 pins where the field went and how its accesses read")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "move", "move_field",
            (service, cache) -> new MoveTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "field");
                Path file = pkg.resolve("MoveFieldSource.java");
                args.put("filePath", file.toString());
                try {
                    args.put("line", lineOf(file, "public static final int SHARED_LIMIT"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                args.put("column", 19);
                args.put("targetType", "com.example.MoveFieldTarget");

                return args;
            },
            List.of("MoveFieldSource.java", "MoveFieldTarget.java", "MoveFieldUser.java"),
            // The DESTINATION only. JDT's Move Static Members does not promise where in the
            // type the moved member lands, and a full-suite run under four parallel shards
            // put it above `describe()` where an isolated run put it below — both correct.
            // Pinning that position made the lock fire on load rather than on a regression,
            // which is worse than no lock: a flake teaches everyone to re-record without
            // reading. The source file and the referring file stay pinned exactly, because
            // the engine DOES promise those.
            java.util.Set.of("MoveFieldTarget.java"));
    }
}
