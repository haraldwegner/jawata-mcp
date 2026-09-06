package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.HierarchyTool;
import org.jawata.mcp.tools.inheritance.ReplaceSuperclassWithDelegateTool;
import org.junit.jupiter.api.Assertions;
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
 * Stage 7, row 57 — Replace Superclass with Delegate, as
 * {@code hierarchy direction=replace_superclass_with_delegate}.
 *
 * <p>This is the cure {@code composition_over_inheritance} names in its own findings, and the
 * one row in this stage whose safety argument is entirely CROSS-FILE: deleting {@code extends}
 * removes substitutability, so the question is not what the class does but what everything ELSE
 * assumes about it. Its refusal fixture therefore lives in a second file.</p>
 */
class ReplaceSuperclassWithDelegateToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private HierarchyTool tool;
    private org.jawata.mcp.tools.RefactoringTool lifecycle;
    private Path pkg;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        org.jawata.mcp.refactoring.RefactoringChangeCache cache =
            new org.jawata.mcp.refactoring.RefactoringChangeCache();
        tool = new HierarchyTool(() -> service, cache);
        lifecycle = new org.jawata.mcp.tools.RefactoringTool(() -> service, cache);
        pkg = service.getProjectRoot().resolve("src/main/java/com/example");
    }

    private ObjectNode argsFor(String type) {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("direction", "replace_superclass_with_delegate");
        args.put("typeName", "com.example." + type);
        return args;
    }

    private String read(String file) throws Exception {
        return Files.readString(pkg.resolve(file), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the extends goes, a delegate field takes its place, and the inherited call is "
        + "forwarded through it")
    void replacesTheSuperclassWithADelegate() throws Exception {
        ToolResponse r = tool.execute(argsFor("Petty"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = read("Petty.java");
        Assertions.assertAll(
            () -> assertFalse(after.contains("extends Ledgering"),
                "the false is-a must go — that is the refactoring:\n" + after),
            () -> assertTrue(after.contains("private final Ledgering ledgering;"),
                "and the reuse must survive, as a field:\n" + after),
            () -> assertTrue(after.contains("this.ledgering = new Ledgering(book);"),
                "whatever super() was given, the delegate is given — otherwise the object is"
                    + " built differently than before:\n" + after),
            () -> assertFalse(after.contains("super(book);"),
                "and the super call is GONE, not left beside the assignment:\n" + after),
            () -> assertTrue(after.contains("ledgering.posting(what)"),
                "the inherited call no longer resolves by inheritance, so it must be forwarded"
                    + " through the field or the class does not compile:\n" + after),
            () -> assertTrue(after.contains("this.float0 = float0;"),
                "while the class's OWN state is untouched:\n" + after));
    }

    @Test
    @DisplayName("the response reports what it rewired and how many references it read")
    void reportsWhatItChecked() throws Exception {
        ToolResponse r = tool.execute(argsFor("Petty"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        java.util.Map<?, ?> data = (java.util.Map<?, ?>) r.getData();
        Assertions.assertAll(
            () -> assertEquals("Ledgering", data.get("superclass"), "" + data),
            () -> assertEquals("ledgering", data.get("fieldName"),
                "the default name is the superclass's own, uncapitalised: " + data),
            () -> assertEquals(1, data.get("constructorsRewired"), "" + data),
            () -> assertEquals(1, data.get("inheritedUsesForwarded"),
                "Petty makes exactly one inherited call; a count that drifts means the forward"
                    + " missed one or invented one: " + data),
            () -> assertNotNull(data.get("referencesChecked"),
                "it must say how many references it read, because THAT is the safety argument"
                    + " and a reader cannot otherwise tell it looked: " + data));
    }

    @Test
    @DisplayName("REFUSES when another file uses an instance AS its superclass")
    void refusesWhenSomethingSubstitutesIt() throws Exception {
        String before = read("Reconciled.java");
        ToolResponse r = tool.execute(argsFor("Reconciled"));

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(),
                "ReconciledDesk assigns one to a Ledgering-typed variable, and deleting extends"
                    + " is exactly what takes that away"),
            () -> assertEquals(ReplaceSuperclassWithDelegateTool.Refusal.USED_AS_ITS_SUPERCLASS,
                r.getError().getReason(), "got: " + r.getError()),
            () -> assertTrue(String.valueOf(r.getError()).contains("ReconciledDesk"),
                "and it must NAME the file, because the caller has to go and look at it: "
                    + r.getError()),
            () -> assertEquals(before, read("Reconciled.java"), "a refusal modifies nothing"));
    }

    @Test
    @DisplayName("REFUSES a class that OVERRIDES an inherited method")
    void refusesAnOverride() throws Exception {
        ToolResponse r = tool.execute(argsFor("Overriding"));

        assertFalse(r.isSuccess(), "a caller holding the superclass and calling the overridden"
            + " method is depending on dynamic dispatch, which delegation does not give");
        assertEquals(ReplaceSuperclassWithDelegateTool.Refusal.OVERRIDES_INHERITED_METHOD,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a class that extends nothing but Object")
    void refusesWithoutASuperclass() throws Exception {
        ToolResponse r = tool.execute(argsFor("Ledgering"));

        assertFalse(r.isSuccess(), "there is no superclass to replace");
        assertEquals(ReplaceSuperclassWithDelegateTool.Refusal.NO_SUPERCLASS,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("auto_apply=false STAGES: the file is untouched until the change is applied")
    void stagesTheChangeAndAppliesItOnDemand() throws Exception {
        String before = read("Petty.java");
        ObjectNode args = argsFor("Petty");
        args.put("auto_apply", false);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertEquals(before, read("Petty.java"), "STAGING must not write");

        Object changeId = ((java.util.Map<?, ?>) r.getData()).get("changeId");
        assertNotNull(changeId, "the response carries no changeId: " + r.getData());
        ObjectNode apply = new ObjectMapper().createObjectNode();
        apply.put("action", "apply");
        apply.put("changeId", String.valueOf(changeId));
        assertTrue(lifecycle.execute(apply).isSuccess());
        assertTrue(read("Petty.java").contains("private final Ledgering ledgering;"),
            "and the staged change must be the real one");
    }

    @Test
    @DisplayName("the undo handle RESTORES the file")
    void undoRestoresTheFile() throws Exception {
        String before = read("Petty.java");
        ToolResponse r = tool.execute(argsFor("Petty"));
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertFalse(before.equals(read("Petty.java")),
            "the CONTROL: with nothing changed, an undo that restores nothing would pass");

        Object handle = ((java.util.Map<?, ?>) r.getData()).get("undoChangeId");
        assertNotNull(handle, "the response carries no undoChangeId: " + r.getData());
        ObjectNode undo = new ObjectMapper().createObjectNode();
        undo.put("action", "undo");
        undo.put("undoChangeId", String.valueOf(handle));
        assertTrue(lifecycle.execute(undo).isSuccess());
        assertEquals(before, read("Petty.java"), "the file is restored");
    }

    @Test
    @DisplayName("the door routes the kind")
    void theDoorRoutesIt() {
        assertTrue(tool.publishedKinds().contains("replace_superclass_with_delegate"),
            "a kind that is dispatched and not published is invisible in tools/list");
        assertTrue(tool.delegates().get("replace_superclass_with_delegate")
                instanceof ReplaceSuperclassWithDelegateTool,
            "and the routing table must reach this delegate");
    }
}
