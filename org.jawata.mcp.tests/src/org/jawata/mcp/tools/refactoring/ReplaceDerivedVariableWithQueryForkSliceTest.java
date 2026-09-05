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
 * <p><b>This is a REFUSAL on foreign code, and it is the strongest evidence this row can
 * carry rather than a weaker substitute for a success.</b> Upstream's
 * {@code DefaultCircuitBreaker} writes {@code this.lastFailureTime = System.nanoTime() +
 * futureTime} in two places, textually identical, both reading only fields and a static
 * call. It passes every other condition this operation checks: two writers, one rule, no
 * locals, no parameters. Turning it into a query would compile, pass the compile gate, and
 * silently change what the class does — because the clock is read afresh on every access,
 * and a circuit breaker whose "last failure time" is always now never opens.</p>
 *
 * <p>Nothing in a fixture written by me would have produced that. The impurity condition was
 * added BECAUSE this file was read, and a version of the row without it would have shipped
 * with its own headline claim — that a query is indistinguishable from reading the field —
 * false in the first real case anyone pointed it at.</p>
 *
 * <p><b>NO FORK DEMONSTRATION of the success path, and it is a measured absence.</b> The
 * corpus was scanned for a field assigned the same field-only, call-free expression by every
 * writer; there is none. That is unsurprising in a teaching repository — a derived field
 * that has gone stale is a maintenance artefact, and these modules are written once to
 * illustrate a pattern — but it is stated as what it is rather than left as a gap that reads
 * like an oversight.</p>
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
    @DisplayName("upstream's clock-derived field is REFUSED — it would compile and be wrong")
    void refusesUpstreamsClockDerivedField() throws Exception {
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
        assertFalse(r.isSuccess(), "two writers, one rule, no locals, no parameters — it"
            + " passes every OTHER condition, which is what makes it the case worth having");
        assertTrue(String.valueOf(r.getError()).contains("nanoTime"),
            "and the refusal must name the CALL that makes the substitution unsound, since"
                + " that is the only thing separating this from a legitimate case: "
                + r.getError());
        assertTrue(before.equals(Files.readString(breaker, StandardCharsets.UTF_8)),
            "a refusal must leave upstream's file byte-for-byte untouched");
    }
}
