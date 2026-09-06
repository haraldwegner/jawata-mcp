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
 * Sprint 28d-rescue row 59 — Replace Type Code with Subclasses: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves and what it does not. This row
 * GENERATES SOURCE, and generated source is the case a golden is worth most on: every assertion
 * a unit test can write about it is a {@code contains}, and a {@code contains} cannot see
 * indentation, member order, a missing javadoc, or a stray blank line. Stage 4's row 47 shipped
 * twelve green tests over output that was visibly wrong for exactly that reason, and only
 * reading the recorded golden found it.</p>
 *
 * <p>All three generated files are pinned, plus the base class — which this row must leave
 * byte-for-byte alone, and which no assertion about the new files could tell you.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class ReplaceTypeCodeWithSubclassesParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 59 pins all three generated subclasses and the base class it left alone")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "hierarchy", "replace_type_code_with_subclasses",
            (service, cache) -> new HierarchyTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("direction", "replace_type_code_with_subclasses");
                args.put("typeName", "com.example.Enrolment");
                return args;
            },
            List.of("Enrolment.java", "EnrolmentProvisional.java", "EnrolmentConfirmed.java",
                "EnrolmentSeniorTutor.java"));
    }
}
