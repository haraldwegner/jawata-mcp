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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d — the {@code ocp} kind: Open/Closed as a NAME over measurements
 * that already existed.
 *
 * <p>This kind is an aggregation and the tests are written to prove exactly
 * that, not more: that the two source traces of a closed-for-extension design
 * ({@code switch_statements}, {@code type_code}) reach a sweep under the
 * principle's own name, carrying the cure. It adds no analysis, so there is no
 * new analysis to characterise — asserting more here would be asserting the two
 * trace detectors' rules a second time, in a file that does not own them.</p>
 *
 * <p>PROOF OF LIFE BEFORE ZERO all the same, and {@code filesExamined} before
 * any number: an aggregation that aggregates nothing looks exactly like clean
 * code. Both fixtures are pre-existing committed files, so the numbers below are
 * read off code this sprint did not write for the purpose.</p>
 */
class OcpDetectorTest {

    /** One int switch with 3 cases (`onCode`); no constant groups. Expect 1. */
    private static final String SWITCH_TRACE = "src/main/java/com/example/SwitchTypeCodeTargets.java";
    /** One STATUS_* group of 3 int constants (`Order`); no switches. Expect 1. */
    private static final String TYPE_CODE_TRACE = "src/main/java/com/example/TypeCodeTargets.java";
    /** Neither trace: no switch, no static-final constants. Expect 0. */
    private static final String CLEAN = "src/main/java/com/example/GreetFormal.java";

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
        args.put("kind", "ocp");
        args.put("filePath", filePath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "ocp must dispatch; got: " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(1, ((Number) data.get("filesExamined")).intValue(),
            () -> "the scan must have examined " + filePath + "; response: " + data);
        return (List<Map<String, Object>>) data.get("findings");
    }

    // ------------------------------------------------------------ proof of life

    @Test
    @DisplayName("PROOF OF LIFE: both traces surface under `ocp` — 1 finding from each fixture")
    void carriesBothTraces() {
        List<Map<String, Object>> fromSwitch = findingsIn(SWITCH_TRACE);
        assertEquals(1, fromSwitch.size(),
            () -> "SwitchTypeCodeTargets has one 3-case int switch (onCode); the enum switch and "
                + "the 2-case switch are below the rule. Got: " + fromSwitch);
        assertEquals("onCode", String.valueOf(fromSwitch.get(0).get("symbol")));
        assertTrue(String.valueOf(fromSwitch.get(0).get("message")).contains("[trace: switch_statements]"),
            () -> "a finding must name the measurement it rests on: " + fromSwitch.get(0));

        List<Map<String, Object>> fromTypeCode = findingsIn(TYPE_CODE_TRACE);
        assertEquals(1, fromTypeCode.size(),
            () -> "TypeCodeTargets has one STATUS_* group of 3 (Order); NoCodes' two unrelated "
                + "constants are not a group. Got: " + fromTypeCode);
        assertEquals("Order", String.valueOf(fromTypeCode.get(0).get("symbol")));
        assertTrue(String.valueOf(fromTypeCode.get(0).get("message")).contains("[trace: type_code]"),
            () -> "a finding must name the measurement it rests on: " + fromTypeCode.get(0));
    }

    @Test
    @DisplayName("every finding states the principle and carries a runnable cure recipe")
    void everyFindingCarriesThePrincipleAndTheCure() {
        for (String file : List.of(SWITCH_TRACE, TYPE_CODE_TRACE)) {
            for (Map<String, Object> finding : findingsIn(file)) {
                String message = String.valueOf(finding.get("message"));
                assertTrue(message.startsWith("Open/Closed: this code must be MODIFIED to extend it."),
                    () -> "the finding must say what the principle is about: " + message);
                assertTrue(message.contains("OCP cure: introduce an abstraction at the modification axis"),
                    () -> "the finding must carry the cure: " + message);
                assertTrue(message.contains("refactor_to_pattern kind="),
                    () -> "the cure must name a runnable recipe kind: " + message);
            }
        }
        // The recipes are RecipeCatalog's, not this detector's — proving the
        // aggregation reuses the existing map rather than re-deciding cures.
        assertTrue(String.valueOf(findingsIn(TYPE_CODE_TRACE).get(0).get("message"))
                .contains("replace_type_code_with_class"),
            "type_code's cure recipe is replace_type_code_with_class");
        assertTrue(String.valueOf(findingsIn(SWITCH_TRACE).get(0).get("message"))
                .contains("refactor_to_state"),
            "switch_statements' cure recipe is refactor_to_state");
    }

    // ------------------------------------------------------------- the zero

    @Test
    @DisplayName("ZERO on a class with neither trace")
    void staysSilentWithoutATrace() {
        // Only meaningful because carriesBothTraces() shows the same kind, on the
        // same service, reporting one from each trace.
        assertEquals(1, findingsIn(SWITCH_TRACE).size(),
            "proof of life must hold before the zero counts");
        assertEquals(List.of(), findingsIn(CLEAN),
            "GreetFormal has no switch and no static-final constants");
    }

    // ------------------------------------------------------------- registration

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("registered: `ocp` is in find_quality_issue's kind enum")
    void registeredAsAKind() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> kind = (Map<String, Object>) properties.get("kind");
        List<String> kinds = (List<String>) kind.get("enum");
        assertTrue(kinds.contains("ocp"), () -> "kind enum must carry ocp; got: " + kinds);
        // The count lives in PrincipleDetectorKindsTest — the roster's one home.
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a fowler family sweep reaches `ocp` — the gap this kind exists to close")
    void answersAFamilySweep() {
        // This is the whole point of the kind: before it, a reader sweeping a
        // family for Open/Closed found no such name and concluded the principle
        // was unmeasured, while the two traces sat in the same sweep.
        ObjectNode args = mapper.createObjectNode();
        args.put("family", "fowler");
        args.put("limit", 2000);
        ToolResponse r = org.jawata.mcp.fixtures.Sweeps.run(tool, args);
        assertTrue(r.isSuccess(), () -> "fowler sweep must succeed; got: " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertTrue(((List<String>) data.get("kinds")).contains("ocp"),
            () -> "the fowler family must list ocp; got: " + data.get("kinds"));
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        long fromOcp = findings.stream().filter(f -> "ocp".equals(f.get("kind"))).count();
        assertTrue(fromOcp >= 2,
            () -> "the sweep must carry the ocp findings themselves — at minimum the two the "
                + "per-file assertions above derive by hand; got " + fromOcp + " of "
                + findings.size() + " findings total");
    }
}
