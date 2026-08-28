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
 * Sprint 28d — the {@code composition_over_inheritance} detector.
 *
 * <p>PROOF OF LIFE BEFORE ZERO, and {@code filesExamined} before any number at
 * all. A detector that never fires and a scan that read nothing both produce
 * exactly what clean code produces, so {@link #findingsIn} refuses to hand back
 * a list until the scan is shown to have opened the file, and the silent cases
 * are written to be read only after {@link #firesOnBothArmsOfTheRule} has shown
 * the same detector, on the same service, reporting two.</p>
 *
 * <p>The {@code inheritance} fixture exists for this: {@code Ledger} bequeaths
 * six inheritable members, and every expected number below is a fraction of that
 * six (see the fixture's pom for the full derivation).</p>
 */
class CompositionOverInheritanceDetectorTest {

    private static final String DIRTY = "src/main/java/com/example/InheritanceTargets.java";
    private static final String CLEAN = "src/main/java/com/example/InheritanceCleanTargets.java";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("inheritance");
        tool = new FindQualityIssueTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> findingsIn(String filePath) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "composition_over_inheritance");
        args.put("filePath", filePath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            () -> "composition_over_inheritance must dispatch; got: " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        // An unexamined file reports the same empty list as a clean one.
        assertEquals(1, ((Number) data.get("filesExamined")).intValue(),
            () -> "the scan must have examined " + filePath + "; response: " + data);
        return (List<Map<String, Object>>) data.get("findings");
    }

    private Set<String> symbolsIn(String filePath) {
        return findingsIn(filePath).stream()
            .map(f -> String.valueOf(f.get("symbol")))
            .collect(Collectors.toSet());
    }

    // ------------------------------------------------------------ proof of life

    @Test
    @DisplayName("PROOF OF LIFE: fires non-zero on the deliberate fixture — 2 findings, one per arm")
    void firesOnBothArmsOfTheRule() {
        List<Map<String, Object>> findings = findingsIn(DIRTY);
        assertEquals(2, findings.size(),
            () -> "InheritanceTargets must produce exactly 2 findings; got: " + findings);
        Set<String> hits = findings.stream()
            .map(f -> String.valueOf(f.get("symbol"))).collect(Collectors.toSet());
        assertTrue(hits.contains("ReportingLedger"),
            () -> "a subclass overriding NOTHING of a concrete parent must fire: " + hits);
        assertTrue(hits.contains("AuditLedger"),
            () -> "a subclass touching 1 of 6 inherited members (16%) must fire: " + hits);
        assertFalse(hits.contains("FullLedger"),
            () -> "a subclass overriding 3 and touching all 6 must NOT fire: " + hits);
        assertFalse(hits.contains("Ledger"),
            () -> "the parent itself extends nothing and must NOT fire: " + hits);
    }

    @Test
    @DisplayName("each arm names its own evidence, so a reader can tell which one fired")
    void eachArmExplainsItself() {
        Map<String, String> bySymbol = findingsIn(DIRTY).stream().collect(Collectors.toMap(
            f -> String.valueOf(f.get("symbol")), f -> String.valueOf(f.get("message"))));

        String noOverrides = bySymbol.get("ReportingLedger");
        assertTrue(noOverrides.contains("overrides NONE"),
            () -> "the no-override arm must say so: " + noOverrides);
        assertFalse(noOverrides.contains("below the"),
            () -> "ReportingLedger touches 33% of the surface, above the 25% threshold, so the "
                + "percentage arm must NOT be claimed for it: " + noOverrides);

        String barelyTouched = bySymbol.get("AuditLedger");
        assertTrue(barelyTouched.contains("1 of 6 inherited member(s)"),
            () -> "the percentage arm must report the fraction it measured: " + barelyTouched);

        for (String message : bySymbol.values()) {
            assertTrue(message.contains("Replace Inheritance with Delegation"),
                () -> "every finding must carry the pointed refactoring: " + message);
        }
    }

    // ------------------------------------------------------------- the zero

    @Test
    @DisplayName("ZERO on the exclusions fixture — every documented exclusion holds")
    void staysSilentOnTheExcludedShapes() {
        // Only meaningful because firesOnBothArmsOfTheRule() shows the same
        // detector, on the same service, reporting 2.
        assertEquals(2, findingsIn(DIRTY).size(), "proof of life must hold before the zero counts");
        Set<String> hits = symbolsIn(CLEAN);
        assertTrue(hits.isEmpty(), () -> "clean fixture must produce ZERO findings; got: " + hits);
    }

    @Test
    @DisplayName("each exclusion is individually silent (named, so a regression says which)")
    void eachExclusionIsSilent() {
        // Every class named here is a NEAR MISS: shaped so it would fire if its
        // exclusion were removed. See InheritanceCleanTargets for each derivation.
        Set<String> hits = symbolsIn(CLEAN);
        assertFalse(hits.contains("Dot"), "an ABSTRACT superclass must be excluded");
        assertFalse(hits.contains("MissingAccount"), "a Throwable hierarchy must be excluded");
        assertFalse(hits.contains("Names"), "a superclass outside the source must be excluded");
        assertFalse(hits.contains("ReadOnlyLedger"),
            "a class that refuses a bequest belongs to refused_bequest, not here");
        assertFalse(hits.contains("Shape"), "a class extending nothing must be excluded");
    }

    @Test
    @DisplayName("the class refused_bequest owns is still reported BY refused_bequest")
    @SuppressWarnings("unchecked")
    void theRefusedBequestClassIsNotLostBetweenTheTwoKinds() {
        // The exclusion hands ReadOnlyLedger over; it must land somewhere, or
        // "left to refused_bequest" would be a way of losing it silently.
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "refused_bequest");
        args.put("filePath", CLEAN);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "refused_bequest must dispatch; got: " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(1, ((Number) data.get("filesExamined")).intValue(),
            () -> "the scan must have examined " + CLEAN + "; response: " + data);
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        assertEquals(1, findings.size(),
            () -> "ReadOnlyLedger.credit is the one refused bequest in this file; got: " + findings);
        assertEquals("credit", String.valueOf(findings.get(0).get("symbol")));
    }

    // ------------------------------------------------------------- registration

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("registered: `composition_over_inheritance` is in find_quality_issue's kind enum")
    void registeredAsAKind() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> kind = (Map<String, Object>) properties.get("kind");
        List<String> kinds = (List<String>) kind.get("enum");
        assertTrue(kinds.contains("composition_over_inheritance"),
            () -> "kind enum must carry composition_over_inheritance; got: " + kinds);
        // The COUNT lives in PrincipleDetectorKindsTest, which is the roster's
        // single home. Asserting it here too would make every future detector
        // turn this unrelated test red — the shotgun surgery this sprint cures.
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
        assertTrue(((List<String>) data.get("kinds")).contains("composition_over_inheritance"),
            () -> "the fowler family must list the kind; got: " + data.get("kinds"));
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        long mine = findings.stream()
            .filter(f -> "composition_over_inheritance".equals(f.get("kind"))).count();
        assertEquals(2, mine,
            () -> "the sweep must carry the findings themselves, not just the kind name; got "
                + findings.size() + " findings total");
    }
}
