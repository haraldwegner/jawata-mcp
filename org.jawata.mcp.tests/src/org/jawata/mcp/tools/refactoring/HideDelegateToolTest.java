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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 5, row 16 — Hide Delegate, reached as {@code data kind=hide_delegate}.
 *
 * <p>Fowler's own example: {@code john.getDepartment().getManager()} becomes
 * {@code john.getManager()}, with the forwarder generated on {@code Person}. The fixture is
 * {@code HideDelegateTargets.java}, which carries the canonical case beside each shape the
 * operation must refuse.</p>
 *
 * <p><b>Both halves are asserted, and the second is the one that can silently not happen.</b>
 * Generating a forwarder on the server is visible and easy to check; SHORTENING THE CALL SITE
 * is the point of the refactoring, and a version that generated the method and left the chain
 * alone would look successful and change nothing. Each happy-path case therefore asserts the
 * server gained the method AND that the client no longer names the delegate.</p>
 */
class HideDelegateToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DataTool tool;
    private ObjectMapper objectMapper;
    private Path targets;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new DataTool(() -> service, new org.jawata.mcp.refactoring.RefactoringChangeCache());
        objectMapper = new ObjectMapper();
        targets = service.getProjectRoot()
            .resolve("src/main/java/com/example/HideDelegateTargets.java");
    }

    /** The chain's line, found by reading the fixture rather than pinning a literal. */
    private int lineOf(String needle) throws Exception {
        String[] lines = Files.readString(targets).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i;
            }
        }
        throw new IllegalStateException("fixture no longer contains: " + needle);
    }

    private ObjectNode at(int line, int column) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "hide_delegate");
        args.put("filePath", targets.toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    @Test
    @DisplayName("the canonical chain: the server gains the forwarder AND the client loses the hop")
    void hidesTheDelegate() throws Exception {
        int line = lineOf("return john.getDepartment().getManager();");
        ToolResponse r = tool.execute(at(line, 20));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets);
        assertTrue(after.contains("public String getManager()"),
            "the forwarder must be generated on Person:\n" + after);
        assertTrue(after.contains("return john.getManager();"),
            "THE POINT OF THE REFACTORING: the call site must lose the middle hop. A run that"
                + " generated the method and left the chain alone would report success and"
                + " have changed nothing a client sees:\n" + after);
        assertFalse(after.contains("john.getDepartment().getManager()"),
            "the old chain must not survive alongside the new call");
    }

    @Test
    @DisplayName("the hidden call's arguments are carried through to the forwarder")
    void carriesArgumentsThrough() throws Exception {
        int line = lineOf("return john.getDepartment().budgetFor(months);");
        ToolResponse r = tool.execute(at(line, 20));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets);
        assertTrue(after.contains("public int budgetFor(int a0)"),
            "the forwarder carries the hidden call's parameter, built from its binding rather"
                + " than assumed empty:\n" + after);
        assertTrue(after.contains("return john.budgetFor(months);"),
            "and the call site passes its OWN argument text through unchanged:\n" + after);
    }

    @Test
    @DisplayName("a return type from another package is IMPORTED, not written as a bare name")
    void importsACrossPackageReturnType() throws Exception {
        // THE CASE THAT USED TO DECLINE. The forwarder returns com.example.service.Lead,
        // which Person's file does not import. Writing the simple name produced source that
        // does not compile, so the pipeline's compile gate refused the whole change — safe,
        // and useless. Measured by reverting the fix: REFACTORING_BROKE_COMPILE, "Lead
        // cannot be resolved to a type", the change UNDONE. This asserts it now handles the
        // case rather than declining.
        int line = lineOf("return john.getDepartment().getLead();");
        ToolResponse r = tool.execute(at(line, 20));
        assertTrue(r.isSuccess(),
            "a cross-package return type must be imported, not refused: " + r.getError());

        String after = Files.readString(targets);
        assertTrue(after.contains("import com.example.service.Lead;"),
            "the import must be added to the server's file:\n" + after);
        assertTrue(after.contains("public Lead getLead()"),
            "and the forwarder then uses the simple name it just imported:\n" + after);
        assertTrue(after.contains("return john.getLead();"),
            "the call site loses the hop, as in every other case:\n" + after);
    }

    @Test
    @DisplayName("REFUSES a server type this workspace cannot edit, and says which")
    void refusesAServerItCannotEdit() throws Exception {
        // getClass() is declared on java.lang.Object. The SHAPE is a perfect match — two
        // calls deep, no arguments on the first — and only the absence of source
        // distinguishes it, which is exactly why it is worth a case of its own.
        int line = lineOf("return anything.getClass().getName();");
        ToolResponse r = tool.execute(at(line, 24));
        assertFalse(r.isSuccess(), "a JDK server type has no source to add a forwarder to");
        assertTrue(String.valueOf(r.getError()).contains("no source in"),
            "the refusal must name why rather than failing vaguely: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a fluent builder — a static intermediate call has no receiver")
    void refusesAFluentBuilder() throws Exception {
        // THE SHAPE THE CORPUS ACTUALLY CONTAINS. A census of all 84 message_chains findings
        // in java-design-patterns returned 41 JDK pipelines and 43 fluent builders, and no
        // chain of the kind this row exists for. Product.builder().name("Eggs") has no
        // receiver object: generating Product.name("Eggs") would drop the builder entirely.
        // The census is what found this gap — the operation did not refuse it before.
        int line = lineOf("return Ticket.create().label(\"urgent\");");
        ToolResponse r = tool.execute(at(line, 24));
        assertFalse(r.isSuccess(), "a static intermediate call is a factory, not a delegate");
        assertTrue(String.valueOf(r.getError()).contains("is STATIC"),
            "the refusal must name the reason: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a position that is not a two-deep chain")
    void refusesASingleCall() throws Exception {
        int line = lineOf("return john.getDepartment();");
        ToolResponse r = tool.execute(at(line, 20));
        assertFalse(r.isSuccess(), "one call deep is not a chain; there is no delegate to hide");
        assertTrue(String.valueOf(r.getError()).contains("two-deep call chain"),
            "the refusal must say what shape it needed: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a name the server already declares, and names the clash")
    void refusesANameCollision() throws Exception {
        int line = lineOf("return john.getDepartment().getManager();");
        ObjectNode args = at(line, 20);
        args.put("delegateMethodName", "getDepartment");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "Person already declares getDepartment/0");
        assertTrue(String.valueOf(r.getError()).contains("getDepartment/0"),
            "the refusal must name the clashing member and its arity: " + r.getError());
    }

    @Test
    @DisplayName("the kind is routed and published by the door")
    void theDoorRoutesIt() {
        assertTrue(tool.delegates().containsKey("hide_delegate"),
            "row 16 must be reachable as data kind=hide_delegate");
        assertEquals("hide_delegate", tool.delegates().get("hide_delegate").kindName(),
            "the routing key and the delegate's own name are two spellings of one fact");
        assertTrue(tool.publishedKinds().contains("hide_delegate"),
            "and a client reading tools/list must see it — the enum is the routing table");
    }
}
