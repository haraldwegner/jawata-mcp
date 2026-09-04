package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ExtractTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue, row 64 — Split Phase, through {@code extract}.
 *
 * <p>Two things are being checked and they are different in kind. The BOUNDARY is the
 * caller's, so the tool's only duty there is to refuse a boundary that cannot work. What
 * the intermediate CARRIES is the tool's, derived from the code, and the fixture is built so
 * a derivation that simply took every phase-one local would be visibly wrong: {@code unused}
 * is declared before the boundary and read nowhere after it, so it must stay behind.</p>
 */
class SplitPhaseToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ExtractTool tool;
    private ObjectMapper mapper;
    private Path fixture;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new ExtractTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        fixture = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example/TwoPhase.java");
    }

    private String read() throws Exception {
        return Files.readString(fixture, StandardCharsets.UTF_8);
    }

    private int lineOf(String marker) throws Exception {
        String[] lines = read().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                return i;
            }
        }
        throw new AssertionError("PROOF OF LIFE: the fixture no longer has " + marker);
    }

    private ToolResponse split(String methodMarker, String boundaryMarker) throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "split_phase");
        args.put("filePath", fixture.toString());
        args.put("line", lineOf(methodMarker));
        args.put("column", 16);
        args.put("boundaryLine", lineOf(boundaryMarker));
        return tool.execute(args);
    }

    @Test
    @DisplayName("the function splits, and the record carries only what the second phase reads")
    void theFunctionSplits() throws Exception {
        ToolResponse r = split("public int priceOrder", "int discount = discountLevel * 100;");
        assertTrue(r.isSuccess(), "the split must run; got: " + r.getError());

        String after = read();
        assertTrue(after.contains("private record PriceOrderIntermediate("),
            "a carrier was generated:\n" + after);
        assertTrue(after.contains("int base"),
            "carrying `base`, which the second phase reads:\n" + after);
        // THE ASSERTION THAT MAKES IT A DERIVATION. `unused` is declared in phase one and
        // read nowhere afterwards; a version that carried every phase-one local would put it
        // in the record and still compile, still pass every other check here, and still be
        // wrong about what the refactoring is for.
        assertFalse(after.contains("int unused, ") || after.contains(", int unused"),
            "and NOT `unused`, which nothing after the boundary reads:\n" + after);
        assertTrue(after.contains("private PriceOrderIntermediate priceOrderPhase1(")
                && after.contains("priceOrderPhase2("),
            "both phases exist:\n" + after);
        assertTrue(after.contains("return priceOrderPhase2(intermediate"),
            "and the original method is the two calls composed:\n" + after);
    }

    @Test
    @DisplayName("a boundary after a return is refused — that is an early exit, not a phase")
    void anEarlyExitIsRefused() throws Exception {
        String before = read();
        ToolResponse r = split("public int earlyExit", "return base + 1;");

        assertFalse(r.isSuccess(), "phase two would not always run");
        assertTrue(String.valueOf(r.getError()).contains("early exit"),
            "and the refusal must name what it saw: " + r.getError());
        assertEquals(before, read(), "nothing may change on a refusal");
    }

    @Test
    @DisplayName("a second phase that assigns to a first-phase local is refused")
    void aWriteBackIsRefused() throws Exception {
        String before = read();
        ToolResponse r = split("public int writesBack", "base = base + 5;");

        assertFalse(r.isSuccess(), "a record's components are final");
        String error = String.valueOf(r.getError());
        assertTrue(error.contains("final") || error.contains("mutable"),
            "and the refusal must say why the carrier cannot take it: " + error);
        assertEquals(before, read(), "nothing may change on a refusal");
    }
}
