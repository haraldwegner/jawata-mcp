package org.jawata.mcp.tools.refactoring;

import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Row 34 parity battery — unreachable private members and locals go.
 *
 * <p>A REGRESSION LOCK, not a proof that behaviour is preserved. {@link RowParity} says
 * why the distinction matters for a new operation and names what does carry that
 * weight.</p>
 */
class RemoveDeadCodeParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("parity: remove_dead_code over DeadCodeTargets.java")
    void parity() throws Exception {
        RowParity.sweepRow(helper, "remove_dead_code", "DeadCodeTargets.java");
    }
}
