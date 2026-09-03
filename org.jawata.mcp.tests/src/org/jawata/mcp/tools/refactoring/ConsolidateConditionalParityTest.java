package org.jawata.mcp.tools.refactoring;

import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Row 7 parity battery — adjacent checks with one outcome become one check.
 *
 * <p>A REGRESSION LOCK, not a proof that behaviour is preserved. {@link RowParity} says
 * why the distinction matters for a new operation and names what does carry that
 * weight.</p>
 */
class ConsolidateConditionalParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("parity: consolidate_conditional over ConsolidateTargets.java")
    void parity() throws Exception {
        RowParity.sweepRow(helper, "consolidate_conditional", "ConsolidateTargets.java");
    }
}
