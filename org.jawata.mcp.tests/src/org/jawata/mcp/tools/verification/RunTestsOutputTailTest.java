package org.jawata.mcp.tools.verification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.jawata.mcp.execution.LogNoise;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.RunTestsTool;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * S19-20 (dogfood 2026-10-24): the run_tests answer for design-patterns' page-object carried a
 * stdoutTail of 80 lines of a library's DEBUG output, which hid the one INFO line that said what
 * went wrong. The tail leaves DEBUG and TRACE log lines out and says how many.
 */
class RunTestsOutputTailTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    // ---------------------------------------------------- through the real tool

    @Test
    @DisplayName("run_tests: the stdoutTail shows the INFO line and not the DEBUG and TRACE lines around it")
    void theTailShowsTheLineThatMatters() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("runner-module-relative-file");
        RunTestsTool tool = new RunTestsTool(() -> service, new org.jawata.mcp.java.JavaProvider(() -> service)::testRuntime);

        ObjectNode args = new ObjectMapper().createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "class");
        scope.put("typeName", "com.example.rel.NoisyOutputTest");
        args.put("framework", "junit5");
        args.put("timeoutSeconds", 120);
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        String tail = String.valueOf(data.get("stdoutTail"));
        assertTrue(tail.contains("statusCode=[404] file missing"), "the INFO line is missing: " + tail);
        assertFalse(tail.contains("DEBUG org.example.Library"), "DEBUG lines are still in the tail: " + tail);
        assertFalse(tail.contains("TRACE org.example.Library"), "TRACE lines are still in the tail: " + tail);
        assertTrue(tail.contains("80 DEBUG/TRACE lines left out"), "the tail does not say what it left out: " + tail);
    }

    @Test
    @DisplayName("run_tests: an INFO line followed by more DEBUG lines than the tail holds is still in the tail (S19-30)")
    void theInfoLineSurvivesMoreDebugLinesThanTheTailHolds() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("runner-module-relative-file");
        RunTestsTool tool = new RunTestsTool(() -> service, new org.jawata.mcp.java.JavaProvider(() -> service)::testRuntime);

        ObjectNode args = new ObjectMapper().createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "class");
        scope.put("typeName", "com.example.rel.LongNoisyOutputTest");
        args.put("framework", "junit5");
        args.put("timeoutSeconds", 120);
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        String tail = String.valueOf(data.get("stdoutTail"));
        // 150 DEBUG lines follow the INFO line: cutting to the last 100 lines first would leave
        // 100 DEBUG lines and no INFO line.
        assertTrue(tail.contains("statusCode=[500] backend gone"), "the INFO line was cut off the tail: " + tail);
        assertFalse(tail.contains("DEBUG org.example.Library"), "DEBUG lines are still in the tail: " + tail);
        assertTrue(tail.contains("150 DEBUG/TRACE lines left out"), "the tail does not say what it left out: " + tail);
    }

    @Test
    @DisplayName("run_tests: the last line of a run survives more than a million characters of DEBUG before it (S19-30b)")
    void theLastLineSurvivesMoreOutputThanTheCaptureHolds() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("runner-module-relative-file");
        RunTestsTool tool = new RunTestsTool(() -> service, new org.jawata.mcp.java.JavaProvider(() -> service)::testRuntime);

        ObjectNode args = new ObjectMapper().createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "class");
        scope.put("typeName", "com.example.rel.HugeNoisyOutputTest");
        args.put("framework", "junit5");
        args.put("timeoutSeconds", 120);
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        String tail = String.valueOf(data.get("stdoutTail"));
        // 16,000 DEBUG lines are about 1.7 million characters. The capture used to keep the FIRST million, so the ERROR
        // line printed last was never read, and the tail said it was truncated over lines from the start of the run.
        assertTrue(tail.contains("statusCode=[503] the gateway went away"), "the last line of the run was cut off the tail: " + tail);
        assertFalse(tail.contains("DEBUG org.example.Library"), "DEBUG lines are still in the tail: " + tail);
        assertTrue(tail.contains("16000 DEBUG/TRACE lines left out"), "the tail does not count every DEBUG line it left out: " + tail);
        assertFalse(tail.contains("truncated"), "nothing was cut from the front of the run's end: " + tail);
    }

    // ---------------------------------------------------------- the filter itself

    @Test
    @DisplayName("run_tests: a run that printed nothing but DEBUG lines shows them as they came: the tail is never emptied")
    void aRunOfNothingButDebugLinesShowsThem() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("runner-module-relative-file");
        RunTestsTool tool = new RunTestsTool(() -> service, new org.jawata.mcp.java.JavaProvider(() -> service)::testRuntime);

        ObjectNode args = new ObjectMapper().createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "class");
        scope.put("typeName", "com.example.rel.OnlyDebugOutputTest");
        args.put("framework", "junit5");
        args.put("timeoutSeconds", 120);
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        String tail = String.valueOf(data.get("stdoutTail"));
        assertTrue(tail.contains("DEBUG org.example.Library -- only line one") && tail.contains("DEBUG org.example.Library -- only line two"),
            "the DEBUG lines were emptied out of the tail: " + tail);
        assertFalse(tail.contains("left out"), "nothing else was printed, so nothing is set aside: " + tail);
    }

    // ---------------------------------------------------------- the line test itself

    @Test
    @DisplayName("DEBUG and TRACE lines in the common log formats are noise; everything else is not")
    void debugAndTraceLinesInTheCommonFormatsAreNoise() {
        for (String noise : new String[] {
            "12:04:11.942 [main] DEBUG org.example.A -- logback default",
            "[main] DEBUG org.example.A - slf4j simple",
            "2026-10-24 12:04:11,942 TRACE org.example.A - iso with a comma"}) {
            assertTrue(LogNoise.isNoise(noise), noise);
        }
        for (String kept : new String[] {
            "12:04:11.943 [main] INFO org.example.A -- the line that matters",
            "12:04:11.944 [main] WARN org.example.A -- a warning",
            "12:04:11.945 [main] ERROR org.example.A -- an error",
            "\tat org.example.A.run(A.java:1)",
            "hello from println",
            ""}) {
            assertFalse(LogNoise.isNoise(kept), kept);
        }
    }

    @Test
    @DisplayName("a line that ends in a carriage return, as a Windows JVM prints it, is noise like any other")
    void aDebugLineThatEndsInACarriageReturnIsNoise() {
        assertTrue(LogNoise.isNoise("12:04:11.942 [main] DEBUG org.example.A -- one\r"));
    }

    @Test
    @DisplayName("a line at a higher level that merely mentions DEBUG is kept")
    void aHigherLevelLineThatMentionsDebugIsKept() {
        assertFalse(LogNoise.isNoise("12:04:11.943 [main] INFO org.example.A -- the DEBUG switch is on"));
    }
}
