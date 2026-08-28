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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d — Command Query Separation detector.
 *
 * <p>PROOF OF LIFE FIRST, on purpose. A brand-new detector reporting zero on
 * clean code and a detector that never fires at all produce byte-identical
 * output, so the zero over {@code CqsCleanTargets} is only evidence <em>after</em>
 * the same detector has been shown firing non-zero over {@code CqsTargets}.
 * The two assertions are deliberately kept in one class, run against one
 * service, so neither can be read without the other.</p>
 */
class CqsDetectorTest {

    private static final String DIRTY = "src/main/java/com/example/CqsTargets.java";
    private static final String CLEAN = "src/main/java/com/example/CqsCleanTargets.java";

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
    private List<Map<String, Object>> findingsIn(String filePath) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "cqs");
        args.put("filePath", filePath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "cqs must dispatch; got: " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        // The scan must have actually READ the file — an unexamined file reports
        // the same empty list as a clean one. filesExamined is flattened into the
        // response by Findings.toResponse, not nested under a "scan" key.
        assertEquals(1, ((Number) data.get("filesExamined")).intValue(),
            () -> "the scan must have examined " + filePath + "; response: " + data);
        return (List<Map<String, Object>>) data.get("findings");
    }

    private Set<String> symbolsIn(String filePath) {
        return findingsIn(filePath).stream()
            .map(f -> String.valueOf(f.get("symbol")))
            .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("PROOF OF LIFE: fires non-zero on the deliberate fixture — 3 findings")
    void firesOnCommandsThatAlsoAnswer() {
        List<Map<String, Object>> findings = findingsIn(DIRTY);
        assertEquals(3, findings.size(),
            () -> "CqsTargets must produce exactly 3 findings; got: " + findings);
        Set<String> hits = findings.stream()
            .map(f -> String.valueOf(f.get("symbol"))).collect(Collectors.toSet());
        assertTrue(hits.contains("withdraw"), () -> "assign-then-answer must fire: " + hits);
        assertTrue(hits.contains("register"), () -> "two writes then answer must fire: " + hits);
        assertTrue(hits.contains("tick"), () -> "post-increment then answer must fire: " + hits);
        assertFalse(hits.contains("lastName"), () -> "a pure query must NOT fire: " + hits);
    }

    @Test
    @DisplayName("ZERO on clean code — every documented exclusion holds")
    void staysSilentOnTheLegitimateShapes() {
        // Only meaningful because firesOnCommandsThatAlsoAnswer() shows the same
        // detector, on the same service, reporting 3.
        assertEquals(3, findingsIn(DIRTY).size(), "proof of life must hold before the zero counts");
        Set<String> hits = symbolsIn(CLEAN);
        assertTrue(hits.isEmpty(), () -> "clean fixture must produce ZERO findings; got: " + hits);
    }

    @Test
    @DisplayName("each exclusion is individually silent (named, so a regression says which)")
    void eachExclusionIsSilent() {
        Set<String> hits = symbolsIn(CLEAN);
        assertFalse(hits.contains("label"), "fluent `return this` must be excluded");
        assertFalse(hits.contains("getAndBump"), "previous-value protocol must be excluded");
        assertFalse(hits.contains("greeting"), "lazy initialisation must be excluded");
        assertFalse(hits.contains("deferrer"), "a write deferred into a lambda must be excluded");
        assertFalse(hits.contains("next"), "an imposed supertype signature must be excluded");
        assertFalse(hits.contains("count"), "a pure query must be excluded");
        assertFalse(hits.contains("bump"), "a pure command must be excluded");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("registered: `cqs` is in find_quality_issue's kind enum and the fowler family")
    void registeredAsAKind() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> kind = (Map<String, Object>) properties.get("kind");
        List<String> kinds = (List<String>) kind.get("enum");
        assertTrue(kinds.contains("cqs"), () -> "kind enum must carry cqs; got: " + kinds);
        // THE COUNT DOES NOT LIVE HERE. It used to: this test asserted the enum
        // size, so every new principle detector made an unrelated detector's
        // test red and had to edit this literal — the shotgun-surgery shape this
        // sprint exists to remove, in our own tests. The roster has one home now
        // (PrincipleDetectorKindsTest); this test asserts only its own kind.
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a fowler family sweep reaches the new kind through the front door")
    void answersAFamilySweep() {
        ObjectNode args = mapper.createObjectNode();
        args.put("family", "fowler");
        args.put("limit", 2000);
        ToolResponse r = org.jawata.mcp.fixtures.Sweeps.run(tool, args);
        assertTrue(r.isSuccess(), () -> "fowler sweep must succeed; got: " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertTrue(((List<String>) data.get("kinds")).contains("cqs"),
            () -> "the fowler family must list cqs; got: " + data.get("kinds"));
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        long fromCqs = findings.stream().filter(f -> "cqs".equals(f.get("kind"))).count();
        assertTrue(fromCqs > 0,
            () -> "the sweep must actually carry cqs findings, not just the kind name; got "
                + findings.size() + " findings total");
    }
}
