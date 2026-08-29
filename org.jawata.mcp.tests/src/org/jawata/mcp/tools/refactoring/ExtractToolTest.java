package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractTool;
import org.jawata.mcp.tools.ExtractClassTool;
import org.jawata.mcp.tools.ExtractConstantTool;
import org.jawata.mcp.tools.ExtractInterfaceTool;
import org.jawata.mcp.tools.ExtractMethodTool;
import org.jawata.mcp.tools.ExtractSuperclassTool;
import org.jawata.mcp.tools.ExtractTool;
import org.jawata.mcp.tools.ExtractVariableTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 16b/A — routing tests for the parametric {@code extract} front door.
 * Strategy: discriminator-stripped failure-parity. Minimal args make each
 * delegate fail validation on its own required param; the parametric output must
 * equal the narrow delegate's (with the {@code kind} discriminator removed),
 * which proves correct routing without applying any refactoring.
 */
class ExtractToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ExtractTool tool;
    private ObjectMapper mapper;
    private String calculatorPath;
    private Map<String, AbstractTool> narrowByKind;
    /** All SIX, for the schema-completeness guard. Kept apart from {@link #narrowByKind},
     *  which drives failure-parity routing and covers only the four range/interface kinds. */
    private Map<String, AbstractTool> allDelegates;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        tool = new ExtractTool(() -> service, cache);
        mapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
        narrowByKind = new LinkedHashMap<>();
        narrowByKind.put("method", new ExtractMethodTool(() -> service, cache));
        narrowByKind.put("variable", new ExtractVariableTool(() -> service, cache));
        narrowByKind.put("constant", new ExtractConstantTool(() -> service, cache));
        narrowByKind.put("interface", new ExtractInterfaceTool(() -> service, cache));

        allDelegates = new LinkedHashMap<>(narrowByKind);
        allDelegates.put("superclass", new ExtractSuperclassTool(() -> service, cache));
        allDelegates.put("class", new ExtractClassTool(() -> service, cache));
    }

    private ObjectNode minimal(String kind) {
        ObjectNode n = mapper.createObjectNode();
        if (kind != null) n.put("kind", kind);
        n.put("filePath", calculatorPath);
        return n;
    }

    @Test
    @DisplayName("schema lists all four kinds and requires kind")
    @SuppressWarnings("unchecked")
    void schema_lists_kinds() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        List<String> kinds = (List<String>) ((Map<String, Object>) props.get("kind")).get("enum");
        assertTrue(kinds.containsAll(List.of("method", "variable", "constant", "interface")));
        assertTrue(((List<String>) schema.get("required")).contains("kind"));
    }

    /**
     * THE GUARD FOR A DEFECT THAT SHIPPED GREEN — Stage 7, found by the architect seat.
     *
     * <p>{@code kind=class} was added to the enum and to dispatch, and its five
     * parameters never reached the published schema. {@code fields} is REQUIRED by the
     * delegate and was invisible to any client reading {@code tools/list}. The
     * operation ran correctly for anyone who already knew the argument names, so every
     * behavioural test passed: the front door was wired for EXECUTION and unwired for
     * CONTRACT, and no existing test compared the two lists. {@link #schema_lists_kinds()}
     * above checks the enum and never the properties, which is precisely the gap.</p>
     *
     * <p><b>Why this test cannot quietly under-cover.</b> It holds its own list of
     * delegates, and a second list is what caused the original defect — so the first
     * assertion is that this list matches the PUBLISHED enum exactly. Ship a seventh
     * kind without updating this test and it goes RED, rather than passing while
     * silently checking six of seven.</p>
     */
    @Test
    @DisplayName("every parameter a delegate declares is published in the front door's schema")
    @SuppressWarnings("unchecked")
    void schema_publishes_every_delegate_parameter() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        List<String> publishedKinds =
            (List<String>) ((Map<String, Object>) props.get("kind")).get("enum");

        assertEquals(publishedKinds.size(), allDelegates.size(),
            "this test's delegate list has drifted from the kinds the tool advertises: "
                + publishedKinds + " vs " + allDelegates.keySet() + ". Add the new kind here"
                + " — otherwise this guard passes while never looking at it");
        assertTrue(allDelegates.keySet().containsAll(publishedKinds),
            "every advertised kind must be represented here: " + publishedKinds);

        for (Map.Entry<String, AbstractTool> e : allDelegates.entrySet()) {
            Map<String, Object> declared =
                (Map<String, Object>) e.getValue().getInputSchema().get("properties");
            for (String param : declared.keySet()) {
                assertTrue(props.containsKey(param),
                    "kind=" + e.getKey() + " accepts '" + param + "' but the front door does"
                        + " not declare it. A parameter absent from the schema is invisible to"
                        + " every client reading tools/list, and the operation is undiscoverable"
                        + " however well it runs for someone who already knows the name");
            }
        }
    }

    @Test
    @DisplayName("every kind routes to its narrow delegate (failure parity)")
    void every_kind_routes() {
        for (Map.Entry<String, AbstractTool> e : narrowByKind.entrySet()) {
            String kind = e.getKey();
            ToolResponse viaParam = tool.execute(minimal(kind));
            ObjectNode narrowArgs = minimal(kind);
            narrowArgs.remove("kind");
            ToolResponse viaNarrow = e.getValue().execute(narrowArgs);
            assertEquals(viaNarrow.isSuccess(), viaParam.isSuccess(), "kind=" + kind + " success parity");
            JsonNode n = mapper.valueToTree(viaNarrow.isSuccess() ? viaNarrow.getData() : viaNarrow.getError());
            JsonNode p = mapper.valueToTree(viaParam.isSuccess() ? viaParam.getData() : viaParam.getError());
            assertEquals(n, p, "kind=" + kind + " payload parity (correct delegate)");
        }
    }

    @Test
    @DisplayName("missing kind returns INVALID_PARAMETER")
    void missing_kind_invalid() {
        assertFalse(tool.execute(minimal(null)).isSuccess());
    }

    @Test
    @DisplayName("unknown kind returns INVALID_PARAMETER")
    void unknown_kind_invalid() {
        assertFalse(tool.execute(minimal("typo")).isSuccess());
    }
}
