package org.jawata.mcp.tools.smell;

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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue — Mutable Data, against com.example.MutableDataTargets.
 *
 * <p>The silence half of this test carries the weight. The cure this detector
 * recommends is to return a read-only view or a copy, so a version that also reported
 * those would be telling a reader to make a change that produces the same finding — and
 * would be turned off the first afternoon someone acted on it.</p>
 */
class MutableDataDetectorTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindQualityIssueTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Set<String> symbols() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "mutable_data");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "mutable_data must dispatch — refused with: "
            + (r.getError() != null ? r.getError().getCode() + " / " + r.getError().getMessage()
                                    : "(no error info)"));
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        return findings.stream().map(f -> String.valueOf(f.get("symbol")))
            .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("an accessor that hands out the field itself is reported, by name and through this")
    void reportsTheBareReturnOfAMutableField() {
        Set<String> hits = symbols();
        assertTrue(hits.contains("com.example.MutableDataTargets#getItems"),
            "returning the list itself hands every caller the object's state: " + hits);
        assertTrue(hits.contains("com.example.MutableDataTargets#getSlots"),
            "and `return this.slots` is the same shape written the other way: " + hits);
    }

    @Test
    @DisplayName("every safe wrapping is silent — they are the cure, not the smell")
    void doesNotReportWrappedOrCopiedReturns() {
        Set<String> hits = symbols();
        assertFalse(hits.contains("com.example.MutableDataTargets#viewItems"),
            "an unmodifiable view is exactly what this detector tells people to write: " + hits);
        assertFalse(hits.contains("com.example.MutableDataTargets#copyItems"),
            "a defensive copy leaves the original alone: " + hits);
        assertFalse(hits.contains("com.example.MutableDataTargets#copySlots"),
            "and so does an array copy: " + hits);
        assertFalse(hits.contains("com.example.MutableDataTargets#getLabel"),
            "a String cannot be changed by the caller, so handing it back leaks nothing: "
                + hits);
        assertFalse(hits.contains("com.example.MutableDataTargets#rawItems"),
            "a private accessor does not leak outside the class: " + hits);
    }

    @Test
    @DisplayName("it does not restate what global_data already says about static state")
    void staticStateBelongsToTheOtherKind() {
        Set<String> hits = symbols();
        assertFalse(hits.stream().anyMatch(s -> s.startsWith("GlobalDataTargets#")),
            "GlobalDataTargets has an accessor-free public static list, and global_data"
                + " reports it. Reporting the same state under a second name makes a"
                + " reader fix it once and meet it again: " + hits);
    }
}
