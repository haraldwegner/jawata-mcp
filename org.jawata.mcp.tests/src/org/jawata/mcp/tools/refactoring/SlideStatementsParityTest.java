package org.jawata.mcp.tools.refactoring;

import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Row 62 parity battery — a declaration slides down to its use.
 *
 * <p>A REGRESSION LOCK, not a proof that behaviour is preserved. {@link RowParity} says
 * why the distinction matters for a new operation and names what does carry that
 * weight.</p>
 */
class SlideStatementsParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("parity: slide_declaration over SlideTargets.java")
    void parity() throws Exception {
        RowParity.sweepRow(helper, "slide_declaration", "SlideTargets.java");
    }
}
