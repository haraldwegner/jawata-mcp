package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.FindRefsTool;
import org.jawata.mcp.tools.MoveTool;
import org.jawata.mcp.tools.RenameSymbolTool;
import org.jawata.mcp.tools.shared.ResolveOrRelocate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 (D2) — stale memory repairs itself. The human loop is: go where you
 * think it is; on a miss, search; when you find it elsewhere, REPLACE the stale
 * memory. An agent gets that whole loop in ONE call: a name that no longer
 * resolves is answered with the correction, explicitly flagged.
 *
 * <p>The correction is never ACTED on — silently retargeting a refactoring at a
 * symbol the caller did not name would be worse than failing. The agent
 * re-issues once, and its memory is now right.</p>
 */
class ResolveOrRelocateTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private ObjectMapper om;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        om = new ObjectMapper();
    }

    private ObjectNode refsBySymbol(String fqn) {
        ObjectNode args = om.createObjectNode();
        args.put("kind", "references");
        args.put("symbol", fqn);
        return args;
    }

    @Test
    @DisplayName("a MOVED class: the old name answers with its new location, flagged as a correction")
    void movedClass_correctsInOneCall() {
        // The agent's memory says com.example.Calculator. Reality moves on.
        MoveTool move = new MoveTool(() -> service, cache);
        ObjectNode moveArgs = om.createObjectNode();
        moveArgs.put("kind", "class");
        moveArgs.put("typeName", "com.example.Calculator");
        moveArgs.put("targetPackage", "com.example.math");
        ToolResponse moved = move.execute(moveArgs);
        assertTrue(moved.isSuccess(), "the move must land: " + moved.getError());

        // Now the stale memory is used. ONE call: not-there + found-here.
        FindRefsTool refs = new FindRefsTool(() -> service);
        ToolResponse r = refs.execute(refsBySymbol("com.example.Calculator"));

        assertFalse(r.isSuccess(), "a stale name must NOT silently resolve to something else");
        assertEquals(ResolveOrRelocate.RELOCATED, r.getError().getCode(),
            "flagged as a relocation, not a plain not-found: " + r.getError());
        assertTrue(r.getError().getMessage().contains("com.example.math.Calculator"),
            "the correction names the NEW location: " + r.getError().getMessage());
        assertTrue(String.valueOf(r.getError().getHint()).contains("com.example.math.Calculator"),
            "the hint tells the agent what to remember: " + r.getError().getHint());

        // The corrected name works immediately — one hop, memory repaired.
        ToolResponse retry = refs.execute(refsBySymbol("com.example.math.Calculator"));
        assertTrue(retry.isSuccess(), "the corrected name resolves: " + retry.getError());
    }

    @Test
    @DisplayName("a RENAMED member: the old name answers with the type's real members")
    void renamedMember_namesTheSiblings() {
        RenameSymbolTool rename = new RenameSymbolTool(() -> service, cache);
        ObjectNode renameArgs = om.createObjectNode();
        renameArgs.put("symbol", "com.example.Calculator#multiply");
        renameArgs.put("newName", "times");
        assertTrue(rename.execute(renameArgs).isSuccess(), "the rename must land");

        FindRefsTool refs = new FindRefsTool(() -> service);
        ToolResponse r = refs.execute(refsBySymbol("com.example.Calculator#multiply"));

        assertFalse(r.isSuccess());
        assertEquals(ResolveOrRelocate.RELOCATED, r.getError().getCode(),
            "a renamed member is a relocation too: " + r.getError());
        String all = r.getError().getMessage() + " " + r.getError().getHint();
        assertTrue(all.contains("com.example.Calculator#times"),
            "the renamed member is offered: " + all);
    }

    @Test
    @DisplayName("a name that is GONE, not moved: an honest not-found, no invented correction")
    void genuinelyAbsent_saysSo() {
        FindRefsTool refs = new FindRefsTool(() -> service);
        ToolResponse r = refs.execute(refsBySymbol("com.example.NeverExistedAnywhere"));

        assertFalse(r.isSuccess());
        assertEquals("SYMBOL_NOT_FOUND", r.getError().getCode(),
            "nothing similar exists — do NOT dress this up as a relocation: " + r.getError());
        assertTrue(r.getError().getMessage().contains("gone, not moved"),
            "says so plainly: " + r.getError().getMessage());
    }

    @Test
    @DisplayName("the relocation search itself ranks the moved type first")
    void relocateRanksTheMovedType() {
        MoveTool move = new MoveTool(() -> service, cache);
        ObjectNode moveArgs = om.createObjectNode();
        moveArgs.put("kind", "class");
        moveArgs.put("typeName", "com.example.Calculator");
        moveArgs.put("targetPackage", "com.example.math");
        assertTrue(move.execute(moveArgs).isSuccess());

        // The relocation search reads the JDT INDEX, which a tool call refreshes on
        // dispatch. In production relocate() only ever runs inside such a call; here
        // we make one first, exactly as the caller would.
        FindRefsTool refs = new FindRefsTool(() -> service);
        assertTrue(refs.execute(refsBySymbol("com.example.math.Calculator")).isSuccess(),
            "the moved class resolves under its new name");

        List<String> candidates = ResolveOrRelocate.relocate(service, "com.example.Calculator");
        assertFalse(candidates.isEmpty(), "the index knows where it went");
        assertEquals("com.example.math.Calculator", candidates.get(0), "got: " + candidates);

        // A member suffix survives the move when the member is really there.
        List<String> withMember =
            ResolveOrRelocate.relocate(service, "com.example.Calculator#add");
        assertEquals("com.example.math.Calculator#add", withMember.get(0), "got: " + withMember);
    }
}
