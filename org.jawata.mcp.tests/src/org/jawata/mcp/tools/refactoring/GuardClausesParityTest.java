package org.jawata.mcp.tools.refactoring;

import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Row 52 parity battery — nested conditionals become guard clauses.
 *
 * <p>A REGRESSION LOCK, not a proof that behaviour is preserved. {@link RowParity} says
 * why the distinction matters for a new operation and names what does carry that
 * weight.</p>
 */
class GuardClausesParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("parity: guard_clauses over GuardClauseTargets.java")
    void parity() throws Exception {
        RowParity.sweepRow(helper, "guard_clauses", "GuardClauseTargets.java");
    }
}
