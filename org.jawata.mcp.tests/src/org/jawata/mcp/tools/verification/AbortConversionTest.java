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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mutation report's third signal (sprint spec, D41: "a mutation that turns a failure into an ABORT
 * -> a gate is swallowing the defect ... NOT the same signal as a survivor"). PIT counts a test that
 * aborts (a failed assumption) as a pass, so a mutant that makes the only guarding test abort arrives
 * as a SURVIVOR; the engine runs PIT with a plugin that reports it as its own kind of kill.
 */
class AbortConversionTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RunTestsTool tool;
    private ObjectMapper om;
    private String covDirBefore;

    @BeforeEach
    void setUp() throws Exception {
        covDirBefore = System.getProperty("jawata.coverage.dir");
        System.setProperty("jawata.coverage.dir",
            Files.createTempDirectory("jawata-cov-store-").toString());
        JdtServiceImpl service = helper.loadProjectCopy("abort-target");
        tool = new RunTestsTool(() -> service, new org.jawata.mcp.java.JavaProvider(() -> service)::testRuntime);
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
    @DisplayName("a mutation that turns the guarding test's failure into an abort is reported as abortConverted, not as a survivor")
    void anAbortIsItsOwnSignalAndNotASurvivor() {
        Map<String, Object> data = mutation("com.example.abort.Swallowed", "com.example.abort.SwallowedTest");
        Map<String, Object> summary = map(data.get("summary"));

        List<Map<String, Object>> converted = rows(data.get("abortConversions"));
        Map<String, Object> row = converted.stream()
            .filter(r -> "com.example.abort.Swallowed#plus".equals(String.valueOf(r.get("symbol"))))
            .findFirst().orElse(null);
        assertNotNull(row, "the abort must map to Swallowed#plus exactly; got: " + data);
        assertTrue(String.valueOf(row.get("abortedTests")).contains("SwallowedTest"),
            "the row names the test that aborted instead of failing; got: " + row);
        assertTrue(((Number) summary.get("abortConverted")).intValue() >= 1, "got: " + summary);

        assertTrue(rows(data.get("survivors")).stream()
                .noneMatch(s -> String.valueOf(s.get("symbol")).equals("com.example.abort.Swallowed#plus")),
            "an abort is NOT a survivor; got survivors: " + data.get("survivors"));
        assertEquals(((Number) summary.get("survived")).intValue()
                + ((Number) summary.get("abortConverted")).intValue(),
            ((Number) data.get("errorCount")).intValue(),
            "a gate swallowing a defect blocks like a survivor does; got: " + data);
    }

    @Test
    @DisplayName("a test that FAILS under the mutation kills it and reports no abort")
    void aRealKillIsNotAnAbort() {
        Map<String, Object> data = mutation("com.example.abort.Solid", "com.example.abort.SolidTest");
        Map<String, Object> summary = map(data.get("summary"));
        assertTrue(((Number) summary.get("killed")).intValue() >= 1, "got: " + data);
        assertEquals(0, ((Number) summary.get("abortConverted")).intValue(), "got: " + data);
        assertTrue(rows(data.get("abortConversions")).isEmpty(), "got: " + data);
    }

    @Test
    @DisplayName("a test that aborts on EVERY run, mutated or not, is no conversion and does not stop the run")
    void anAbortThatNeverDependedOnTheMutationIsNotReported() {
        Map<String, Object> data = mutation("com.example.abort.Guarded", "com.example.abort.GuardedTest");
        assertEquals(null, data.get("state"), "the run must produce a result; got: " + data);
        // The mutants WERE run against it (the test covers the target before it aborts) ...
        assertTrue(((Number) map(data.get("summary")).get("survived")).intValue() >= 1,
            "the target is covered, so its mutants run and survive (an abort is a pass to PIT); got: " + data);
        // ... and none of them is blamed on the abort, which was there before they were.
        assertFalse(rows(data.get("abortConversions")).stream()
                .anyMatch(r -> String.valueOf(r.get("symbol")).startsWith("com.example.abort.Guarded#")),
            "a test aborting before any mutation converted nothing; got: " + data);
    }

    private Map<String, Object> mutation(String targetClass, String targetTest) {
        ObjectNode args = om.createObjectNode();
        args.put("action", "coverage_mutation");
        args.putArray("targetClasses").add(targetClass);
        args.putArray("targetTests").add(targetTest);
        args.put("timeoutSeconds", 300);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "mutation run failed: " + r.getError());
        return map(r.getData());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object o) {
        return o == null ? List.of() : (List<Map<String, Object>>) o;
    }
}
