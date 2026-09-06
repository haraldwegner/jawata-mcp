package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.HierarchyTool;
import org.jawata.mcp.tools.inheritance.ReplaceSubclassWithDelegateTool;
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
 * Stage 7, row 56 — Replace Subclass with Delegate, as
 * {@code hierarchy direction=replace_subclass_with_delegate}.
 *
 * <p>It is row 57's COMPLEMENT — that row is for a subclass that overrides nothing, this one for
 * a subclass whose whole point is what it overrides — and one of the assertions below is that
 * each refusal names the other row rather than leaving a caller to work out which they have.</p>
 */
class ReplaceSubclassWithDelegateToolTest {

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
        args.put("direction", "replace_subclass_with_delegate");
        args.put("typeName", "com.example." + type);
        return args;
    }

    private String read(String file) throws Exception {
        Path p = pkg.resolve(file);
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "(absent)";
    }

    @Test
    @DisplayName("the overriding method moves into a generated behaviour class, verbatim and "
        + "without its @Override")
    void generatesTheBehaviourClass() throws Exception {
        ToolResponse r = tool.execute(argsFor("Zeroed"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String behaviour = read("ZeroedBehaviour.java");
        Assertions.assertAll(
            () -> assertTrue(behaviour.contains("public class ZeroedBehaviour {"),
                "the default name is <Subclass>Behaviour:\n" + behaviour),
            () -> assertTrue(behaviour.contains("return \"zero\";"),
                "carrying the subclass's OWN body — copied rather than regenerated, because"
                    + " nothing downstream resolves a created file:\n" + behaviour),
            () -> assertFalse(behaviour.contains("@Override"),
                "and WITHOUT the annotation: the delegate overrides nothing, so an @Override"
                    + " there would not compile — which the gate would not tell us:\n"
                    + behaviour),
            () -> assertTrue(behaviour.contains("package com.example;"),
                "in the subclass's own package:\n" + behaviour));
    }

    @Test
    @DisplayName("the subclass is left alone, and the response says what remains to be done")
    void leavesTheSubclassAndSaysSo() throws Exception {
        String before = read("Zeroed.java");
        ToolResponse r = tool.execute(argsFor("Zeroed"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        java.util.Map<?, ?> data = (java.util.Map<?, ?>) r.getData();
        Assertions.assertAll(
            () -> assertEquals(before, read("Zeroed.java"),
                "adding the field, routing through it and deleting the subclass are three steps"
                    + " that must happen together — this row does none of them"),
            () -> assertTrue(String.valueOf(data.get("note")).contains("still extends"),
                "and the response must SAY the subclass survives, or a reader assumes the"
                    + " refactoring finished: " + data),
            () -> assertEquals(java.util.List.of("rate"), data.get("methodsMoved"),
                "naming exactly what moved: " + data));
    }

    @Test
    @DisplayName("REFUSES a subclass that overrides nothing, and NAMES the row that fits it")
    void refusesASubclassThatOverridesNothing() throws Exception {
        ToolResponse r = tool.execute(argsFor("Reusing"));

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(), "there is no varying behaviour to move"),
            () -> assertEquals(ReplaceSubclassWithDelegateTool.Refusal.NOTHING_OVERRIDDEN,
                r.getError().getReason(), "got: " + r.getError()),
            () -> assertTrue(String.valueOf(r.getError())
                    .contains("replace_superclass_with_delegate"),
                "the two rows are complements, so a refusal here should hand the caller its"
                    + " sibling rather than leave them to work out which case they have: "
                    + r.getError()),
            () -> assertEquals("(absent)", read("ReusingBehaviour.java"),
                "a refusal creates nothing"));
    }

    @Test
    @DisplayName("REFUSES a subclass that declares its own state")
    void refusesASubclassWithState() throws Exception {
        ToolResponse r = tool.execute(argsFor("Stateful"));

        assertFalse(r.isSuccess(), "the state would have to travel with the behaviour, and what"
            + " the delegate is constructed with is a seam a human chooses");
        assertEquals(ReplaceSubclassWithDelegateTool.Refusal.DECLARES_ITS_OWN_STATE,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a class that is not a subclass at all")
    void refusesWithoutASuperclass() throws Exception {
        ToolResponse r = tool.execute(argsFor("Levying"));

        assertFalse(r.isSuccess(), "there is no subclass to replace");
        assertEquals(ReplaceSubclassWithDelegateTool.Refusal.NO_SUPERCLASS,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("auto_apply=false STAGES: no file appears until the change is applied")
    void stagesTheChangeAndAppliesItOnDemand() throws Exception {
        ObjectNode args = argsFor("Zeroed");
        args.put("auto_apply", false);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertEquals("(absent)", read("ZeroedBehaviour.java"), "STAGING must not create files");

        Object changeId = ((java.util.Map<?, ?>) r.getData()).get("changeId");
        assertNotNull(changeId, "the response carries no changeId: " + r.getData());
        ObjectNode apply = new ObjectMapper().createObjectNode();
        apply.put("action", "apply");
        apply.put("changeId", String.valueOf(changeId));
        assertTrue(lifecycle.execute(apply).isSuccess());
        assertTrue(read("ZeroedBehaviour.java").contains("return \"zero\";"),
            "and the staged change must be the real one");
    }

    @Test
    @DisplayName("the undo handle DELETES the generated file")
    void undoDeletesWhatItCreated() throws Exception {
        ToolResponse r = tool.execute(argsFor("Zeroed"));
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertTrue(read("ZeroedBehaviour.java").contains("class ZeroedBehaviour"),
            "the CONTROL: with nothing created, an undo that deletes nothing would pass");

        Object handle = ((java.util.Map<?, ?>) r.getData()).get("undoChangeId");
        assertNotNull(handle, "the response carries no undoChangeId: " + r.getData());
        ObjectNode undo = new ObjectMapper().createObjectNode();
        undo.put("action", "undo");
        undo.put("undoChangeId", String.valueOf(handle));
        assertTrue(lifecycle.execute(undo).isSuccess());
        assertEquals("(absent)", read("ZeroedBehaviour.java"),
            "an undo of a CREATE is a delete");
    }

    @Test
    @DisplayName("the door routes the kind")
    void theDoorRoutesIt() {
        assertTrue(tool.publishedKinds().contains("replace_subclass_with_delegate"),
            "a kind that is dispatched and not published is invisible in tools/list");
        assertTrue(tool.delegates().get("replace_subclass_with_delegate")
                instanceof ReplaceSubclassWithDelegateTool,
            "and the routing table must reach this delegate");
    }
}
