package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.MoveTool;
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
 * Sprint 28d-rescue, rows 25 and 26 — Move Statements into Function and its inverse, Move
 * Statements to Callers, through {@code move}.
 *
 * <p>The pair's whole safety argument is the enumeration: Fowler's precondition is that the
 * statements <em>always</em> run with the call, and "always" is a claim about every call
 * site, not about the two the author happened to look at. The fixture is built so one
 * method satisfies it and a nearly identical one does not — {@code note()} carries the audit
 * line at both its calls, {@code partial()} at one of two — which makes the second test a
 * control rather than a decoration. Without the enumeration the same call would fold the
 * line in and silently add it at the site that never had it.</p>
 *
 * <p>The round trip is asserted too, because these two rows are supposed to be inverses and
 * a pair that does not come back is two operations that merely look symmetric.</p>
 */
class MoveStatementsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private MoveTool tool;
    private ObjectMapper mapper;
    private Path pkg;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new MoveTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        pkg = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example");
    }

    private String read(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** The nth line containing the marker, zero-based, so repeated lines are addressable. */
    private int lineOf(Path file, String marker, int occurrence) throws Exception {
        String[] lines = read(file).split("\n", -1);
        int seen = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker) && seen++ == occurrence) {
                return i;
            }
        }
        throw new AssertionError("PROOF OF LIFE: " + file.getFileName() + " has fewer than "
            + (occurrence + 1) + " lines containing " + marker);
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
                at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private ToolResponse move(String kind, Path file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", kind);
        args.put("filePath", file.toString());
        args.put("line", line);
        args.put("column", column);
        return tool.execute(args);
    }

    @Test
    @DisplayName("a statement beside EVERY call moves into the function, and comes back out")
    void theStatementMovesInAndBackOut() throws Exception {
        Path caller = pkg.resolve("AuditedCaller.java");
        Path callee = pkg.resolve("Audited.java");
        assertEquals(3, countOf(read(caller), "Audited.audits = Audited.audits + 1;"),
            "PROOF OF LIFE: three audit lines to start — two beside note(), one beside"
                + " partial()");

        int line = lineOf(caller, "Audited.audits = Audited.audits + 1;", 0);
        ToolResponse in = move("statements_into_function", caller, line, 8);
        assertTrue(in.isSuccess(), "the move in must run; got: " + in.getError());

        String afterCallee = read(callee);
        assertTrue(afterCallee.contains("audits = audits + 1")
                || afterCallee.contains("Audited.audits = Audited.audits + 1"),
            "the audit line arrived in the callee:\n" + afterCallee);
        assertEquals(1, countOf(read(caller), "Audited.audits = Audited.audits + 1;"),
            "and left both note() call sites, leaving only partial()'s:\n" + read(caller));

        // THE INVERSE. If these two rows are not each other's undo, they are two operations
        // that only look symmetric, and the pair's whole claim is that they are one idea.
        int inCallee = lineOf(callee, "audits", 1);
        ToolResponse out = move("statements_to_callers", callee, inCallee, 8);
        assertTrue(out.isSuccess(), "the move out must run; got: " + out.getError());
        assertEquals(3, countOf(read(caller), "Audited.audits = Audited.audits + 1;"),
            "the round trip restores all three audit lines:\n" + read(caller));
    }

    @Test
    @DisplayName("a statement beside only SOME calls is refused, with the ratio named")
    void aStatementAtSomeCallsIsRefused() throws Exception {
        Path caller = pkg.resolve("AuditedCaller.java");
        String before = read(caller);
        // The audit line beside partial(), which the OTHER call to partial() does not have.
        int line = lineOf(caller, "Audited.audits = Audited.audits + 1;", 2);

        ToolResponse r = move("statements_into_function", caller, line, 8);

        assertFalse(r.isSuccess(), "moving it in would add the line where it never ran");
        String error = String.valueOf(r.getError());
        assertTrue(error.contains("1 of 2"),
            "and the refusal must name the ratio, since that is what says whether the idea"
                + " was nearly right or wrong: " + error);
        assertEquals(before, read(caller), "nothing may change on a refusal");
    }

    @Test
    @DisplayName("a statement in the middle of a method cannot move to callers")
    void aMiddleStatementIsRefused() throws Exception {
        Path callee = pkg.resolve("Audited.java");
        String before = read(callee);
        int line = lineOf(callee, "audits = 0", 0);

        // A field declaration, not a statement of a method body: there is no call-site
        // position for it at all, which is the same refusal read from the other end.
        ToolResponse r = move("statements_to_callers", callee, line, 8);

        assertFalse(r.isSuccess(), "a field is not a movable statement");
        assertEquals(before, read(callee), "nothing may change on a refusal");
    }
}
