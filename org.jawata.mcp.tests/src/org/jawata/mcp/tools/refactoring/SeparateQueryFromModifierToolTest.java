package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.api.SeparateQueryFromModifierTool;
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
 * Stage 4, row 61 — Separate Query from Modifier, as
 * {@code change_method_signature kind=separate_query_from_modifier}.
 *
 * <p>The row performs the case where no body has to be copied — the method returns a field it
 * wrote — and REFUSES the rest rather than duplicating a body and hoping. Both halves are
 * tested, because the refusals are the larger part of what this row is.</p>
 */
class SeparateQueryFromModifierToolTest {

    /**
     * ONE METHOD'S OWN TEXT, from its signature to the first closing brace at method indent.
     *
     * <p>Needed because the obvious needle does not work here and the first version of this
     * test proved it: the row's whole point is that {@code return failures;} MOVES, so after a
     * successful run the file still contains that line — in the query the row just generated.
     * A whole-file {@code assertFalse} therefore fails on a correct result. The same trap one
     * step over caught the caller assertion, where {@code targets.failureCount();} is a
     * substring of the legitimate {@code int count = targets.failureCount();}.</p>
     */
    private static String bodyOf(String source, String signature) {
        int at = source.indexOf(signature);
        if (at < 0) {
            throw new AssertionError("no method matching '" + signature + "' in:\n" + source);
        }
        int end = source.indexOf("\n    }", at);
        return source.substring(at, end < 0 ? source.length() : end);
    }

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
        targets = pkg.resolve("CommandQueryTargets.java");
        desk = pkg.resolve("CommandQueryDesk.java");
    }

    private ObjectNode argsFor(String method) {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "separate_query_from_modifier");
        args.put("symbol", "com.example.CommandQueryTargets#" + method);
        return args;
    }

    @Test
    @DisplayName("the command loses its answer, a query appears, and only the call that USED "
        + "the answer is split")
    void separatesTheQueryFromTheCommand() throws Exception {
        ObjectNode args = argsFor("recordFailure");
        args.put("queryName", "failureCount");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        org.junit.jupiter.api.Assertions.assertAll(

            () -> {
                String after = Files.readString(targets, StandardCharsets.UTF_8);
                assertTrue(after.contains("public void recordFailure()"),
                    "the command must answer nothing:\n" + after);
                assertFalse(bodyOf(after, "public void recordFailure()").contains("return"),
                    "and the COMMAND's own body must no longer hand the field back — scoped to"
                        + " that method, because the query the row just generated returns the"
                        + " same field and a whole-file search hits it:\n" + after);
                assertTrue(after.contains("public int failureCount()"),
                    "the query must exist beside it:\n" + after);
            },

            // THE CALL THAT USED THE ANSWER. Two adjacent statements, so nothing can change the
            // field between the command and the read.
            () -> {
                String other = Files.readString(desk, StandardCharsets.UTF_8);
                assertTrue(other.contains("targets.recordFailure();")
                        && other.contains("int count = targets.failureCount();"),
                    "the caller that used the answer must command first and ask second:\n"
                        + other);
            },

            // THE CALL THAT IGNORED IT was only ever a command and must be untouched — a row
            // that split it too would add a read nobody asked for.
            () -> {
                String other = Files.readString(desk, StandardCharsets.UTF_8);
                assertFalse(bodyOf(other, "public void justRecord()").contains("failureCount"),
                    "the call that ignored the answer must NOT gain a query call — scoped to"
                        + " that method, because the legitimate split one method up CONTAINS"
                        + " `targets.failureCount();` as a substring:\n" + other);
            });
    }

    @Test
    @DisplayName("REFUSES a method whose answer is computed, because separating it would run "
        + "the body twice")
    void refusesAComputedAnswer() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = tool.execute(argsFor("describeFailure"));

        assertFalse(r.isSuccess(), "the answer is assembled from locals, not read from a field");
        assertEquals(SeparateQueryFromModifierTool.Refusal.RETURN_NOT_A_WRITTEN_FIELD,
            r.getError().getReason(),
            "the refusal must be the returned-shape PRECONDITION: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("REFUSES an answer COMPUTED FROM the field it wrote — the case a relaxed rule "
        + "would silently get wrong")
    void refusesAnAnswerComputedFromTheField() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = tool.execute(argsFor("doubledFailures"));

        assertFalse(r.isSuccess(),
            "the answer is failures * 2, so a query returning `failures` would promise callers"
                + " something the method never returned");
        assertEquals(SeparateQueryFromModifierTool.Refusal.RETURN_NOT_A_WRITTEN_FIELD,
            r.getError().getReason(),
            "the refusal must be the returned-shape PRECONDITION: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("REFUSES a method that answers a field it never writes — already a query")
    void refusesAPureQuery() throws Exception {
        ToolResponse r = tool.execute(argsFor("currentUntouched"));

        assertFalse(r.isSuccess(), "nothing is commanded, so nothing needs separating");
        assertEquals(SeparateQueryFromModifierTool.Refusal.RETURN_NOT_A_WRITTEN_FIELD,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a void method — already a command")
    void refusesACommand() throws Exception {
        ToolResponse r = tool.execute(argsFor("clear"));

        assertFalse(r.isSuccess(), "it answers nothing, so there is no query in it");
        assertEquals(SeparateQueryFromModifierTool.Refusal.RETURNS_VOID,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("queryName DEFAULTS to the field's own name")
    void defaultsTheQueryNameToTheField() throws Exception {
        ToolResponse r = tool.execute(argsFor("recordFailure"));
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertTrue(Files.readString(targets, StandardCharsets.UTF_8)
                .contains("public int failures()"),
            "the field's own name is the query's, because the value already has a name:\n"
                + Files.readString(targets, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("auto_apply=false STAGES: the file is untouched until the change is applied")
    void stagesTheChangeAndAppliesItOnDemand() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ObjectNode args = argsFor("recordFailure");
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
        assertTrue(Files.readString(targets, StandardCharsets.UTF_8)
                .contains("public void recordFailure()"),
            "and the staged change must be the real one");
    }

    @Test
    @DisplayName("the undo handle RESTORES both files")
    void undoRestoresEverything() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        String beforeDesk = Files.readString(desk, StandardCharsets.UTF_8);
        ToolResponse r = tool.execute(argsFor("recordFailure"));
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
            "and so is the caller's — a per-file undo that missed one would leave a caller"
                + " assigning from a void method");
    }

    @Test
    @DisplayName("the door routes the kind")
    void theDoorRoutesIt() {
        assertTrue(tool.publishedKinds().contains("separate_query_from_modifier"),
            "a kind that is dispatched and not published is invisible in tools/list");
        assertTrue(tool.delegates().get("separate_query_from_modifier")
                instanceof SeparateQueryFromModifierTool,
            "and the routing table must reach this delegate");
    }
}
