package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.tools.HierarchyTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

/**
 * Sprint 28d-rescue row 4 — Collapse Hierarchy: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves and what it does not. What it is
 * carrying HERE is the shape no single assertion covers: the parent after it absorbed the level,
 * and BOTH subtypes after they were reparented, in one artifact. A containment check can say
 * {@code extends TierBase} appears; only the golden shows what the three files look like
 * together, which is what a reader has to judge.</p>
 *
 * <p>The collapsed file itself is deliberately NOT pinned — it is deleted, and a golden of an
 * absent file is a golden of the word "absent".</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class CollapseHierarchyParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 4 pins the widened parent and both reparented subtypes")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "hierarchy", "collapse_hierarchy",
            (service, cache) -> new HierarchyTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("direction", "collapse_hierarchy");
                args.put("typeName", "com.example.TierBanded");
                return args;
            },
            List.of("TierBase.java", "TierGold.java", "TierSilver.java", "TierDesk.java"));
    }
}
