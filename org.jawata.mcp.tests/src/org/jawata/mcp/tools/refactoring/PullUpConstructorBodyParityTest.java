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
 * Sprint 28d-rescue row 29 — Pull Up Constructor Body: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves and what it does not. This row's
 * subject is unusually well suited to one: it edits a SUPERCLASS and a SUBCLASS that live in the
 * SAME file, and the first version silently rewrote only one of them because both edits were
 * keyed by file and the second replaced the first. That produced VALID JAVA — a subclass which
 * still assigns the fields itself compiles perfectly — so the compile gate could not see it and
 * a containment check on either class alone would not have either.</p>
 *
 * <p>The golden holds the whole file, which is the only instrument that sees both halves of one
 * change at once, plus the FOUR sibling classes this run must leave exactly as it found them.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class PullUpConstructorBodyParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 29 pins the generated constructor, the preserved default, the super call, "
        + "and the four siblings it left alone")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "hierarchy", "pull_up_constructor_body",
            (service, cache) -> new HierarchyTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("direction", "pull_up_constructor_body");
                args.put("symbol",
                    "com.example.ConstructorBodyTargets.Contractor#Contractor");
                return args;
            },
            List.of("ConstructorBodyTargets.java"));
    }
}
