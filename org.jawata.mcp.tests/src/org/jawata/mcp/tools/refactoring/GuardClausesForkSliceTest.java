package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ApplyCleanupTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 52 ON CODE WE DID NOT AUTHOR.
 *
 * <p>Every row of this sprint owes a demonstration on the fork corpus, and the reason is
 * that a fixture written alongside a rewrite is written to suit it. A fixture proves the
 * rule does what its author meant; only somebody else's code proves the rule survives
 * contact with how people actually write.</p>
 *
 * <p>NO NEW COPYING. The candidate is in a slice Stage 7 already vendored verbatim from
 * the pinned fork — {@code DefaultCircuitBreaker.attemptApiCall}, which answers from a
 * cache when the breaker is open and otherwise makes the call inside a {@code try}. The
 * then-branch returns, so the {@code else} is nesting rather than an alternative, and
 * this is exactly the shape Fowler's mechanic is for. Finding it in code already present
 * is worth more than vendoring a better example: nothing here was chosen to be easy.</p>
 *
 * <p>Stated so it is not over-claimed: this demonstrates the OPERATION on foreign code.
 * It is not a claim that the circuit breaker ought to be rewritten. Whether a particular
 * nest is worth flattening is a reader's judgement, which is why the rewrite is offered
 * and never applied on its own.</p>
 */
class GuardClausesForkSliceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ApplyCleanupTool tool;
    private ObjectMapper mapper;
    private Path breakerFile;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("fork-circuit-breaker");
        tool = new ApplyCleanupTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        breakerFile = helper.getTempDirectory()
            .resolve("fork-circuit-breaker/src/main/java/com/iluwatar/circuitbreaker"
                + "/DefaultCircuitBreaker.java");
    }

    private ToolResponse run() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "guard_clauses");
        args.put("filePath", breakerFile.toString());
        return tool.execute(args);
    }

    @Test
    @DisplayName("the cached-response nest flattens into a guard, on the fork's own code")
    void theForksNestBecomesAGuard() throws Exception {
        String before = Files.readString(breakerFile, StandardCharsets.UTF_8);
        assertTrue(before.contains("return this.lastFailureResponse;"),
            "PROOF OF LIFE: the vendored slice must still carry the before-shape this"
                + " test is about. If the fork moved, this test is measuring nothing.");

        ToolResponse r = run();
        assertTrue(r.isSuccess(), "the cleanup must run on foreign code; got: " + r.getError());
        String after = Files.readString(breakerFile, StandardCharsets.UTF_8);

        assertTrue(after.contains("return this.lastFailureResponse;"),
            "the cached-response path must survive — this moves code and removes none:\n"
                + after);
        assertTrue(count(after, "else") < count(before, "else"),
            "at least one else guarded a branch that returns, and should be gone. Before "
                + count(before, "else") + ", after " + count(after, "else"));
        assertTrue(after.contains("try {"),
            "and the else's body — a try block — must have been lifted, not dropped:\n"
                + after);
    }

    @Test
    @DisplayName("it compiles afterwards, which on foreign code is the assertion that matters")
    void theResultCompiles() {
        ToolResponse r = run();
        // The apply pipeline compiles the modified files and UNDOES the change when new
        // errors appear, so a success here is a compile-verified success. That is the
        // whole value of running on code nobody wrote for this test: a rewrite tuned to a
        // fixture meets shapes it was never shown, and the gate is what catches it. It
        // caught this rule's first version on a fixture.
        assertTrue(r.isSuccess(),
            "a rewrite that breaks foreign code is refused and reverted by the compile"
                + " gate, and the refusal reads like this: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("undoChangeId"),
            "and it is reversible on foreign code exactly as on our own: " + data);
        assertTrue(data.get("filesModified") instanceof List<?> files && !files.isEmpty(),
            "with the file named: " + data);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
