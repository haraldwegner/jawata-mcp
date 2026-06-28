package org.goja.mcp.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.IJdtService;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.FindQualityIssueTool;
import org.goja.mcp.tools.QualityDetectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 16b/D — the projection invariant: the Smell front door's kind enum and
 * dispatch are a projection of the {@link DetectorCatalog}. A detector registered
 * in the catalog appears in {@code find_quality_issue}'s schema AND dispatches,
 * with NO edit to the tool. This is exactly what Sprint 17 (Fowler) / 20 (SOLID)
 * rely on: add a kind, not a tool.
 */
class FindQualityIssueProjectionTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
    }

    @SuppressWarnings("unchecked")
    private List<String> kindEnum(FindQualityIssueTool tool) {
        Map<String, Object> props = (Map<String, Object>) tool.getInputSchema().get("properties");
        Map<String, Object> kind = (Map<String, Object>) props.get("kind");
        return (List<String>) kind.get("enum");
    }

    @Test
    @DisplayName("built-in catalog projects the eight quality kinds")
    void builtins_project_eight_kinds() {
        FindQualityIssueTool tool = new FindQualityIssueTool(() -> service);
        assertTrue(kindEnum(tool).containsAll(List.of(
            "naming", "bugs", "unused", "large_classes",
            "circular_deps", "reflection", "throws", "catches")));
    }

    @Test
    @DisplayName("a newly registered detector is projected into the enum AND dispatched — no tool edit")
    void registered_detector_is_projected_and_dispatched() {
        DetectorCatalog catalog = QualityDetectors.builtins(() -> service);
        catalog.register(new Detector() {
            @Override public String kind() { return "demo_smell"; }
            @Override public String description() { return "test-only detector"; }
            @Override public ToolResponse detect(IJdtService s, JsonNode args) {
                return ToolResponse.success(Map.of("hit", true));
            }
        });
        FindQualityIssueTool tool = new FindQualityIssueTool(new GojaService(() -> service, catalog));

        // Projection on the SCHEMA side.
        assertTrue(kindEnum(tool).contains("demo_smell"), "new kind appears in the projected enum");

        // Projection on the DISPATCH side.
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "demo_smell");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "new kind dispatches to its detector");
        assertEquals(Boolean.TRUE, ((Map<?, ?>) r.getData()).get("hit"));
    }

    @Test
    @DisplayName("an unregistered kind is rejected")
    void unknown_kind_rejected() {
        FindQualityIssueTool tool = new FindQualityIssueTool(() -> service);
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "not_a_detector");
        assertFalse(tool.execute(args).isSuccess());
    }
}
