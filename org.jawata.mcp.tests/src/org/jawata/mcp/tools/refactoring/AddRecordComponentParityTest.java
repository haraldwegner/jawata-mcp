package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.tools.DataTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

/**
 * mcp#63 — {@code data kind=add_record_component}: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves and what it does not. This is
 * a NEW operation, so a golden recorded from it is a snapshot of itself: it pins the exact
 * outcome and fails the day it changes for any reason. Behaviour preservation lives in the
 * compile gate and in {@link AddRecordComponentToolTest}'s four refusals.</p>
 *
 * <p><b>What the golden is uniquely good for here is the TWO-FILE outcome.</b> The unit test
 * asserts a handful of substrings — the header, and each rewritten construction. The golden
 * pins BOTH files whole, so a change that kept every asserted substring and altered anything
 * else — the spacing of the widened header, an argument list the operation should not have
 * touched, a construction of a DIFFERENT record in the same file — moves it.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class AddRecordComponentParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("mcp#63 pins the widened header and every migrated construction, in BOTH files")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "data", "add_record_component",
            (service, cache) -> new DataTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "add_record_component");
                // The NAME form, deliberately. A record is addressed by what it is called,
                // and this is the form a caller reading the change_method_signature refusal
                // will have in hand — that refusal names the type, not a position.
                args.put("typeName", "com.example.RecordComponentTargets.Reading");
                args.put("componentType", "long");
                args.put("componentName", "takenAt");
                args.put("defaultValue", "0L");
                return args;
            },
            List.of("RecordComponentTargets.java", "RecordComponentDesk.java"));
    }
}
