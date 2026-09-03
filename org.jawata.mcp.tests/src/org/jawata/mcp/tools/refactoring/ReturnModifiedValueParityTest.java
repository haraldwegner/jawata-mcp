package org.jawata.mcp.tools.refactoring;

import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Row 60 parity battery — an answer-holding local becomes direct returns.
 *
 * <p>A REGRESSION LOCK, not a proof that behaviour is preserved. {@link RowParity} says
 * why the distinction matters for a new operation and names what does carry that
 * weight.</p>
 */
class ReturnModifiedValueParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("parity: return_modified_value over ReturnModifiedValueTargets.java")
    void parity() throws Exception {
        RowParity.sweepRow(helper, "return_modified_value", "ReturnModifiedValueTargets.java");
    }
}
