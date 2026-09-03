package org.jawata.mcp.tools.refactoring;

import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Row 50 parity battery — an accumulation loop becomes a stream pipeline.
 *
 * <p>A REGRESSION LOCK, not a proof that behaviour is preserved. {@link RowParity} says
 * why the distinction matters for a new operation and names what does carry that
 * weight.</p>
 */
class LoopToPipelineParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("parity: loop_to_pipeline over PipelineTargets.java")
    void parity() throws Exception {
        RowParity.sweepRow(helper, "loop_to_pipeline", "PipelineTargets.java");
    }
}
