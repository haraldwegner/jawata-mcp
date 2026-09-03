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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue — Alternative Classes with Different Interfaces.
 *
 * <p>This is the detector whose whole risk is reporting too much, so its history is worth
 * keeping: the first version paired {@code JdtServiceImpl} with {@code DetectorCatalog}
 * on {@code Optional(String)}, {@code boolean(String)}, {@code Collection()} and
 * {@code int()} — the shapes every lookup-flavoured class has, in two classes that do
 * entirely different jobs. It was measured before it was believed, and the measurement
 * is what produced the domain-type condition.</p>
 *
 * <p>Then it reported ZERO on the product's own 827 files, which is either correct or a
 * detector tightened into silence — and those look identical from the outside. So the
 * fixture below is not decoration: it is the only thing that tells the two apart, and
 * this file asserts it FIRST for that reason.</p>
 */
class AlternativeClassesDetectorTest {

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
    private List<Map<String, Object>> findings() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "alternative_classes");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "alternative_classes must dispatch — refused with: "
            + (r.getError() != null ? r.getError().getCode() + " / " + r.getError().getMessage()
                                    : "(no error info)"));
        Map<String, Object> data = (Map<String, Object>) r.getData();
        return (List<Map<String, Object>>) data.get("findings");
    }

    private Set<String> symbols() {
        return findings().stream().map(f -> String.valueOf(f.get("symbol")))
            .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("PROOF OF LIFE: the deliberate pair is found, from both sides")
    void theDeliberatePairIsFound() {
        Set<String> hits = symbols();
        assertTrue(hits.contains("com.example.LedgerByName"),
            "the fixture holds two classes doing one job over one domain type with every"
                + " method named differently. If this is silent the detector is dead, and"
                + " a dead detector and a clean codebase produce the same answer: " + hits);
        assertTrue(hits.contains("com.example.RegisterOfCalculators"),
            "and it must be reported from BOTH sides — a reader looking at either class"
                + " needs to be told about the other: " + hits);
    }

    @Test
    @DisplayName("and nothing else in 76 classes is dragged in with it")
    void nothingElseIsReported() {
        assertEquals(Set.of("com.example.LedgerByName", "com.example.RegisterOfCalculators"),
            symbols(),
            "this fixture has one deliberate pair. Anything else here is a shape"
                + " coincidence, which is the failure that makes this kind of detector"
                + " get switched off — the first version paired two unrelated lookup"
                + " classes on Optional(String) and int().");
    }

    @Test
    @DisplayName("a shape of nothing but JDK types cannot carry a pair")
    void jdkOnlyShapesDoNotCount() {
        // The condition stated as behaviour rather than as a rule: the fixture's own
        // classes share plenty of `String getX()` and `int size()` shapes between them,
        // and none of those pairings appears above. If the domain-type condition were
        // removed, this fixture alone would produce several.
        assertEquals(2, findings().size(),
            "two findings — one pair, both sides. More means shapes made only of java.*"
                + " types are being counted again: " + findings());
    }
}
