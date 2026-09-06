package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.DataTool;
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
 * Stage 5, row 37 — Remove Setting Method, as {@code data kind=remove_setting_method}.
 *
 * <p>The operation is a precondition and an edit, and this file asserts both sides of the
 * precondition: a setter whose only caller is a constructor goes, and one with a caller
 * anywhere else refuses with the site named. The refusal is the case a reader will meet most,
 * because it is the state most setters are actually in.</p>
 *
 * <p><b>The {@code final} half is asserted separately from the removal</b>, and on two
 * fixtures rather than one. A row that only ever showed the happy pair could not tell "the
 * modifier follows the removal" from "the modifier is added whenever the removal succeeds",
 * and the second is false: a field another method writes is not settled at construction, so
 * the setter goes and the modifier does not.</p>
 */
class RemoveSettingMethodToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DataTool tool;
    private org.jawata.mcp.tools.RefactoringTool lifecycle;
    private Path targets;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        org.jawata.mcp.refactoring.RefactoringChangeCache cache =
            new org.jawata.mcp.refactoring.RefactoringChangeCache();
        tool = new DataTool(() -> service, cache);
        // The SAME cache, because staging and undo are two calls about one change: a
        // lifecycle door holding its own cache would answer about nothing.
        lifecycle = new org.jawata.mcp.tools.RefactoringTool(() -> service, cache);
        targets = service.getProjectRoot()
            .resolve("src/main/java/com/example/SettingMethodTargets.java");
    }

    /** Drive the front door at the line declaring this setter. */
    private ToolResponse at(String declaration, String methodName) throws Exception {
        return at(declaration, methodName, new ObjectMapper().createObjectNode());
    }

    /** The same, with extra arguments the caller supplies (staging, for instance). */
    private ToolResponse at(String declaration, String methodName, ObjectNode args)
            throws Exception {
        String[] lines = Files.readString(targets, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(declaration)) {
                args.put("kind", "remove_setting_method");
                args.put("filePath", targets.toString());
                args.put("line", i);
                args.put("column", lines[i].indexOf(methodName));
                return tool.execute(args);
            }
        }
        throw new AssertionError("the fixture no longer declares: " + declaration);
    }

    /** One value out of a successful response's data map. */
    private static String field(ToolResponse response, String key) {
        Object data = response.getData();
        assertTrue(data instanceof java.util.Map,
            "expected a data map, got: " + data);
        Object value = ((java.util.Map<?, ?>) data).get(key);
        assertNotNull(value, "the response carries no '" + key + "': " + data);
        return String.valueOf(value);
    }

    @Test
    @DisplayName("the canonical case: the setter goes, its constructor call becomes an "
        + "assignment, and the field is settled")
    void removesASetterAndSettlesTheField() throws Exception {
        ToolResponse r = at("public void setCarrier(String carrier)", "setCarrier");
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        assertFalse(after.contains("void setCarrier("),
            "the SETTER must be gone — it is the whole subject of the row:\n" + after);
        assertTrue(after.contains("this.carrier = carrier;"),
            "and the constructor that called it must now assign the field itself, or the"
                + " field is never set:\n" + after);
        assertTrue(after.contains("private final String carrier;"),
            "the field becomes final: that is what makes 'settled at construction' a thing"
                + " the compiler enforces rather than a comment:\n" + after);
        // The CONTROL: the other field of the same class had no setter and is untouched, so
        // the change is the setter's field and not a sweep over the class.
        assertTrue(after.contains("private int weight;"),
            "a field this call was not pointed at must be exactly as it was:\n" + after);
    }

    @Test
    @DisplayName("REFUSES a caller outside the declaring class's constructors, and names it")
    void refusesACallerOutsideTheConstructors() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = at("public void setReference(String reference)", "setReference");
        assertFalse(r.isSuccess(),
            "a caller outside the constructors is asking to change the value after"
                + " construction, which is the behaviour being removed");
        String error = String.valueOf(r.getError());
        assertEquals(org.jawata.mcp.tools.data.RemoveSettingMethodTool.Refusal.CALLED_FROM_OUTSIDE,
            r.getError().getReason(),
            "the refusal must be the caller PRECONDITION: " + error);
        // NAMING the site is the half that matters: a caller told only "somebody calls it"
        // has to go and find them, which is the work the tool just did.
        assertTrue(error.contains("SettingMethodDesk"),
            "and it must name where, so the caller can be routed rather than hunted: " + error);
        // D3a (S8b step 7): this row carries NO next step, and the absence is asserted rather
        // than left to be noticed. Its refusals report that the setter is STILL NEEDED — an
        // outside caller assigns through it — which is a fact about the code that has to
        // CHANGE, not a smaller operation someone can run. The written reason is the class
        // javadoc of RemoveSettingMethodTool; if a successor is ever found, this assertion is
        // what makes updating that paragraph unavoidable.
        org.junit.jupiter.api.Assertions.assertNull(r.getError().getNextStep(),
            "a pointer here would name an operation that does not address what was refused: "
                + error);
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("REFUSES a method that assigns a field and then does something else")
    void refusesAMethodThatIsNotASetter() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = at("public void setReading(int reading)", "setReading");
        assertFalse(r.isSuccess(), "its body does more than assign, so deleting it would"
            + " remove behaviour rather than a setter");
        assertEquals(org.jawata.mcp.tools.data.RemoveSettingMethodTool.Refusal.NOT_A_SETTING_METHOD,
            r.getError().getReason(),
            "the refusal must be the shape PRECONDITION: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("the setter goes but the field stays non-final when another method writes it, "
        + "and the reason is reported")
    void leavesTheFieldNonFinalWhenSomethingElseWritesIt() throws Exception {
        ToolResponse r = at("public void setHits(int hits)", "setHits");
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        assertFalse(after.contains("void setHits("),
            "the setter still goes — it had no caller outside the constructor:\n" + after);
        assertFalse(after.contains("private final int hits;"),
            "but the field must NOT become final: bump() writes it, so the value is not"
                + " settled at construction and final would not compile:\n" + after);
        assertTrue(after.contains("private int hits;"),
            "it stays exactly as it was:\n" + after);
    }

    @Test
    @DisplayName("rewrites the SIBLING it was pointed at, not the first class in the file "
        + "declaring a setter of that name")
    void rewritesTheSiblingItWasPointedAt() throws Exception {
        // LeftPanel and RightPanel each declare setLabel(String) — legal Java, because a
        // simple name is unique inside a scope and not inside a file. The target is the
        // SECOND, so a lookup that searches the whole unit and takes the first hit answers
        // about LeftPanel: it checks the precondition on one class and edits another.
        ToolResponse r = at("public void setLabel(String caption)", "setLabel");
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        assertFalse(after.contains("public void setLabel(String caption)"),
            "the setter that was POINTED AT must be gone:\n" + after);
        assertTrue(after.contains("public void setLabel(String title)"),
            "and the sibling's identically named setter must be untouched — rewriting it"
                + " instead is the defect this case exists for:\n" + after);
        assertTrue(after.contains("private final String caption;"),
            "the pointed-at class's field is the one settled:\n" + after);
        assertTrue(after.contains("private String title;"),
            "the sibling's field must NOT become final:\n" + after);
    }

    @Test
    @DisplayName("REFUSES a setter that implements an INTERFACE the class declares")
    void refusesASetterThatImplementsAnInterface() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = at("public void setScale(int scale)", "setScale");
        assertFalse(r.isSuccess(),
            "Gauge.setScale satisfies Adjustable, so it is part of a contract this class"
                + " declares rather than a setter it is free to take away");
        // THE REASON, NOT THE PROSE. This used to search the message for words, which is how
        // it once passed while the branch it names never ran: with the supertype lookup
        // reverted, the row ACCEPTS this setter and the COMPILE GATE undoes it — and the
        // compiler's own error says "Adjustable" too. One value replaces three searches, two
        // of which had to be negative.
        assertEquals(org.jawata.mcp.tools.data.RemoveSettingMethodTool.Refusal.OVERRIDES_SUPERTYPE,
            r.getError().getReason(),
            "the refusal must be the override PRECONDITION: " + r.getError());
        assertTrue(String.valueOf(r.getError()).contains("Adjustable"),
            "and the sentence still names which supertype's contract, for the human reading"
                + " it: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("auto_apply=false STAGES: the file is untouched until the staged change is "
        + "applied by id")
    void stagesTheChangeAndAppliesItOnDemand() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ObjectNode staged = new ObjectMapper().createObjectNode();
        staged.put("auto_apply", false);
        ToolResponse r = at("public void setCarrier(String carrier)", "setCarrier", staged);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "STAGING must not write: the whole point is a diff a caller reviews first");

        ObjectNode apply = new ObjectMapper().createObjectNode();
        apply.put("action", "apply");
        apply.put("changeId", field(r, "changeId"));
        ToolResponse applied = lifecycle.execute(apply);
        assertTrue(applied.isSuccess(), "got: " + applied.getError());
        assertFalse(Files.readString(targets, StandardCharsets.UTF_8).contains("void setCarrier("),
            "and the staged change must be the real one — applying it performs the removal");
    }

    @Test
    @DisplayName("the undo handle RESTORES the file, byte for byte")
    void undoRestoresTheFile() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = at("public void setCarrier(String carrier)", "setCarrier");
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertFalse(before.equals(Files.readString(targets, StandardCharsets.UTF_8)),
            "the CONTROL: without a change first, an undo that restores nothing would pass");

        ObjectNode undo = new ObjectMapper().createObjectNode();
        undo.put("action", "undo");
        undo.put("undoChangeId", field(r, "undoChangeId"));
        ToolResponse undone = lifecycle.execute(undo);
        assertTrue(undone.isSuccess(), "got: " + undone.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "undo must restore the source exactly, not approximately");
    }

    @Test
    @DisplayName("runs from the setter's SYMBOL NAME, with no file position given")
    void runsFromItsSymbolName() throws Exception {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "remove_setting_method");
        args.put("symbol", "com.example.SettingMethodTargets.Shipment#setCarrier");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertFalse(Files.readString(targets, StandardCharsets.UTF_8).contains("void setCarrier("),
            "a finding names a symbol, not a caret — the name form is how it becomes callable"
                + " straight from the finding");
    }
}
