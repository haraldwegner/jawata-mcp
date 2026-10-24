package org.jawata.mcp.tools.refactoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.CompileWorkspaceTool;
import org.jawata.mcp.tools.MoveMethodTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28g S5 / D9 row 2 — the moved body's access to its old owner is ASKED, not decided.
 *
 * <p>The census counts <strong>eleven</strong> hand-edited move sites with one cause, in its own
 * summary figure: <em>"the moved body's access to its old owner decided silently (11)"</em>. Its
 * verdict line names the defect in this tool — <em>"how the moved body reaches its old owner is
 * MoveInstanceMethodProcessor's SILENT choice"</em> — and the cost beside it is the edge that
 * made those edits manual: <em>"every one of these bodies had to reach it through a port or a
 * listener instead, because `execution` may not name `pipeline`"</em>.</p>
 *
 * <p><strong>Every assertion here was written from a RUN, not from what the engine ought to
 * do.</strong> That is load-bearing rather than a boast: the first version of this row published
 * a third mode, {@code oldOwnerAccess=getter}, whose schema claimed it "reaches the old owner's
 * state through accessors instead, adding no parameter and no new type dependency". Measured
 * against this fixture it produced output byte-identical to {@code parameter} — same added
 * parameter, same direct field read, same widened field. {@code setUseGetters} governs access to
 * the TARGET's own fields, not the old owner's, so it had nothing to do here. The mode is gone,
 * and {@link #anUnknownModeIsRefusedWithTheRealChoices()} is what keeps it from coming back.</p>
 */
class AMovedBodyThatStillNeedsItsOldOwnerTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private MoveMethodTool tool;
    private ObjectMapper mapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("move-method");
        tool = new MoveMethodTool(() -> service,
            new org.jawata.mcp.change.RefactoringChangeCache());
        mapper = new ObjectMapper();
        projectPath = service.getProjectRoot();
    }

    @Test
    @DisplayName("by default the move is REFUSED, naming both costs it would pay silently")
    void theDefaultRefusesAndNamesWhatItWouldCost() throws Exception {
        Path pipeline = pipelinePath();
        String before = Files.readString(pipeline);

        ToolResponse r = move("dispatch(Port", null, null);
        assertFalse(r.isSuccess(), "a body that still reaches its old owner must not move silently");
        String message = String.valueOf(r.getError().getMessage());

        // The two costs, each named. Wording ONLY this branch emits: the forbiddenPackage
        // refusal says "declares forbidden" and the bad-mode refusal says "is not a choice",
        // so neither can satisfy these needles — which is what makes them discriminate rather
        // than merely prove that something declined.
        assertTrue(message.contains("cannot stand alone on Port"),
            () -> "the refusal must say the body cannot stand alone on the destination: " + message);
        assertTrue(message.contains("com.example.sink") && message.contains("depend on com.example"),
            () -> "and name the package edge it would create: " + message);
        assertTrue(message.contains("WIDEN") && message.contains("private field public"),
            () -> "and the SECOND cost, which JDT also pays silently: " + message);

        assertEquals(before, Files.readString(pipeline),
            "a refusal must leave the source byte-for-byte unchanged");
    }

    @Test
    @DisplayName("THE CONTROL: a body that needs nothing of its old owner still moves untouched")
    void aBodyThatNeedsNothingMovesWithoutBeingAsked() throws Exception {
        // This is the half that makes the test above mean something. The branch is gated on the
        // engine's own needsTargetNode(), so a tool that asked unconditionally would refuse
        // every move in the product and still pass the refusal assertions.
        ToolResponse r = move("describe(Port", null, null);
        assertTrue(r.isSuccess(),
            () -> "describe() reaches nothing of Pipeline, so no question arises: " + describe(r));
        assertTrue(Files.readString(portPath()).contains("describe("),
            "describe must have landed on Port");
    }

    @Test
    @DisplayName("oldOwnerAccess=parameter takes JDT's edge deliberately, and it compiles")
    void theParameterModeTakesTheEdgeAndCompiles() throws Exception {
        ToolResponse r = move("dispatch(Port", "parameter", null);
        assertTrue(r.isSuccess(), () -> "staging/applying failed: " + describe(r));

        // The WHOLE signature and the WHOLE body line, read off the engine's own output rather
        // than guessed. contains("dispatch") is true of the method before the move as well.
        String port = Files.readString(portPath());
        assertTrue(port.contains("public String dispatch(Pipeline pipeline, String reason)"),
            () -> "the old owner arrives as a parameter, which is the edge being accepted:\n" + port);
        assertTrue(port.contains("return id() + \":\" + reason + \":\" + pipeline.budget;"),
            () -> "and the body reads it through that parameter:\n" + port);

        CompileWorkspaceTool compile = new CompileWorkspaceTool(() -> service, () -> new org.jawata.core.workspace.StrictDiskSync(() -> service));
        ToolResponse cr = compile.execute(mapper.createObjectNode());
        @SuppressWarnings("unchecked")
        Map<String, Object> cdata = (Map<String, Object>) cr.getData();
        assertEquals(0, ((Number) cdata.get("errorCount")).intValue(),
            () -> "the move and its rewritten call must compile; diagnostics="
                + cdata.get("diagnostics"));
    }

    @Test
    @DisplayName("and the SECOND cost is real: the old owner's private field is made public")
    void theParameterModeOpensTheOldOwnersEncapsulation() throws Exception {
        assertTrue(Files.readString(pipelinePath()).contains("private int budget = 100;"),
            "precondition: the field starts private, or this measures nothing");

        assertTrue(move("dispatch(Port", "parameter", null).isSuccess(), "the move must succeed");

        // MEASURED, and it is why the refusal names two costs rather than one. The engine widens
        // whatever the moved body reads so the cross-package direct access compiles — so the move
        // opens the old owner's encapsulation, which no gate below this tool would report.
        String after = Files.readString(pipelinePath());
        assertTrue(after.contains("public int budget = 100;"),
            () -> "the field was silently widened to public, which the refusal warns about:\n"
                + after);
        assertFalse(after.contains("private int budget"),
            () -> "and it is no longer private:\n" + after);
    }

    @Test
    @DisplayName("forbiddenPackage refuses the edge BEFORE anything is written")
    void theForbiddenPackageIsCheckedOnThisToolsOwnResult() throws Exception {
        Path pipeline = pipelinePath();
        Path port = portPath();
        String pipelineBefore = Files.readString(pipeline);
        String portBefore = Files.readString(port);

        ToolResponse r = move("dispatch(Port", "parameter", "com.example");
        assertFalse(r.isSuccess(), "the move creates exactly the edge the call declares forbidden");
        String message = String.valueOf(r.getError().getMessage());
        assertTrue(message.contains("declares forbidden"),
            () -> "the refusal must say which rule it is applying: " + message);
        assertTrue(message.contains("Nothing was written"),
            () -> "and that it declined before acting: " + message);

        assertEquals(pipelineBefore, Files.readString(pipeline),
            "the owner must be byte-for-byte unchanged");
        assertEquals(portBefore, Files.readString(port),
            "and so must the destination — this is the half 'before anything is written' claims");
    }

    @Test
    @DisplayName("an unknown mode is refused even where the body reaches NOTHING — the half that was silent")
    void anUnknownModeIsRefusedOnAMoveThatAsksNoQuestion() throws Exception {
        // THE CONTROL FOR THE HOIST, and without it the fix has none. The mode used to be read
        // and validated INSIDE `if (processor.needsTargetNode())`, so on every move whose body
        // reaches nothing — which is most of them — a misspelled `oldOwnerAccess` was accepted
        // in silence and the move proceeded. That is the failure the sibling D9 change names in
        // its own comment one file away: "which is how a caller ends up believing a flag did
        // something".
        //
        // The existing unknown-mode case cannot see the fix: it drives `dispatch`, whose body
        // DOES reach the old owner, so it was refused by the old in-gate `default` arm and is
        // refused now by the pre-gate guard — passing identically before and after. A C5
        // round-2 audit named that. `describe` reaches nothing, so this case runs only through
        // the hoisted guard: before the hoist it SUCCEEDED, after it refuses.
        Path pipeline = pipelinePath();
        Path port = portPath();
        String pipelineBefore = Files.readString(pipeline);
        String portBefore = Files.readString(port);

        ToolResponse r = move("describe(Port", "sideways", null);
        assertFalse(r.isSuccess(),
            () -> "a garbage mode must be refused whatever the body reaches: " + describe(r));
        assertTrue(String.valueOf(r.getError().getMessage()).contains("is not a choice"),
            () -> "and by the mode guard rather than by something else: "
                + r.getError().getMessage());

        assertEquals(pipelineBefore, Files.readString(pipeline),
            "nothing may be written — the sibling case proves this same move SUCCEEDS when the"
                + " mode is valid, so a refusal that still moved it would be the worse half");
        assertEquals(portBefore, Files.readString(port),
            "and the destination must be untouched too");
    }

    @Test
    @DisplayName("an unknown mode is refused, and the choices it lists are the ones that WORK")
    void anUnknownModeIsRefusedWithTheRealChoices() throws Exception {
        ToolResponse r = move("dispatch(Port", "sideways", null);
        assertFalse(r.isSuccess(), "a misspelled mode must not fall through to a default");
        String message = String.valueOf(r.getError().getMessage());
        assertTrue(message.contains("refuse") && message.contains("parameter"),
            () -> "the refusal must list the modes that exist: " + message);

        // THE REGRESSION GUARD for the mode that was removed. `getter` was published, measured
        // byte-identical to `parameter`, and deleted; a published choice that does not differ
        // from another choice is a false statement in the schema. If it returns, this fails.
        assertFalse(message.contains("getter"),
            () -> "getter was measured to be a no-op and removed; it must not be offered: "
                + message);
    }

    private Path pipelinePath() {
        return projectPath.resolve("src/main/java/com/example/Pipeline.java");
    }

    private Path portPath() {
        return projectPath.resolve("src/main/java/com/example/sink/Port.java");
    }

    private ToolResponse move(String methodNeedle, String oldOwnerAccess, String forbiddenPackage)
            throws Exception {
        Path pipeline = pipelinePath();
        int[] pos = posOf(Files.readString(pipeline), methodNeedle);
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", pipeline.toString());
        args.put("line", pos[0]);
        args.put("column", pos[1]);
        args.put("target", "port");
        if (oldOwnerAccess != null) {
            args.put("oldOwnerAccess", oldOwnerAccess);
        }
        if (forbiddenPackage != null) {
            args.put("forbiddenPackage", forbiddenPackage);
        }
        return tool.execute(args);
    }

    /** Zero-based [line, column] of the first occurrence of {@code needle} in {@code text}. */
    private static int[] posOf(String text, String needle) {
        int idx = text.indexOf(needle);
        int line = 0;
        int col = 0;
        for (int i = 0; i < idx; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }
        return new int[]{line, col};
    }

    private static String describe(ToolResponse r) {
        return r.getError() == null ? "(no error)"
            : r.getError().getReason() + " / " + r.getError().getMessage();
    }
}
