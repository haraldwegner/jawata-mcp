package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.RunTestsTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 23 (D5, C10) — targeted PIT on the weak-test fixture: a surviving
 * mutation lands at the exact under-asserted method with a candidate
 * assertion location; the well-tested method KILLS its mutants; both runs
 * stay inside the declared bound.
 */
class CoverageMutationTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RunTestsTool tool;
    private ObjectMapper om;
    private String covDirBefore;

    @BeforeEach
    void setUp() throws Exception {
        covDirBefore = System.getProperty("jawata.coverage.dir");
        System.setProperty("jawata.coverage.dir",
            Files.createTempDirectory("jawata-cov-store-").toString());
        service = helper.loadProjectCopy("coverage-target");
        tool = new RunTestsTool(() -> service);
        om = new ObjectMapper();
    }

    @AfterEach
    void restore() {
        if (covDirBefore == null) {
            System.clearProperty("jawata.coverage.dir");
        } else {
            System.setProperty("jawata.coverage.dir", covDirBefore);
        }
    }

    @Test
    @DisplayName("C10: survivor at the under-asserted method; kills on the well-tested one; bounded")
    void mutation_survivorsAndKills_bounded() {
        int bound = 300;

        // Weak: WeakTest EXECUTES plus() but asserts nothing about it.
        Map<String, Object> weak = mutation("com.example.cov.Weak",
            List.of("com.example.cov.WeakTest"), bound);
        Map<String, Object> weakSummary = cast2(weak.get("summary"));
        assertTrue(((Number) weakSummary.get("survived")).intValue() >= 1,
            "the under-asserted method must have SURVIVING mutations; got: " + weak);
        List<Map<String, Object>> survivors = cast(weak.get("survivors"));
        Map<String, Object> survivor = survivors.stream()
            .filter(s -> String.valueOf(s.get("symbol")).equals("com.example.cov.Weak#plus"))
            .findFirst().orElse(null);
        assertNotNull(survivor, "the survivor must map to Weak#plus exactly; got: " + survivors);
        assertTrue(((Number) survivor.get("line")).intValue() > 0, "got: " + survivor);
        assertTrue(String.valueOf(survivor.get("candidateAssertion")).contains("Weak#plus"),
            "candidate assertion location must name the symbol; got: " + survivor);
        assertTrue(((Number) weak.get("timeMs")).longValue() < bound * 1000L,
            "within the declared bound; got: " + weak.get("timeMs") + " ms");

        // Well-tested: CoveredTest asserts alwaysCalled's value — mutants die.
        Map<String, Object> strong = mutation("com.example.cov.Covered",
            List.of("com.example.cov.CoveredTest"), bound);
        Map<String, Object> strongSummary = cast2(strong.get("summary"));
        assertTrue(((Number) strongSummary.get("killed")).intValue() >= 1,
            "the asserted method must KILL mutants; got: " + strong);
        assertTrue(cast(strong.get("survivors")).stream()
                .noneMatch(s -> String.valueOf(s.get("symbol")).contains("alwaysCalled")),
            "no survivor may hide in the value-asserted method; got: " + strong.get("survivors"));
        assertTrue(((Number) strong.get("timeMs")).longValue() < bound * 1000L,
            "within the declared bound; got: " + strong.get("timeMs") + " ms");
    }

    private Map<String, Object> mutation(String targetClass, List<String> targetTests, int bound) {
        ObjectNode args = om.createObjectNode();
        args.put("action", "coverage_mutation");
        args.putArray("targetClasses").add(targetClass);
        var tests = args.putArray("targetTests");
        targetTests.forEach(tests::add);
        args.put("timeoutSeconds", bound);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "mutation run failed: " + r.getError());
        Map<String, Object> data = cast2(r.getData());
        assertEquals(null, data.get("state"),
            "PIT must produce a result; got: " + data.get("note") + " stderr: "
                + data.get("stderrTail"));
        return data;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cast(Object o) {
        return (List<Map<String, Object>>) o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast2(Object o) {
        return (Map<String, Object>) o;
    }
}
