package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.mcp.execution.LogNoise;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.RunTestsTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ---------------------------------------------------------- the filter itself

    @Test
    @DisplayName("DEBUG and TRACE lines in the common log formats are left out and counted; everything else stays")
    void debugAndTraceLinesInTheCommonFormatsAreLeftOut() {
        String text = String.join("\n",
            "12:04:11.942 [main] DEBUG org.example.A -- logback default",
            "[main] DEBUG org.example.A - slf4j simple",
            "2026-10-24 12:04:11,942 TRACE org.example.A - iso with a comma",
            "12:04:11.943 [main] INFO org.example.A -- the line that matters",
            "12:04:11.944 [main] WARN org.example.A -- a warning",
            "12:04:11.945 [main] ERROR org.example.A -- an error",
            "\tat org.example.A.run(A.java:1)",
            "hello from println",
            "");

        String out = LogNoise.withoutDebug(text);

        assertTrue(out.contains("the line that matters") && out.contains("a warning") && out.contains("an error"));
        assertTrue(out.contains("\tat org.example.A.run(A.java:1)") && out.contains("hello from println"));
        assertFalse(out.contains("logback default") || out.contains("slf4j simple") || out.contains("iso with a comma"));
        assertTrue(out.contains("3 DEBUG/TRACE lines left out"), out);
    }

    @Test
    @DisplayName("a line at a higher level that merely mentions DEBUG is kept")
    void aHigherLevelLineThatMentionsDebugIsKept() {
        String text = "12:04:11.943 [main] INFO org.example.A -- the DEBUG switch is on\n";
        assertEquals(text, LogNoise.withoutDebug(text));
    }

    @Test
    @DisplayName("a text without DEBUG or TRACE lines is returned as it came")
    void aTextWithoutDebugLinesIsReturnedAsIs() {
        String text = "12:04:11.943 [main] INFO org.example.A -- one\nplain\n";
        assertEquals(text, LogNoise.withoutDebug(text));
    }

    @Test
    @DisplayName("a text of nothing but DEBUG lines is returned as it came: the tail is never emptied")
    void aTextOfNothingButDebugLinesIsReturnedAsIs() {
        String text = "12:04:11.942 [main] DEBUG org.example.A -- one\n12:04:11.943 [main] DEBUG org.example.A -- two\n";
        assertEquals(text, LogNoise.withoutDebug(text));
    }
}
