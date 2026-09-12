package org.jawata.mcp.tools.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindQualityIssueTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 22a P2-c — baseline / trend diffing on a family sweep: a first diff (no
 * baseline) reports every finding as new; after save, a re-run reports them all
 * unchanged.
 *
 * <p><b>DELIBERATELY NO {@code @Timeout}, and this is the note that says why
 * (2026-09-12).</b> This is one of the suite's longest-running classes, so it looks
 * exactly like a class that wants a declared budget. It already has three: its one
 * test drives {@code Sweeps.run} three times, and each call carries
 * {@code Sweeps.DEADLINE_MILLIS} — a 600 s hang backstop that fails the test NAMING
 * the sweep that never finished.</p>
 *
 * <p>A class-level {@code @Timeout} here would be worse than redundant. Priced
 * below 1800 s it would fire FIRST and replace that precise message with
 * "the test took too long"; priced above it, it would never fire at all. The budget
 * belongs at the layer that knows what it is waiting for, and that layer is
 * {@code Sweeps}.</p>
 *
 * <p>What this class does need is the runner's process-level backstop to sit above
 * its worst legitimate silence — 3 × 600 s, since no test event is emitted while a
 * sweep polls. {@code SpikeTestMain.DEFAULT_STALL_MILLIS} is derived from exactly
 * that number.</p>
 */
class QualityBaselineTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new FindQualityIssueTool(() -> service);
        mapper = new ObjectMapper();
    }

    private Map<String, Object> run(String baseline) {
        ObjectNode args = mapper.createObjectNode();
        args.put("family", "fowler");
        args.put("baseline", baseline);
        ToolResponse r = org.jawata.mcp.fixtures.Sweeps.run(tool, args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        return data;
    }

    @Test
    @DisplayName("diff→save→diff: first run is all new, after save all unchanged")
    void baselineRoundTrip() {
        // 1. First diff with no saved baseline — every finding is new.
        Map<String, Object> d1 = run("diff");
        int firstNew = ((Number) d1.get("newCount")).intValue();
        assertTrue(firstNew > 0, "the fowler sweep must report findings on simple-maven: " + d1);
        assertEquals(0, ((Number) d1.get("fixedCount")).intValue());
        assertEquals(0, ((Number) d1.get("unchangedCount")).intValue());

        // 2. Save the snapshot.
        Map<String, Object> d2 = run("save");
        assertEquals("saved", d2.get("baseline"));
        assertEquals(firstNew, ((Number) d2.get("baselineSize")).intValue());

        // 3. Diff again against the saved snapshot — no change, all unchanged.
        Map<String, Object> d3 = run("diff");
        assertEquals(0, ((Number) d3.get("newCount")).intValue(),
            "a re-diff with no source change reports nothing new: " + d3);
        assertEquals(firstNew, ((Number) d3.get("unchangedCount")).intValue(),
            "all prior findings are unchanged: " + d3);
    }
}
