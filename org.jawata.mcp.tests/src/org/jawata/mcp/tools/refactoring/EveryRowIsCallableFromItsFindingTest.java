package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ApplyCleanupTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3's contract clause "callable straight from the finding that names it … by file
 * position", asserted PER ROW rather than once for the shared mechanism.
 *
 * <p>An audit accepted that {@code MemberScope} is one narrowing used identically by every
 * kind, and still counted this clause as met for one row out of nine — because the
 * criterion says per row, and "the mechanism is shared" is the reasoning that lets a
 * broken row hide behind a working one.</p>
 *
 * <h2>The shape of each case, which is a pair and not an assertion</h2>
 *
 * <p>Pointing at a member the rule acts on and watching the file change proves nothing:
 * the unscoped sweep changes it too. So each row does BOTH, against the same file:</p>
 * <ol>
 *   <li>point at a member this rule REFUSES — the file must come back untouched, though
 *       the same sweep run without a position rewrites this file;</li>
 *   <li>then point at a member it acts on — the file must change.</li>
 * </ol>
 *
 * <p>Step 1 is the one that can fail if the narrowing is not real, and step 2 is what
 * stops step 1 from passing because the rule was simply inert. Run in that order on one
 * copy, since step 1 leaves the file pristine for step 2.</p>
 *
 * <h2>Four of the eight REFUSE, and that is the finding</h2>
 *
 * <p>Writing this test is what found it. A rewrite that MOVES code emits a linked pair of
 * edits, and a pair cannot be narrowed: keeping one half breaks it, and copying both
 * halves out of the tree breaks the link that joins them. So for guard_clauses,
 * consolidate_conditional, loop_to_pipeline and slide_declaration, a
 * position is refused with a reason rather than answered — the caller is told the rewrite
 * reaches past the member they named, which is true and useful, instead of being handed a
 * silently widened change or a silently empty one.</p>
 *
 * <p>The expectation is encoded PER ROW, from measurement. A row that starts confining, or
 * stops, changes this file — which is the point of asserting it here rather than once for
 * the shared mechanism. The heading above said FIVE and named remove_dead_code among them
 * until a C6 audit read it against the file's own expectations, which have said four since
 * commit 42044351 narrowed that row.</p>
 */
class EveryRowIsCallableFromItsFindingTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();

    /** What narrowing to this row's rewrite does, measured rather than assumed. */
    private enum Narrowing {
        /** The edit lies inside the member, so the position confines it. */
        CONFINES,
        /** The rewrite MOVES code past the member, so narrowing is refused with a reason. */
        REFUSES
    }

    private void assertPositionConfines(String kind, String fixture, String refusedMember,
                                        String actedMember, Narrowing expected)
            throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        ApplyCleanupTool tool = new ApplyCleanupTool(() -> service, new RefactoringChangeCache());
        Path file = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example/" + fixture);
        String before = Files.readString(file, StandardCharsets.UTF_8);

        ToolResponse quiet = run(tool, kind, file, lineOf(before, refusedMember));
        assertTrue(quiet.isSuccess(), kind + " scoped to " + refusedMember + " must not"
            + " error; a member with nothing to do is an answer. Got: " + quiet.getError());
        assertEquals(before, Files.readString(file, StandardCharsets.UTF_8),
            kind + " rewrote something outside " + refusedMember + ", which the caller"
                + " named — the whole-file sweep does change this file, so this is the"
                + " position failing to confine it");

        ToolResponse acted = run(tool, kind, file, lineOf(before, actedMember));
        String afterActing = Files.readString(file, StandardCharsets.UTF_8);
        if (expected == Narrowing.CONFINES) {
            assertTrue(acted.isSuccess(), kind + " scoped to " + actedMember + " must run;"
                + " got: " + acted.getError());
            assertNotEquals(before, afterActing,
                kind + " changed nothing at " + actedMember + " either, so the case above"
                    + " passed because the rule is inert rather than because it was"
                    + " confined");
        } else {
            // The rewrite moves code, so it reaches past the member the caller named. It
            // must SAY so — the one answer that would be wrong here is quietly rewriting
            // the file, or quietly doing nothing while reporting success.
            assertTrue(!acted.isSuccess(), kind + " cannot narrow a move, so it must refuse"
                + " rather than answer: " + acted.getData());
            assertTrue(String.valueOf(acted.getError()).contains("MOVES code"),
                "and the refusal must name the reason: " + acted.getError());
            assertEquals(before, afterActing, "nothing may be written on a refusal");
        }
    }

    private ToolResponse run(ApplyCleanupTool tool, String kind, Path file, int line) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", kind);
        args.put("filePath", file.toString());
        args.put("line", line);
        args.put("column", 4);
        return tool.execute(args);
    }

    private static int lineOf(String source, String member) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(" " + member + "(")) {
                return i;
            }
        }
        throw new AssertionError("PROOF OF LIFE: the fixture no longer declares " + member);
    }

    @Test
    @DisplayName("the published list of kinds that refuse a position matches what they DO")
    void thePublishedListMatchesBehaviour() {
        // The list in apply_cleanup's description cannot be derived — whether a rewrite
        // emits a move is a property of the edit tree, not of the kind. So it is bound to
        // behaviour here: the rows this class measures as REFUSES must be exactly the
        // kinds the tool tells callers will refuse.
        assertEquals(new java.util.TreeSet<>(org.jawata.mcp.tools.ApplyCleanupTool
                .POSITION_REFUSING_KINDS),
            new java.util.TreeSet<>(java.util.List.of(
                "guard_clauses", "consolidate_conditional", "loop_to_pipeline",
                "slide_declaration")),
            "the published list and the per-row expectations below have drifted apart;"
                + " one of them is now lying to a caller");
    }

    @Test
    @DisplayName("row 52 guard_clauses")
    void guardClauses() throws Exception {
        assertPositionConfines("guard_clauses", "GuardClauseTargets.java",
            "breaksOutOfLoop", "nestedReturns", Narrowing.REFUSES);
    }

    @Test
    @DisplayName("row 7 consolidate_conditional")
    void consolidateConditional() throws Exception {
        assertPositionConfines("consolidate_conditional", "ConsolidateTargets.java",
            "differentBodies", "disabilityAmount", Narrowing.REFUSES);
    }

    @Test
    @DisplayName("row 44 control_flag_to_break")
    void controlFlagToBreak() throws Exception {
        assertPositionConfines("control_flag_to_break", "ControlFlagTargets.java",
            "readAfterwards", "pureExit", Narrowing.CONFINES);
    }

    @Test
    @DisplayName("row 50 loop_to_pipeline")
    void loopToPipeline() throws Exception {
        assertPositionConfines("loop_to_pipeline", "PipelineTargets.java",
            "stopsEarly", "activeNames", Narrowing.REFUSES);
    }

    @Test
    @DisplayName("row 62 slide_declaration")
    void slideDeclaration() throws Exception {
        assertPositionConfines("slide_declaration", "SlideTargets.java",
            "reassignedInBetween", "slidesDown", Narrowing.REFUSES);
    }

    @Test
    @DisplayName("row 63 split_loop")
    void splitLoop() throws Exception {
        assertPositionConfines("split_loop", "SplitLoopTargets.java",
            "stopsEarly", "twoJobs", Narrowing.CONFINES);
    }

    @Test
    @DisplayName("row 60 return_modified_value")
    void returnModifiedValue() throws Exception {
        assertPositionConfines("return_modified_value", "ReturnModifiedValueTargets.java",
            "accumulates", "describe", Narrowing.CONFINES);
    }

    @Test
    @DisplayName("row 34 remove_dead_code")
    void removeDeadCode() throws Exception {
        assertPositionConfines("remove_dead_code", "DeadCodeTargets.java",
            "used", "obsolete", Narrowing.CONFINES);
    }
}
