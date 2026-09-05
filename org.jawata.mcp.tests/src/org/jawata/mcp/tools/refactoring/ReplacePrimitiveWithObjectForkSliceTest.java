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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROW 54 (Replace Primitive with Object) performed on code we did not author.
 *
 * <p>Like row 22 and unlike row 16, this row HAS candidates in the fork and this is a
 * demonstration rather than a measured absence. It reuses the slice vendored for Stage 6's
 * Extract Class row rather than copying a module in for itself — the slice's
 * {@code PROVENANCE.md} records the commit and the upstream path, and it names the field
 * cluster it was chosen for. Nothing about it was selected to suit this row, which is what
 * makes the demonstration worth having.</p>
 *
 * <p><b>It found a defect before the test was written, and that is why the second case is
 * here.</b> {@code DefaultCircuitBreaker.recordFailure} writes {@code failureCount =
 * failureCount + 1} — a read of the field INSIDE the value being wrapped. Nothing in this
 * row's fixtures had that shape, and asking {@code ASTRewrite} to replace a node and one of
 * its own descendants is not something it can do. Upstream writes it in the second method of
 * the file.</p>
 *
 * <p><b>Why this slice and not the step-builder one</b> ({@code fork-step-builder}, vendored
 * for rows 16 and 22): every field in {@code Character} is behind class-level Lombok
 * {@code @Getter}/{@code @Setter}. JDT does not run Lombok's processor, so a Lombok-generated
 * setter is invisible to the reference search AND to the compile gate — the rewrite would
 * miss it and nothing here could see that it had. A demonstration on a field whose readers we
 * cannot enumerate is not a demonstration. {@code fork-circuit-breaker} uses no Lombok at all.
 * </p>
 */
class ReplacePrimitiveWithObjectForkSliceTest {

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

    private ToolResponse at(String declaration, String fieldName, String typeName)
            throws Exception {
        String[] lines = Files.readString(breaker, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(declaration)) {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "replace_primitive");
                args.put("filePath", breaker.toString());
                args.put("line", i);
                args.put("column", lines[i].indexOf(fieldName));
                if (typeName != null) {
                    args.put("typeName", typeName);
                }
                return tool.execute(args);
            }
        }
        throw new AssertionError("the vendored slice no longer declares: " + declaration);
    }

    @Test
    @DisplayName("upstream's own String field gets a value type, and both its accesses migrate")
    void performsOnUpstreamsLastFailureResponse() throws Exception {
        // `private String lastFailureResponse;` — written in recordFailure, read in
        // attemptRequest, both through `this.`, which is the FieldAccess shape rather than
        // the bare name every fixture here happens to use.
        ToolResponse r = at("private String lastFailureResponse;", "lastFailureResponse", null);
        assertTrue(r.isSuccess(), "upstream's field is a plain non-final String with two"
            + " accesses — the shape this row performs on: " + r.getError());

        String after = Files.readString(breaker, StandardCharsets.UTF_8);
        assertTrue(after.contains("public record LastFailureResponse(String value)"),
            "the value type must be generated from upstream's own field name:\n" + after);
        assertTrue(after.contains("private LastFailureResponse lastFailureResponse;"),
            "and the field retyped to it:\n" + after);
        assertTrue(after.contains("this.lastFailureResponse = new LastFailureResponse(response);"),
            "the write through `this.` is wrapped — the whole access, not the name inside"
                + " it:\n" + after);
        assertTrue(after.contains("return this.lastFailureResponse.value();"),
            "and the read through `this.` is unwrapped:\n" + after);
    }

    @Test
    @DisplayName("upstream writes `failureCount = failureCount + 1` — the read unwraps inside it")
    void handlesUpstreamsSelfReferentialWrite() throws Exception {
        // THE CASE NO FIXTURE HERE HAD. It is also a PACKAGE-PRIVATE field, so its reference
        // set is not confined to one file by visibility — the operation has to find them
        // rather than assume them.
        ToolResponse r = at("int failureCount;", "failureCount", "Failures");
        assertTrue(r.isSuccess(), "a plain assignment whose value reads the same field is not"
            + " a compound assignment and must not be refused as one: " + r.getError());

        String after = Files.readString(breaker, StandardCharsets.UTF_8);
        assertTrue(after.contains("failureCount = new Failures(failureCount.value() + 1);"),
            "the value is wrapped AND the read inside it unwrapped, as one edit:\n" + after);
        assertTrue(after.contains("if (failureCount.value() >= failureThreshold)"),
            "an ordinary read in a comparison is unwrapped too:\n" + after);
        assertTrue(after.contains("this.failureCount = new Failures(failureThreshold);"),
            "and a write whose value does NOT read the field is wrapped plainly:\n" + after);
    }
}
