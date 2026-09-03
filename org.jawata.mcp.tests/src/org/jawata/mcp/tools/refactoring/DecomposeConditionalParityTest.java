package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Row 8 parity battery — a tangled if becomes a named test and a named branch each side.
 *
 * <p>Pinned by RESULTING SOURCE rather than a planned diff, because this row is a
 * multi-step recipe and cannot be staged; {@link RowParity#recipeRow} carries the reason.
 * A REGRESSION LOCK, not a proof that behaviour is preserved — see {@link RowParity}.</p>
 *
 * <h2>THIS GOLDEN PINS A KNOWN DEFECT, deliberately</h2>
 *
 * <p>The three generated methods in it are TAB-indented, in a fixture that uses spaces.
 * That is not this row's doing: it routes through {@code ExtractMethodTool}, and JDT's
 * refactoring engines take no formatter options and read the preference store themselves.
 * The nine rules we own were fixed by passing {@code FormatterOptions} to
 * {@code rewriteAST}; this path cannot be reached that way, and both central routes have
 * been tried and measured — {@code HeadlessJdtConfig} records which and why neither
 * worked.</p>
 *
 * <p>The lock stays because it pins what the tool ACTUALLY produces, which is what a
 * regression lock is for. It will change when the engine's formatting is fixed, and that
 * fix moves {@code extract}'s output across the repository — the pre-existing
 * extract-variable and extract-constant goldens carry the same tabs — so it is a scope
 * decision rather than a tidy-up. Until then a reader of this golden should know the
 * indentation in it is wrong and known.</p>
 */
class DecomposeConditionalParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("parity: decompose_conditional over DecomposeConditionalTargets.java")
    void parity() throws Exception {
        ObjectNode names = new ObjectMapper().createObjectNode();
        // The names are the caller's input, so they are part of what is pinned: a
        // different set of names is a different operation.
        names.put("conditionName", "notSummer");
        names.put("thenName", "applyWinterCharge");
        names.put("elseName", "applySummerCharge");
        RowParity.recipeRow(helper, "DecomposeConditionalTargets.java",
            "if (date.isBefore", names);
    }
}
