package org.jawata.mcp.tools.refactoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.CompileWorkspaceTool;
import org.jawata.mcp.tools.DeleteAnonymousImplementationTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28g <b>D10</b> — {@code delete_anonymous_implementation}.
 *
 * <p>The census counts <strong>17</strong> hand edits of this shape across Sprint 8, six in
 * production and eleven in tests, against <em>no tool at all</em>: <em>"no tool removes an
 * anonymous implementation and makes its target implement the interface instead (the
 * stage-1/2/3 shape, done by hand each time)"</em>. It is the largest single untooled shape the
 * sprint produced, and D10's measure is that it <em>"performs the case from that sprint, staged
 * and reversible, and refuses where the anonymous body is not a plain forwarder"</em>.</p>
 *
 * <p>Five of the seven cases here are refusals, and that is the subject rather than
 * defensiveness: the operation deletes bodies instead of moving them, so "is this a plain
 * forwarder" is not a convenience check — it is the precondition that makes the deletion
 * equivalent to the original. Each refusal asserts its reason CODE, because a message substring
 * is shared by neighbouring branches and proves only that something declined.</p>
 */
class AForwardingAnonymousClassIsDeletedTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private DeleteAnonymousImplementationTool tool;
    private org.jawata.mcp.tools.RefactoringTool lifecycle;
    private ObjectMapper mapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("dead-api");
        // The cache is SHARED with the lifecycle door, which is what makes a staged change
        // findable by its changeId — a fresh cache per tool would lose it between the two.
        org.jawata.mcp.refactoring.RefactoringChangeCache cache =
            new org.jawata.mcp.refactoring.RefactoringChangeCache();
        tool = new DeleteAnonymousImplementationTool(() -> service, cache);
        lifecycle = new org.jawata.mcp.tools.RefactoringTool(() -> service, cache);
        mapper = new ObjectMapper();
        projectPath = service.getProjectRoot();
    }

    @Test
    @DisplayName("the wrapper goes, the manager gains the interface, and it all still compiles")
    void theWrapperIsDeletedAndTheTargetImplementsTheInterface() throws Exception {
        assertFalse(Files.readString(managerPath()).contains("implements SlotListener"),
            "precondition: the target does NOT declare the interface yet, or this measures nothing");
        int wrappersBefore = creations(Files.readString(strategyPath()));

        ToolResponse r = deleteAt("wrapper()");
        assertTrue(r.isSuccess(), () -> "the deletion failed: " + describe(r));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("undoChangeId"), "D10's measure says staged and REVERSIBLE");
        assertEquals(true, data.get("targetGainedTheInterface"), () -> "" + data);
        assertEquals("com.example.SlotManager", data.get("target"), () -> "" + data);

        // THE SECOND EDIT, in the other file — the half a hand edit forgets.
        String manager = Files.readString(managerPath());
        assertTrue(manager.contains("implements SlotListener"),
            () -> "SlotManager must declare the interface:\n" + manager);
        // AND THE IMPORT IT NEEDS. The interface is in com.example.port deliberately: beside the
        // target no import is required and ImportRewrite does nothing, so this claim would have
        // been exercised by nothing — the shape this sprint already caught twice. Without it the
        // file does not compile, which the compile assertion below would also catch; asserted
        // directly as well, because a compile failure names the symbol and not the cause.
        assertTrue(manager.contains("import com.example.port.SlotListener;"),
            () -> "the promotion must bring the import with it:\n" + manager);

        // THE FIRST EDIT: the creation expression is replaced by the receiver. Asserted on the
        // ONE method's text, because a whole-file check cannot say "gone from HERE" — five other
        // wrappers in this fixture still contain `new SlotListener()` and must.
        String strategy = Files.readString(strategyPath());
        String body = bodyOf(strategy, "public SlotListener wrapper()");
        assertTrue(body.contains("return manager;"),
            () -> "the creation must become the receiver:\n" + body);
        assertFalse(body.contains("new SlotListener()"),
            () -> "and the anonymous class must be gone from it:\n" + body);
        // A COUNT, NOT A CONTAINMENT. `contains` proved that ONE of the siblings survived, not
        // that all of them did — a C5 audit named it, and the compile assertion below only
        // catches gross damage. A rewrite that caught a sibling now fails by number.
        //
        // DERIVED FROM THE FIXTURE, not written down. A first version asserted the literal 6,
        // which a C5 round-2 audit pointed out was a fresh hand-written count added inside the
        // commit whose whole subject is hand-written counts going stale: the eighth wrapper
        // anyone adds to this fixture would re-open that line. The before-count is already in
        // hand, so the claim is exactly "one fewer than there were".
        assertEquals(wrappersBefore - 1, creations(strategy),
            () -> "the OTHER wrappers must be untouched — this operation acts on the one it was"
                + " given, and the fixture's remaining " + (wrappersBefore - 1)
                + " must still construct:\n" + strategy);

        CompileWorkspaceTool compile = new CompileWorkspaceTool(() -> service);
        @SuppressWarnings("unchecked")
        Map<String, Object> cdata =
            (Map<String, Object>) compile.execute(mapper.createObjectNode()).getData();
        assertEquals(0, ((Number) cdata.get("errorCount")).intValue(),
            () -> "the promoted target and the rewritten call must compile; diagnostics="
                + cdata.get("diagnostics"));
    }

    @Test
    @DisplayName("it STAGES: nothing is written until the changeId is applied")
    void stagesTheChangeAndAppliesItOnDemand() throws Exception {
        // C5's exit says the new tool is "staged and reversible". Only the REVERSIBLE half was
        // asserted — a non-null undoChangeId — and a C5 audit named the gap: no test drove this
        // tool with auto_apply=false at all, so "staged" was a word in the criterion with
        // nothing behind it. The two halves are different claims: an undo handle says a change
        // can be taken back AFTER it landed, staging says it had not landed yet.
        String strategyBefore = Files.readString(strategyPath());
        String managerBefore = Files.readString(managerPath());

        ToolResponse r = deleteAt("wrapper()", false);
        assertTrue(r.isSuccess(), () -> "staging failed: " + describe(r));

        assertEquals(strategyBefore, Files.readString(strategyPath()),
            "STAGING must not write — the creation expression is still there");
        assertEquals(managerBefore, Files.readString(managerPath()),
            "and must not write the OTHER file either: this operation edits two, so a staging"
                + " that held one back and promoted the target anyway would be the worse half");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        Object changeId = data.get("changeId");
        assertNotNull(changeId, () -> "a staged change must be retrievable by id: " + data);

        ObjectNode apply = mapper.createObjectNode();
        apply.put("action", "apply");
        apply.put("changeId", String.valueOf(changeId));
        assertTrue(lifecycle.execute(apply).isSuccess(),
            "the staged change must be findable in the shared cache and applicable by its id");

        // AND IT WAS THE REAL CHANGE, not an empty one that staged and applied cleanly.
        assertTrue(Files.readString(managerPath()).contains("implements SlotListener"),
            "applying the staged change must do what the immediate path does");
    }

    @Test
    @DisplayName("a body that does more than forward is refused — its work lives nowhere else")
    void aBodyThatDoesWorkIsRefused() throws Exception {
        ToolResponse r = deleteAt("wrapperThatDoesWork()");
        assertFalse(r.isSuccess(), "deleting that body would lose the println");
        assertEquals("BODY_IS_NOT_A_PLAIN_FORWARD", r.getError().getReason(), () -> describe(r));
        assertTrue(String.valueOf(r.getError().getMessage()).contains("onCancel"),
            () -> "the refusal must name the method it saw: " + r.getError().getMessage());
    }

    @Test
    @DisplayName("a transformed argument is refused — that is an adapter, not a wrapper")
    void aTransformedArgumentIsRefused() throws Exception {
        ToolResponse r = deleteAt("wrapperThatTransforms()");
        assertFalse(r.isSuccess(), "order.trim() is not the parameter passed through");
        assertEquals("ARGUMENTS_ARE_NOT_PASSED_THROUGH", r.getError().getReason(),
            () -> describe(r));
        assertTrue(String.valueOf(r.getError().getMessage()).contains("order.trim()"),
            () -> "and quote what it passes instead: " + r.getError().getMessage());
    }

    @Test
    @DisplayName("two receivers are refused — promoting either drops the other's calls")
    void twoReceiversAreRefused() throws Exception {
        ToolResponse r = deleteAt("wrapperWithTwoReceivers()");
        assertFalse(r.isSuccess(), "there is no single target");
        assertEquals("RECEIVERS_DISAGREE", r.getError().getReason(), () -> describe(r));
        String message = String.valueOf(r.getError().getMessage());
        // THE SECOND NEEDLE USED TO BE "other" AND COULD NOT FAIL: the refusal's template ends
        // "promoting either would silently drop the other's calls", so the word is present
        // whatever the receivers are called. The fixture's second receiver is now `standby`,
        // which only the receiver list can supply — a C5 audit found this.
        assertTrue(message.contains("manager") && message.contains("standby"),
            () -> "the refusal must name BOTH receivers: " + message);
    }

    @Test
    @DisplayName("a renamed forward is refused — the target does not answer what the interface asks")
    void aRenamedForwardIsRefused() throws Exception {
        // THE BRANCH THIS CHECKPOINT MADE UNREACHABLE. The receiver check was moved ahead of
        // the name check — right, because the implicit-receiver fixture is necessarily also a
        // rename and was answering for the wrong branch — but that fixture was the only route
        // here, and a C5 audit measured the result: zero files in the test tree mentioned
        // FORWARD_RENAMES_THE_METHOD. `wrapperThatRenames` reaches it with an explicit
        // variable receiver, so every earlier precondition passes and only the name differs.
        ToolResponse r = deleteAt("wrapperThatRenames()");
        assertFalse(r.isSuccess(), "promoting the target would make onFill call onCancel");
        assertEquals("FORWARD_RENAMES_THE_METHOD", r.getError().getReason(), () -> describe(r));
        String message = String.valueOf(r.getError().getMessage());
        assertTrue(message.contains("onFill") && message.contains("onCancel"),
            () -> "the refusal must name BOTH the method asked for and the one forwarded to,"
                + " or a reader cannot see what the mismatch is: " + message);
    }

    @Test
    @DisplayName("an implicit receiver is refused — it forwards to the enclosing instance")
    void anImplicitReceiverIsRefused() throws Exception {
        ToolResponse r = deleteAt("wrapperOnItself()");
        assertFalse(r.isSuccess(), "that is a different edit on a different declaration");
        assertEquals("RECEIVER_IS_IMPLICIT", r.getError().getReason(), () -> describe(r));
    }

    @Test
    @DisplayName("a wrapper that owns state is refused — it is not a wrapper")
    void aWrapperWithStateIsRefused() throws Exception {
        ToolResponse r = deleteAt("wrapperWithState()");
        assertFalse(r.isSuccess(), "the field is state nothing else holds");
        assertEquals("BODY_IS_NOT_ONLY_METHODS", r.getError().getReason(), () -> describe(r));
    }

    @Test
    @DisplayName("the position must name an anonymous creation, not any old expression")
    void aPlainExpressionIsRefused() throws Exception {
        ToolResponse r = deleteAt("public String managerLog()");
        assertFalse(r.isSuccess(), "a plain method is not an anonymous implementation");
        assertTrue(String.valueOf(r.getError().getMessage()).contains("No anonymous class"),
            () -> "the refusal should say what it was looking for: " + r.getError().getMessage());
    }

    /** How many anonymous {@code SlotListener}s the fixture still constructs. */
    private static int creations(String source) {
        return source.split("new SlotListener\\(\\)", -1).length - 1;
    }

    private Path strategyPath() {
        return projectPath.resolve("src/main/java/com/example/Strategy.java");
    }

    private Path managerPath() {
        return projectPath.resolve("src/main/java/com/example/SlotManager.java");
    }

    /**
     * Drive the tool at the {@code new SlotListener()} that belongs to the named method — the
     * FIRST one at or after the method's own signature, because six of them live in one file and
     * a needle on the creation alone would always resolve to the first wrapper.
     */
    private ToolResponse deleteAt(String methodNeedle) throws Exception {
        return deleteAt(methodNeedle, true);
    }

    /** As above, but staging rather than applying when {@code autoApply} is false. */
    private ToolResponse deleteAt(String methodNeedle, boolean autoApply) throws Exception {
        String source = Files.readString(strategyPath());
        int from = source.indexOf(methodNeedle);
        if (from < 0) {
            throw new AssertionError("method not found: " + methodNeedle);
        }
        int at = source.indexOf("new SlotListener()", from);
        // The last case points at a method with no creation in it at all, which is the point of
        // that case: fall back to the method's own position so the tool is asked about it.
        int target = at < 0 ? from : at;
        int[] pos = lineColOf(source, target);
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", strategyPath().toString());
        args.put("line", pos[0]);
        args.put("column", pos[1]);
        if (!autoApply) {
            args.put("auto_apply", false);
        }
        return tool.execute(args);
    }

    private static int[] lineColOf(String text, int index) {
        int line = 0;
        int col = 0;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }
        return new int[]{line, col};
    }

    /** One method's text, from its signature to its closing brace. */
    private static String bodyOf(String source, String signature) {
        int from = source.indexOf(signature);
        if (from < 0) {
            throw new AssertionError("method not found: " + signature);
        }
        int depth = 0;
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(from, i + 1);
                }
            }
        }
        return source.substring(from);
    }

    private static String describe(ToolResponse r) {
        return r.getError() == null ? "(no error)"
            : r.getError().getReason() + " / " + r.getError().getMessage();
    }
}
