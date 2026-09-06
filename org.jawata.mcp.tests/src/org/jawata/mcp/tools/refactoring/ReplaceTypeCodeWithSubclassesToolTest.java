package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.HierarchyTool;
import org.jawata.mcp.tools.inheritance.ReplaceTypeCodeWithSubclassesTool;
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
 * Stage 7, row 59 — Replace Type Code with Subclasses, as
 * {@code hierarchy direction=replace_type_code_with_subclasses}.
 *
 * <p>This row CREATES FILES, which is what most of its assertions are about: a change that
 * generates code is only as good as the code it generates, and a containment check on the
 * response cannot see a file at all.</p>
 */
class ReplaceTypeCodeWithSubclassesToolTest {

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
        args.put("direction", "replace_type_code_with_subclasses");
        args.put("typeName", "com.example." + type);
        return args;
    }

    private String read(String file) throws Exception {
        Path p = pkg.resolve(file);
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "(absent)";
    }

    @Test
    @DisplayName("one subclass per constant is generated, each overriding the accessor")
    void generatesASubclassPerConstant() throws Exception {
        ToolResponse r = tool.execute(argsFor("Enrolment"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String provisional = read("EnrolmentProvisional.java");
        String seniorTutor = read("EnrolmentSeniorTutor.java");
        Assertions.assertAll(
            () -> assertTrue(provisional.contains("public class EnrolmentProvisional extends Enrolment"),
                "each constant gets a subclass of the base:\n" + provisional),
            () -> assertTrue(provisional.contains("return GRADE_PROVISIONAL;"),
                "overriding the accessor to answer with ITS constant:\n" + provisional),
            () -> assertTrue(provisional.contains("@Override")
                    && provisional.contains("public int grade()"),
                "and it must be an override of the real accessor's signature, or it answers"
                    + " nothing:\n" + provisional),
            () -> assertTrue(seniorTutor.contains("class EnrolmentSeniorTutor "),
                "SENIOR_TUTOR must become SeniorTutor — a multi-word constant is where a naive"
                    + " capitalisation gets it wrong:\n" + seniorTutor),
            () -> assertTrue(read("EnrolmentConfirmed.java").contains("return GRADE_CONFIRMED;"),
                "and all THREE constants are covered, not just the first"));
    }

    @Test
    @DisplayName("the base class is left exactly as it was — this row adds, it does not migrate")
    void leavesTheBaseClassAlone() throws Exception {
        String before = read("Enrolment.java");
        ToolResponse r = tool.execute(argsFor("Enrolment"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        assertEquals(before, read("Enrolment.java"),
            "the field, the constructor and the constants all stay. Retyping them cascades"
                + " through construction and serialization, which is the same scope its sibling"
                + " replace_type_code_with_class declares — two cures for one smell that scoped"
                + " themselves differently would be worse than either");
    }

    @Test
    @DisplayName("the response reports the constant-to-subclass mapping and says what it did NOT do")
    void reportsTheMapping() throws Exception {
        ToolResponse r = tool.execute(argsFor("Enrolment"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        java.util.Map<?, ?> data = (java.util.Map<?, ?>) r.getData();
        Object mapping = data.get("constantMapping");
        Assertions.assertAll(
            () -> assertEquals("grade", data.get("accessor"),
                "it must name the accessor it overrode, because that is the one thing a reader"
                    + " has to check: " + data),
            () -> assertEquals("GRADE", data.get("typeCodePrefix"), "" + data),
            () -> assertNotNull(mapping, "no mapping reported: " + data),
            () -> assertEquals(3, ((java.util.Map<?, ?>) mapping).size(),
                "three constants, three subclasses: " + mapping),
            () -> assertTrue(String.valueOf(data.get("note")).contains("remain"),
                "and it must say what it deliberately left undone, or a reader will assume the"
                    + " migration happened: " + data));
    }

    @Test
    @DisplayName("REFUSES when the code is not read through an accessor — Fowler's first step")
    void refusesWhenTheCodeIsNotEncapsulated() throws Exception {
        ToolResponse r = tool.execute(argsFor("Stipend"));

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(), "a subclass would have nothing to override"),
            () -> assertEquals(ReplaceTypeCodeWithSubclassesTool.Refusal.CODE_NOT_ENCAPSULATED,
                r.getError().getReason(), "got: " + r.getError()),
            () -> assertTrue(String.valueOf(r.getError()).contains("encapsulate_field"),
                "and it must name the operation that performs the missing step, rather than"
                    + " leaving the caller to work out what to do: " + r.getError()),
            () -> assertEquals("(absent)", read("StipendJunior.java"),
                "a refusal creates nothing"));
    }

    @Test
    @DisplayName("REFUSES a FINAL class, and points at the sibling cure that needs no subclass")
    void refusesAFinalClass() throws Exception {
        ToolResponse r = tool.execute(argsFor("Bursary"));

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(), "a final class cannot be subclassed"),
            () -> assertEquals(ReplaceTypeCodeWithSubclassesTool.Refusal.CLASS_IS_FINAL,
                r.getError().getReason(), "got: " + r.getError()),
            () -> assertTrue(String.valueOf(r.getError()).contains("replace_type_code_with_class"),
                "the same smell has another cure that works here: " + r.getError()));
    }

    @Test
    @DisplayName("REFUSES a lone constant — one value is not a code")
    void refusesWithoutAConstantGroup() throws Exception {
        ToolResponse r = tool.execute(argsFor("Sabbatical"));

        assertFalse(r.isSuccess(), "a type code is a SET of values; one is just a constant");
        assertEquals(ReplaceTypeCodeWithSubclassesTool.Refusal.NO_TYPE_CODE_CONSTANTS,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("auto_apply=false STAGES: no file appears until the change is applied")
    void stagesTheChangeAndAppliesItOnDemand() throws Exception {
        ObjectNode args = argsFor("Enrolment");
        args.put("auto_apply", false);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertEquals("(absent)", read("EnrolmentProvisional.java"),
            "STAGING must not create files — a preview that writes is worse than no preview");

        Object changeId = ((java.util.Map<?, ?>) r.getData()).get("changeId");
        assertNotNull(changeId, "the response carries no changeId: " + r.getData());
        ObjectNode apply = new ObjectMapper().createObjectNode();
        apply.put("action", "apply");
        apply.put("changeId", String.valueOf(changeId));
        assertTrue(lifecycle.execute(apply).isSuccess());
        assertTrue(read("EnrolmentProvisional.java").contains("extends Enrolment"),
            "and the staged change must be the real one");
    }

    @Test
    @DisplayName("the undo handle DELETES the generated files")
    void undoDeletesWhatItCreated() throws Exception {
        ToolResponse r = tool.execute(argsFor("Enrolment"));
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertTrue(read("EnrolmentProvisional.java").contains("extends Enrolment"),
            "the CONTROL: with nothing created, an undo that deletes nothing would pass");

        Object handle = ((java.util.Map<?, ?>) r.getData()).get("undoChangeId");
        assertNotNull(handle, "the response carries no undoChangeId: " + r.getData());
        ObjectNode undo = new ObjectMapper().createObjectNode();
        undo.put("action", "undo");
        undo.put("undoChangeId", String.valueOf(handle));
        assertTrue(lifecycle.execute(undo).isSuccess());
        Assertions.assertAll(
            () -> assertEquals("(absent)", read("EnrolmentProvisional.java"),
                "an undo of a CREATE is a delete, and all three must go"),
            () -> assertEquals("(absent)", read("EnrolmentConfirmed.java")),
            () -> assertEquals("(absent)", read("EnrolmentSeniorTutor.java")));
    }

    @Test
    @DisplayName("the door routes the kind")
    void theDoorRoutesIt() {
        assertTrue(tool.publishedKinds().contains("replace_type_code_with_subclasses"),
            "a kind that is dispatched and not published is invisible in tools/list");
        assertTrue(tool.delegates().get("replace_type_code_with_subclasses")
                instanceof ReplaceTypeCodeWithSubclassesTool,
            "and the routing table must reach this delegate");
    }
}
