package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.api.ParameterizeFunctionTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 4, row 27 — Parameterize Function, as
 * {@code change_method_signature kind=parameterize_function}.
 *
 * <p>What this file establishes is that the OPERATION is what it claims: the constant leaves
 * the body, the signature gains a parameter under the name asked for, and the caller in ANOTHER
 * FILE passes the constant the method stopped holding.</p>
 */
class ParameterizeFunctionToolTest {

    /** The WHOLE statement the row removes — no comment in the fixture contains one. */
    private static final String HOLDS = "return salary * 1.1;";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ChangeMethodSignatureTool tool;
    private org.jawata.mcp.tools.RefactoringTool lifecycle;
    private Path targets;
    private Path desk;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        org.jawata.mcp.refactoring.RefactoringChangeCache cache =
            new org.jawata.mcp.refactoring.RefactoringChangeCache();
        tool = new ChangeMethodSignatureTool(() -> service, cache);
        lifecycle = new org.jawata.mcp.tools.RefactoringTool(() -> service, cache);
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example");
        targets = pkg.resolve("ParameterizeTargets.java");
        desk = pkg.resolve("ParameterizeDesk.java");
    }

    private ObjectNode argsAt(String declaration, String methodName) throws Exception {
        String[] lines = Files.readString(targets, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(declaration)) {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "parameterize_function");
                args.put("filePath", targets.toString());
                args.put("line", i);
                args.put("column", lines[i].indexOf(methodName));
                return args;
            }
        }
        throw new AssertionError("the fixture no longer declares: " + declaration);
    }

    @Test
    @DisplayName("the constant becomes a parameter and the caller in another file passes it")
    void parameterizesTheConstant() throws Exception {
        ObjectNode args = argsAt("public double tenPercentRaise(", "tenPercentRaise");
        args.put("literal", "1.1");
        args.put("parameterName", "factor");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        org.junit.jupiter.api.Assertions.assertAll(

            () -> {
                String after = Files.readString(targets, StandardCharsets.UTF_8);
                assertTrue(after.contains("tenPercentRaise(double salary, double factor)")
                        || after.contains("tenPercentRaise(double factor, double salary)"),
                    "the method must take the constant under the name ASKED FOR:\n" + after);
                assertFalse(after.contains(HOLDS),
                    "and the body must no longer HOLD it:\n" + after);
            },

            // THE CROSS-FILE HALF. Nothing pointed the tool at this file. The needle is the
            // whole call, so it pins that the original argument survived and the constant
            // arrived beside it — a literal needs no import, which is what lets this row reach
            // a case row 55 cannot.
            () -> {
                String other = Files.readString(desk, StandardCharsets.UTF_8);
                assertTrue(other.contains("targets.tenPercentRaise(salary, 1.1)"),
                    "the caller in another file must supply the constant:\n" + other);
            },

            // The SIBLING is untouched, which is the point of the row being half of Fowler's
            // mechanic: folding it in is a redirect this same door publishes separately.
            () -> {
                String after = Files.readString(targets, StandardCharsets.UTF_8);
                assertTrue(after.contains("return salary * 1.05;"),
                    "the sibling method is NOT folded in by this kind, and saying so here is"
                        + " what stops a reader believing it was:\n" + after);
            });
    }

    @Test
    @DisplayName("REFUSES a literal the method never writes")
    void refusesALiteralThatIsNotThere() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ObjectNode args = argsAt("public double unchanged(", "unchanged");
        args.put("literal", "1.1");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "there is no such constant to parameterize");
        assertEquals(ParameterizeFunctionTool.Refusal.LITERAL_NOT_FOUND,
            r.getError().getReason(),
            "the refusal must be the not-found PRECONDITION: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("REFUSES when the constant is written twice — two equal constants are not "
        + "always the same constant")
    void refusesAnAmbiguousLiteral() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ObjectNode args = argsAt("public double doubleBump(", "doubleBump");
        args.put("literal", "1.1");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "two occurrences, and nothing in the request says which");
        assertEquals(ParameterizeFunctionTool.Refusal.AMBIGUOUS_LITERAL,
            r.getError().getReason(),
            "the refusal must be the ambiguity PRECONDITION: " + r.getError());
        assertTrue(String.valueOf(r.getError()).contains("2 times"),
            "and it must report HOW MANY it found: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("REFUSES without a literal, because the target is addressed by NAME")
    void refusesWithoutALiteral() throws Exception {
        ToolResponse r = tool.execute(argsAt("public double tenPercentRaise(",
            "tenPercentRaise"));

        assertFalse(r.isSuccess(), "a method alone does not name a constant");
        assertEquals(ParameterizeFunctionTool.Refusal.LITERAL_REQUIRED,
            r.getError().getReason(),
            "the refusal must be the name PRECONDITION: " + r.getError());
    }

    @Test
    @DisplayName("the literal is matched as WRITTEN — 1.1 is not 1.10")
    void matchesTheTextAsWritten() throws Exception {
        ObjectNode args = argsAt("public double tenPercentRaise(", "tenPercentRaise");
        args.put("literal", "1.10");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(),
            "1.10 and 1.1 are the same NUMBER and different TEXT, and the caller names what"
                + " they can see in the source");
        assertEquals(ParameterizeFunctionTool.Refusal.LITERAL_NOT_FOUND,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("runs from the method's SYMBOL NAME, with no file position given")
    void runsFromItsSymbolName() throws Exception {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "parameterize_function");
        args.put("symbol", "com.example.ParameterizeTargets#tenPercentRaise");
        args.put("literal", "1.1");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertFalse(Files.readString(targets, StandardCharsets.UTF_8).contains(HOLDS),
            "a finding names a symbol, not a caret");
    }

    @Test
    @DisplayName("auto_apply=false STAGES: the file is untouched until the change is applied")
    void stagesTheChangeAndAppliesItOnDemand() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ObjectNode args = argsAt("public double tenPercentRaise(", "tenPercentRaise");
        args.put("literal", "1.1");
        args.put("auto_apply", false);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "STAGING must not write");

        Object changeId = ((java.util.Map<?, ?>) r.getData()).get("changeId");
        assertNotNull(changeId, "the response carries no changeId: " + r.getData());
        ObjectNode apply = new ObjectMapper().createObjectNode();
        apply.put("action", "apply");
        apply.put("changeId", String.valueOf(changeId));
        assertTrue(lifecycle.execute(apply).isSuccess());
        assertFalse(Files.readString(targets, StandardCharsets.UTF_8).contains(HOLDS),
            "and the staged change must be the real one");
    }

    @Test
    @DisplayName("the undo handle RESTORES both files")
    void undoRestoresEverything() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        String beforeDesk = Files.readString(desk, StandardCharsets.UTF_8);
        ObjectNode args = argsAt("public double tenPercentRaise(", "tenPercentRaise");
        args.put("literal", "1.1");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertFalse(before.equals(Files.readString(targets, StandardCharsets.UTF_8)),
            "the CONTROL: with nothing changed, an undo that restores nothing would pass");

        Object handle = ((java.util.Map<?, ?>) r.getData()).get("undoChangeId");
        assertNotNull(handle, "the response carries no undoChangeId: " + r.getData());
        ObjectNode undo = new ObjectMapper().createObjectNode();
        undo.put("action", "undo");
        undo.put("undoChangeId", String.valueOf(handle));
        assertTrue(lifecycle.execute(undo).isSuccess());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "the method's own file is restored");
        assertEquals(beforeDesk, Files.readString(desk, StandardCharsets.UTF_8),
            "and so is the caller's");
    }

    @Test
    @DisplayName("the door routes the kind")
    void theDoorRoutesIt() {
        assertTrue(tool.publishedKinds().contains("parameterize_function"),
            "a kind that is dispatched and not published is invisible in tools/list");
        assertTrue(tool.delegates().get("parameterize_function")
                instanceof ParameterizeFunctionTool,
            "and the routing table must reach this delegate");
    }
}
