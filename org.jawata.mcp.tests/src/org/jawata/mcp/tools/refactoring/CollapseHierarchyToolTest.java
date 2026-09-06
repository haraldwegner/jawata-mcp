package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.HierarchyTool;
import org.jawata.mcp.tools.inheritance.CollapseHierarchyTool;
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
 * Stage 7, row 4 — Collapse Hierarchy, as {@code hierarchy direction=collapse_hierarchy}.
 *
 * <p>It performs the case {@code inline kind=subclass} refuses, so the pair of refusals is
 * itself under test: each must hand the caller the other row by name rather than leaving them
 * to work out which case they have.</p>
 */
class CollapseHierarchyToolTest {

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
        args.put("direction", "collapse_hierarchy");
        args.put("typeName", "com.example." + type);
        return args;
    }

    private String read(String file) throws Exception {
        Path p = pkg.resolve(file);
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "(absent)";
    }

    @Test
    @DisplayName("the level is collapsed: members move up, both subtypes reparent, the file goes")
    void collapsesTheLevel() throws Exception {
        ToolResponse r = tool.execute(argsFor("TierBanded"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String parent = read("TierBase.java");
        Assertions.assertAll(
            () -> assertTrue(parent.contains("public int band()"),
                "the member moved up into the parent:\n" + parent),
            () -> assertEquals("(absent)", read("TierBanded.java"),
                "and the collapsed level's file is gone"),
            () -> assertTrue(read("TierGold.java").contains("extends TierBase"),
                "the first subtype now extends the grandparent DIRECTLY — this is the half"
                    + " that makes it Collapse Hierarchy rather than Remove Subclass:\n"
                    + read("TierGold.java")),
            () -> assertTrue(read("TierSilver.java").contains("extends TierBase"),
                "and so does the second, so the reparenting is about the SET rather than"
                    + " whichever file the sweep happened to visit first:\n"
                    + read("TierSilver.java")),
            () -> assertTrue(read("TierDesk.java").contains("TierBase banded"),
                "an ordinary user of the type is repointed too:\n" + read("TierDesk.java")));
    }

    @Test
    @DisplayName("the response names what moved, what reparented, and what it cost")
    void theResponseSaysWhatItDid() throws Exception {
        ToolResponse r = tool.execute(argsFor("TierBanded"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        java.util.Map<?, ?> data = (java.util.Map<?, ?>) r.getData();
        Assertions.assertAll(
            () -> assertEquals(java.util.List.of("band"), data.get("membersMovedUp"),
                "naming exactly what the parent's API grew by: " + data),
            () -> assertEquals(java.util.List.of("TierGold", "TierSilver"),
                data.get("subtypesReparented"),
                "and exactly which subtypes were moved: " + data),
            () -> assertTrue(String.valueOf(data.get("note")).contains("price of the collapse"),
                "the widening of the parent's API is the price, and a reader should meet it"
                    + " in the response rather than discover it: " + data));
    }

    @Test
    @DisplayName("REFUSES a leaf, and NAMES the row that fits it")
    void refusesALeaf() throws Exception {
        ToolResponse r = tool.execute(argsFor("TierLeaf"));

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(), "nothing would be reparented"),
            () -> assertEquals(CollapseHierarchyTool.Refusal.NO_SUBTYPES,
                r.getError().getReason(), "got: " + r.getError()),
            () -> assertTrue(String.valueOf(r.getError()).contains("inline kind=subclass"),
                "the two rows partition on this precondition, so the refusal must hand the"
                    + " caller its sibling: " + r.getError()),
            () -> assertTrue(read("TierLeaf.java").contains("class TierLeaf"),
                "a refusal changes nothing"));
    }

    @Test
    @DisplayName("REFUSES a level that overrides its parent — the override is the distinction")
    void refusesAnOverridingLevel() throws Exception {
        ToolResponse r = tool.execute(argsFor("TierOverriding"));

        assertFalse(r.isSuccess(), "collapsing it would change what every subtype dispatches");
        assertEquals(CollapseHierarchyTool.Refusal.OVERRIDES_PARENT,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a level whose type is observed by an instanceof")
    void refusesAnObservedLevel() throws Exception {
        ToolResponse r = tool.execute(argsFor("TierObserved"));

        assertFalse(r.isSuccess(), "something depends on this level existing");
        assertEquals(CollapseHierarchyTool.Refusal.TYPE_IS_OBSERVED,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a NESTED level, because this row deletes the whole file")
    void refusesANestedLevel() throws Exception {
        ToolResponse r = tool.execute(argsFor("TierKin.Middling"));

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(),
                "deleting the file would take TierKin and every other member with it"),
            () -> assertEquals(CollapseHierarchyTool.Refusal.FILE_HAS_OTHER_TYPES,
                r.getError().getReason(), "got: " + r.getError()),
            () -> assertTrue(read("TierKin.java").contains("class TierKin"),
                "and the enclosing class is still there"));
    }

    @Test
    @DisplayName("REFUSES an abstract parent ONLY when something constructs the level")
    void refusesAnAbstractParentThatIsConstructed() throws Exception {
        ToolResponse r = tool.execute(argsFor("TierUnderAbstract"));

        assertFalse(r.isSuccess(), "the `new TierAbstract(...)` left behind would not compile");
        assertEquals(CollapseHierarchyTool.Refusal.PARENT_IS_ABSTRACT,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a level whose constructor FIXES super()'s argument")
    void refusesAFixedArgumentConstructor() throws Exception {
        ToolResponse r = tool.execute(argsFor("TierFixedCtor"));

        assertFalse(r.isSuccess(), "the super(...) call in each subtype would stop meaning the"
            + " same thing once this level is gone");
        assertEquals(CollapseHierarchyTool.Refusal.CONSTRUCTOR_FIXES_ARGUMENT,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a level declaring a member name the parent already declares")
    void refusesACollidingMemberName() throws Exception {
        ToolResponse r = tool.execute(argsFor("TierColliding"));

        assertFalse(r.isSuccess(), "two members of one name is a merge, and merging is a"
            + " decision this row will not make");
        assertEquals(CollapseHierarchyTool.Refusal.MEMBER_NAME_COLLIDES,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a class that extends nothing")
    void refusesWithoutASuperclass() throws Exception {
        ToolResponse r = tool.execute(argsFor("TierBase"));

        assertFalse(r.isSuccess(), "there is no level to collapse");
        assertEquals(CollapseHierarchyTool.Refusal.NO_SUPERCLASS,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("auto_apply=false STAGES: nothing moves until the change is applied")
    void stagesTheChangeAndAppliesItOnDemand() throws Exception {
        ObjectNode args = argsFor("TierBanded");
        args.put("auto_apply", false);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertTrue(read("TierBanded.java").contains("class TierBanded"),
            "STAGING must not delete anything");

        Object changeId = ((java.util.Map<?, ?>) r.getData()).get("changeId");
        assertNotNull(changeId, "the response carries no changeId: " + r.getData());
        ObjectNode apply = new ObjectMapper().createObjectNode();
        apply.put("action", "apply");
        apply.put("changeId", String.valueOf(changeId));
        assertTrue(lifecycle.execute(apply).isSuccess());
        assertEquals("(absent)", read("TierBanded.java"),
            "and the staged change must be the real one");
    }

    @Test
    @DisplayName("the undo handle RESTORES the collapsed level and un-reparents its subtypes")
    void undoRestoresEverything() throws Exception {
        String before = read("TierGold.java");
        ToolResponse r = tool.execute(argsFor("TierBanded"));
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertEquals("(absent)", read("TierBanded.java"),
            "the CONTROL: with nothing deleted, an undo that restores nothing would pass");

        Object handle = ((java.util.Map<?, ?>) r.getData()).get("undoChangeId");
        assertNotNull(handle, "the response carries no undoChangeId: " + r.getData());
        ObjectNode undo = new ObjectMapper().createObjectNode();
        undo.put("action", "undo");
        undo.put("undoChangeId", String.valueOf(handle));
        assertTrue(lifecycle.execute(undo).isSuccess());
        Assertions.assertAll(
            () -> assertTrue(read("TierBanded.java").contains("class TierBanded"),
                "an undo of a DELETE puts the file back"),
            () -> assertEquals(before, read("TierGold.java"),
                "and the reparented subtype is byte-for-byte what it was"));
    }

    @Test
    @DisplayName("the door routes the kind")
    void theDoorRoutesIt() {
        assertTrue(tool.publishedKinds().contains("collapse_hierarchy"),
            "a kind that is dispatched and not published is invisible in tools/list");
        assertTrue(tool.delegates().get("collapse_hierarchy")
                instanceof CollapseHierarchyTool,
            "and the routing table must reach this delegate");
    }
}
