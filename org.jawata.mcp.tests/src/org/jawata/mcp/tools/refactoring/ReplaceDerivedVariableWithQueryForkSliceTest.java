package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.DataTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROW 45 (Replace Derived Variable with Query) against code we did not author.
 *
 * <p>Upstream's {@code DefaultCircuitBreaker.lastFailureTime} is written in THREE places with
 * THREE different expressions — {@code System.nanoTime() + futureTime},
 * {@code System.nanoTime()} and {@code System.nanoTime() - retryTimePeriod}. It is a field
 * that looks derived and is not derived from one rule, which is precisely what this row's
 * agreement check exists to tell apart, and the refusal quotes all three so a reader can see
 * the disagreement rather than take it on trust.</p>
 *
 * <h2>A CORRECTION, kept because the mistake is the point</h2>
 *
 * <p>This file first claimed the field demonstrated the IMPURITY refusal — that it "passes
 * every other condition" and is declined only for the clock. That was false, and it was false
 * because I read two of the three writes and stated a property of all three. The impurity rule
 * is real and stays; what is not real is the evidence originally cited for it. It is proved by
 * {@code DerivedVariableTargets.Session} in the unit tests, and by nothing here.</p>
 *
 * <p>The mistake surfaced only because a MUTATION did not go red: disabling the impurity check
 * left this test green, since the message it asserted on ("nanoTime") is printed by the
 * disagreement refusal too. A substring shared by two refusals is evidence that SOMETHING
 * declined, never that the right thing did.</p>
 *
 * <p><b>NO FORK DEMONSTRATION of the success path, and it is a measured absence.</b> The
 * corpus was scanned for a field assigned the same field-only, call-free expression by every
 * writer; there is none. Unsurprising in a teaching repository — a stale derived field is a
 * maintenance artefact and these modules are written once — but stated as what it is rather
 * than left as a gap that reads like an oversight.</p>
 */
class ReplaceDerivedVariableWithQueryForkSliceTest {

    private static final String PKG = "src/main/java/com/iluwatar/circuitbreaker";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DataTool tool;
    private Path breaker;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("fork-circuit-breaker");
        tool = new DataTool(() -> service, new org.jawata.mcp.refactoring.RefactoringChangeCache());
        breaker = service.getProjectRoot().resolve(PKG).resolve("DefaultCircuitBreaker.java");
    }

    @Test
    @DisplayName("upstream's three-rule field is refused, with all three rules quoted")
    void refusesUpstreamsDisagreeingWriters() throws Exception {
        String before = Files.readString(breaker, StandardCharsets.UTF_8);
        String[] lines = before.split("\n", -1);
        int line = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("long lastFailureTime;")) {
                line = i;
                break;
            }
        }
        assertTrue(line >= 0, "the vendored slice no longer declares lastFailureTime");

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "replace_derived_variable");
        args.put("filePath", breaker.toString());
        args.put("line", line);
        args.put("column", lines[line].indexOf("lastFailureTime"));

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "three writers with three rules is not one derivation");
        String error = String.valueOf(r.getError());
        assertTrue(error.contains("do not agree"),
            "the refusal must be the AGREEMENT one: " + error);
        // ALL THREE, because quoting is the half that saves the reader the work. A refusal
        // that said only "they disagree" would leave them to go and find the writers, which
        // is exactly what the tool just did.
        assertTrue(error.contains("System.nanoTime() + futureTime")
                && error.contains("System.nanoTime() - retryTimePeriod"),
            "and it must quote the rules it found, not merely report that they differ: "
                + error);
        assertTrue(before.equals(Files.readString(breaker, StandardCharsets.UTF_8)),
            "a refusal must leave upstream's file byte-for-byte untouched");
    }
}
