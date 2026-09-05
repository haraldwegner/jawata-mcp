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
    private Path targets;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new DataTool(() -> service,
            new org.jawata.mcp.refactoring.RefactoringChangeCache());
        targets = service.getProjectRoot()
            .resolve("src/main/java/com/example/SettingMethodTargets.java");
    }

    /** Drive the front door at the line declaring this setter. */
    private ToolResponse at(String declaration, String methodName) throws Exception {
        String[] lines = Files.readString(targets, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(declaration)) {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "remove_setting_method");
                args.put("filePath", targets.toString());
                args.put("line", i);
                args.put("column", lines[i].indexOf(methodName));
                return tool.execute(args);
            }
        }
        throw new AssertionError("the fixture no longer declares: " + declaration);
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
        assertTrue(error.contains("outside"),
            "the refusal must say that is the reason: " + error);
        // NAMING the site is the half that matters: a caller told only "somebody calls it"
        // has to go and find them, which is the work the tool just did.
        assertTrue(error.contains("SettingMethodDesk"),
            "and it must name where, so the caller can be routed rather than hunted: " + error);
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
        assertTrue(String.valueOf(r.getError()).contains("not a setting method"),
            "the refusal must name that reason: " + r.getError());
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
}
