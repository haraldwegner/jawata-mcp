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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROW 16 (Hide Delegate) on code we did not author — and its measured ABSENCE.
 *
 * <h2>There is no demonstration to give, and that was measured rather than assumed</h2>
 *
 * <p>All 84 {@code find_quality_issue(kind=message_chains)} findings in the pinned fork were
 * read at their own {@code file:line} — a census, not a sample. Every one is either a JDK
 * pipeline (41) or a fluent / self-returning chain (43). <b>Not one is the shape this row
 * exists for</b>: a client reaching through one domain object to a second. The corpus is a
 * design-patterns teaching repository, so idiomatic pipelines and builders are exactly what
 * it contains. {@code PROVENANCE.md} beside the slice carries the breakdown.</p>
 *
 * <h2>What this test pins instead, and what that is worth</h2>
 *
 * <p>The vendored module carries BOTH shapes, so the row's two governing refusals are
 * exercised on upstream's own code rather than on a fixture written to produce them. A
 * refusal on foreign code is EVIDENCE and is NOT a demonstration — Stage 6 recorded that
 * distinction for row 38 and it holds here unchanged.</p>
 *
 * <p>What it buys is that the census cannot drift silently. If upstream grows a chain this
 * row could actually perform, or if a change to the row starts accepting a builder or a JDK
 * server, one of these two tests stops refusing and says so.</p>
 *
 * <p>Each test reloads the slice, so neither acts on what the other left behind — though
 * neither changes anything, which is the point.</p>
 */
class HideDelegateForkSliceTest {

    private static final String PKG = "src/main/java/com/iluwatar/stepbuilder";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DataTool tool;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("fork-step-builder");
        tool = new DataTool(() -> service, new org.jawata.mcp.refactoring.RefactoringChangeCache());
        root = service.getProjectRoot();
    }

    private static int lineOf(Path file, String marker) throws Exception {
        String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                return i;
            }
        }
        throw new AssertionError("the vendored slice no longer contains: " + marker);
    }

    private ToolResponse hideAt(Path file, String marker, int column) throws Exception {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "hide_delegate");
        args.put("filePath", file.toString());
        args.put("line", lineOf(file, marker));
        args.put("column", column);
        return tool.execute(args);
    }

    @Test
    @DisplayName("upstream's fluent builder is refused: its server is a step INTERFACE")
    void refusesUpstreamsFluentBuilder() throws Exception {
        // THE 43-of-84 SHAPE, and it refused for a reason no fixture had produced.
        //
        // The prediction was the STATIC refusal — CharacterStepBuilder.newBuilder() is
        // static, so there is no receiver to hide behind. That is not what happens, and the
        // difference is this row's own documented rule working as written: a position
        // selects the OUTERMOST two-deep pair, and in a chain of six that pair is
        // `.noAbilities().build()`, whose intermediate call is an INSTANCE method on a step
        // interface. Nothing static is involved by the time the operation looks.
        //
        // The first run of this test therefore did not refuse at all: it generated a
        // concrete method body into an interface, and the pipeline's compile gate caught
        // "Abstract methods do not specify a body" and undid the change. The interface
        // refusal exists because upstream's code produced that, and a fixture would not
        // have — every fixture server written for this row was a class.
        Path app = root.resolve(PKG).resolve("App.java");
        String before = Files.readString(app, StandardCharsets.UTF_8);

        ToolResponse r = hideAt(app, "CharacterStepBuilder.newBuilder()", 24);

        assertFalse(r.isSuccess(), "a step interface cannot take a generated forwarder: "
            + r.getError());
        assertTrue(String.valueOf(r.getError()).contains("is an INTERFACE"),
            "and the refusal must name that reason rather than failing through the compile"
                + " gate, which reports a symptom instead of a decision: " + r.getError());
        assertEquals(before, Files.readString(app, StandardCharsets.UTF_8),
            "a refusal must leave upstream's file byte-for-byte untouched");
    }

    @Test
    @DisplayName("upstream's StringBuilder chain is refused: the hidden call carries an argument")
    void refusesAnArgumentBearingIntermediate() throws Exception {
        // THE 41-of-84 SHAPE. The prediction here was the no-source refusal, since the
        // server is java.lang.StringBuilder — but an earlier gate fires first and correctly:
        // in `.append("This is a ").append(x)` the INTERMEDIATE call carries an argument, so
        // the forwarder would have to carry it too, and whether it should is a design
        // question. Both refusals are right; the order is what the prediction missed.
        //
        // The no-source refusal is exercised in HideDelegateToolTest, where the fixture can
        // present a JDK server reached by a no-argument call.
        Path character = root.resolve(PKG).resolve("Character.java");
        String before = Files.readString(character, StandardCharsets.UTF_8);

        ToolResponse r = hideAt(character, ".append(\"This is a \")", 20);

        assertFalse(r.isSuccess(), "an argument-bearing intermediate call is refused: "
            + r.getError());
        assertTrue(String.valueOf(r.getError()).contains("argument"),
            "and the refusal must name that reason: " + r.getError());
        assertEquals(before, Files.readString(character, StandardCharsets.UTF_8),
            "a refusal must leave upstream's file byte-for-byte untouched");
    }
}
