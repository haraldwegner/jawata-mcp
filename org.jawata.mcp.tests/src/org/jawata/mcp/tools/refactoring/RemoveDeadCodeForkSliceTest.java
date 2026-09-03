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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 34 ON CODE WE DID NOT AUTHOR — and it found something real.
 *
 * <p>Every row of this sprint owes a demonstration on the fork corpus, because a fixture
 * written alongside a rewrite is written to suit it. NO NEW COPYING was needed: the slice
 * Stage 7 already vendored carries genuine dead state, and nobody had noticed.</p>
 *
 * <p>{@code DefaultCircuitBreaker} declares {@code private final long timeout}, assigns it
 * in the constructor, and never reads it. The assignment sits under a comment saying
 * "Used to break the calls made to remote resource if it exceeds the limit" — which is
 * not true of this class, and is a fair illustration of why dead state costs something:
 * the comment is the only evidence of an intention the code abandoned, and it reads as
 * documentation of behaviour that is not there.</p>
 *
 * <h2>What the constructor parameter proves</h2>
 *
 * <p>The field goes and the constructor PARAMETER stays, so the signature is untouched
 * and every caller still compiles. That is the rule's parameter refusal earning its
 * place on foreign code rather than on a fixture: JDT would take the now-unused
 * parameter too, and taking it changes a published constructor.</p>
 *
 * <h2>The hazard this sits next to</h2>
 *
 * <p>jawata's own {@code unused} detector — the finding that routes a user here — reports
 * 325 items across the fork, and a large share are wrong: the fork uses Lombok, so a
 * field read only through a generated accessor looks unreferenced to an AST walk. This
 * cure reads the COMPILER's problem set instead, so a false finding yields no removal.
 * The two are independent on purpose, and the detector's accuracy is a separate matter
 * from this row.</p>
 */
class RemoveDeadCodeForkSliceTest {

    /** Private fields of the vendored breaker that ARE read, and must survive. */
    private static final List<String> LIVE_FIELDS = List.of(
        "retryTimePeriod", "service", "lastFailureResponse",
        "failureThreshold", "state", "futureTime");

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
        args.put("kind", "remove_dead_code");
        args.put("filePath", breakerFile.toString());
        return tool.execute(args);
    }

    @Test
    @DisplayName("the fork's own write-only field goes, and everything it reads stays")
    void theForksDeadFieldIsRemoved() throws Exception {
        String before = Files.readString(breakerFile, StandardCharsets.UTF_8);
        assertTrue(before.contains("private final long timeout;"),
            "PROOF OF LIFE: the vendored slice must still declare the dead field this"
                + " test is about. If the fork moved, this test is measuring nothing.");

        ToolResponse r = run();
        assertTrue(r.isSuccess(), "the cleanup must run on foreign code; got: " + r.getError());
        String after = Files.readString(breakerFile, StandardCharsets.UTF_8);

        assertFalse(after.contains("private final long timeout;"),
            "the field is assigned and never read, so it goes:\n" + after);
        assertFalse(after.contains("this.timeout = timeout;"),
            "and its constructor assignment goes with it — leaving the write behind would"
                + " not compile:\n" + after);

        // THE REFUSAL, PAYING OFF. JDT would remove the now-unused constructor parameter
        // and can rename the member to dodge an overload collision. Either would change a
        // published signature, and the caller asked to remove dead code.
        assertTrue(after.contains("long timeout,"),
            "the constructor parameter must survive, so the signature is unchanged and"
                + " every caller still compiles:\n" + after);

        for (String field : LIVE_FIELDS) {
            assertTrue(after.contains(field),
                "'" + field + "' is read by this class and must still be here:\n" + after);
        }
    }

    @Test
    @DisplayName("it is compile-verified and reversible on foreign code, exactly as on our own")
    void theResultCompilesAndCanBeUndone() {
        ToolResponse r = run();
        // The apply pipeline compiles the modified files and UNDOES the change when new
        // errors appear, so a success here is a compile-verified success. On a removal
        // that is the assertion that matters most: the failure mode is deleting something
        // reachable, and the compile gate is what refuses it.
        assertTrue(r.isSuccess(),
            "a removal that breaks foreign code is refused and reverted by the compile"
                + " gate, and the refusal reads like this: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("undoChangeId"),
            "a deletion above all others must be reversible: " + data);
        assertTrue(data.get("filesModified") instanceof List<?> files && !files.isEmpty(),
            "with the file named: " + data);
    }
}
