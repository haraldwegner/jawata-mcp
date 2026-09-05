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
 * Sprint 28d-rescue row 16 — Hide Delegate: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves here and what it does not. In
 * short: this row is a NEW operation, so a golden recorded from it is a snapshot of itself.
 * It pins the exact outcome on the fixture and fails the day that changes for any reason — a
 * JDT upgrade, a refactor of the rule, an edit to the fixture. Behaviour preservation lives
 * in the compile gate and in {@code HideDelegateToolTest}'s five refusals.</p>
 *
 * <p><b>ONE FILE is pinned, and that is a property of the fixture rather than of the row.</b>
 * Hide Delegate is a cross-file operation in general — the forwarder lands on the server,
 * the shortened call on the client — but this fixture nests {@code Person} inside
 * {@code HideDelegateTargets}, so both edits land in one compilation unit. That is also the
 * case the implementation most easily gets wrong: the server rewrite and the caller rewrite
 * must travel as ONE {@code ASTRewrite} or the second overwrites the first, and a two-file
 * fixture would never exercise it.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class HideDelegateParityTest {

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
    @DisplayName("row 16 pins the generated forwarder and the shortened call site")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "data", "hide_delegate",
            (service, cache) -> new DataTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "hide_delegate");
                Path file = pkg.resolve("HideDelegateTargets.java");
                args.put("filePath", file.toString());
                try {
                    args.put("line", lineOf(file, "return john.getDepartment().getManager();"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                args.put("column", 20);
                return args;
            },
            List.of("HideDelegateTargets.java"));
    }
}
