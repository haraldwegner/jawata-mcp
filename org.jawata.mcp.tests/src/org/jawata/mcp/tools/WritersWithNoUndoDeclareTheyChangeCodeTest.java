package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jawata.mcp.tools.build.DependencyTool;
import org.jawata.mcp.tools.workflow.FormatTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M11 reads a hired run's tool calls through {@link Tool#changesCode()}. The three registered
 * tools that write source or build files without an undo are NOT revertible, so the flag the
 * precedent guard uses cannot say they change code — each has to say it itself, and nothing else
 * would notice one that stopped.
 */
class WritersWithNoUndoDeclareTheyChangeCodeTest {

    @Test
    @DisplayName("format, quick_fix and dependency write files with no undo, and say they change code")
    void theRegisteredWritersWithNoUndoSayTheyChangeCode() {
        Tool format = new FormatTool(() -> null);
        Tool quickFix = new QuickFixTool(() -> null);
        Tool dependency = new DependencyTool(() -> null);
        assertAll(
            () -> assertFalse(format.isRevertible(),
                "premise: format has no undo, which is why the revertible flag cannot stand in"),
            () -> assertFalse(quickFix.isRevertible()),
            () -> assertFalse(dependency.isRevertible()),
            () -> assertTrue(format.changesCode(), "format"),
            () -> assertTrue(quickFix.changesCode(), "quick_fix"),
            () -> assertTrue(dependency.changesCode(), "dependency"));
    }
}
