package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.HierarchyTool;
import org.jawata.mcp.tools.inheritance.PullUpConstructorBodyTool;
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
 * Stage 7, row 29 — Pull Up Constructor Body, as
 * {@code hierarchy direction=pull_up_constructor_body}.
 *
 * <p>The row performs one case and refuses the rest BY NAME, so every refusal here asserts the
 * typed reason code rather than a substring of the message. That is this sprint's own lesson,
 * paid for twice: a refusal test proves a refusal FIRED, never that the branch you had in mind
 * is what fired it, and a substring two refusals share proves less still.</p>
 */
class PullUpConstructorBodyToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private HierarchyTool tool;
    private org.jawata.mcp.tools.RefactoringTool lifecycle;
    private Path targets;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        org.jawata.mcp.refactoring.RefactoringChangeCache cache =
            new org.jawata.mcp.refactoring.RefactoringChangeCache();
        tool = new HierarchyTool(() -> service, cache);
        lifecycle = new org.jawata.mcp.tools.RefactoringTool(() -> service, cache);
        targets = service.getProjectRoot()
            .resolve("src/main/java/com/example/ConstructorBodyTargets.java");
    }

    /** Address the constructor of a nested class by NAME — no line, no column. */
    private ObjectNode argsFor(String nested) {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("direction", "pull_up_constructor_body");
        args.put("symbol", "com.example.ConstructorBodyTargets." + nested + "#" + nested);
        return args;
    }

    private String read() throws Exception {
        return Files.readString(targets, StandardCharsets.UTF_8);
    }

    /** One class's text, so "gone from HERE" is expressible — row 61 earned this helper. */
    private String bodyOf(String source, String declaration) {
        int at = source.indexOf(declaration);
        assertTrue(at >= 0, "the fixture no longer declares " + declaration + ":\n" + source);
        int next = source.indexOf("\n    public static class ", at + declaration.length());
        return source.substring(at, next < 0 ? source.length() : next);
    }

    @Test
    @DisplayName("the leading assignments move into a GENERATED superclass constructor, and the "
        + "subclass calls super")
    void pullsTheLeadingAssignmentsUp() throws Exception {
        ToolResponse r = tool.execute(argsFor("Contractor"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = read();
        String subclass = bodyOf(after, "public static class Contractor");
        String superclass = bodyOf(after, "public static class Employment");
        Assertions.assertAll(
            () -> assertTrue(subclass.contains("super(employer, grade);"),
                "the subclass must delegate upward, naming the parameters in order:\n"
                    + subclass),
            () -> assertFalse(subclass.contains("this.employer = employer;"),
                "and the pulled assignment must be GONE from here — a whole-file check could"
                    + " not say that, because the same text is now in the superclass:\n"
                    + subclass),
            () -> assertTrue(subclass.contains("this.started = started;"),
                "while the subclass's OWN field stays exactly where it was, which is what"
                    + " distinguishes this from moving the whole body:\n" + subclass),
            () -> assertTrue(superclass.contains("protected Employment(String employer, int grade)"),
                "the superclass gains a constructor taking those parameters:\n" + superclass),
            () -> assertTrue(superclass.contains("this.employer = employer;")
                    && superclass.contains("this.grade = grade;"),
                "carrying the author's OWN assignment text rather than a regenerated"
                    + " equivalent:\n" + superclass));
    }

    @Test
    @DisplayName("the response says how much moved and whether it generated the constructor")
    void reportsWhatItDid() throws Exception {
        ToolResponse r = tool.execute(argsFor("Contractor"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        java.util.Map<?, ?> data = (java.util.Map<?, ?>) r.getData();
        Assertions.assertAll(
            () -> assertEquals(2, data.get("statementsPulled"),
                "Contractor has exactly two leading superclass assignments; a count that drifts"
                    + " means the run stopped early or ran past its own rule: " + data),
            () -> assertEquals(true, data.get("constructorGenerated"),
                "Employment declares no constructor, so one had to be generated — the OTHER"
                    + " branch reuses an existing one, and the two must be distinguishable to a"
                    + " caller: " + data),
            () -> assertEquals("Employment", data.get("superclass"),
                "and it must name where the body went: " + data));
    }

    @Test
    @DisplayName("REFUSES a constructor that already delegates upward WITH ARGUMENTS")
    void refusesAConstructorThatAlreadyCallsSuper() throws Exception {
        String before = read();
        ToolResponse r = tool.execute(argsFor("Reader"));

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(), "it already delegates upward"),
            () -> assertEquals(PullUpConstructorBodyTool.Refusal.ALREADY_CALLS_SUPER,
                r.getError().getReason(), "got: " + r.getError()),
            () -> assertEquals(before, read(), "a refusal modifies nothing"));
    }

    @Test
    @DisplayName("a BARE super() is replaced, not left beside the new call")
    void replacesABareSuperCall() throws Exception {
        ToolResponse r = tool.execute(argsFor("Seconded"));
        assertTrue(r.isSuccess(), "a bare super() says only that the superclass sets itself up"
            + " with nothing, which is exactly what this row changes: " + r.getError());

        String subclass = bodyOf(read(), "public static class Seconded");
        Assertions.assertAll(
            () -> assertTrue(subclass.contains("super(employer, grade);"),
                "the bare call must become the argument-carrying one:\n" + subclass),
            () -> assertEquals(1, subclass.split("super\\(", -1).length - 1,
                "and there must be exactly ONE super call — leaving the bare one beside the"
                    + " new one delegates upward twice, which is the defect this branch"
                    + " exists to avoid:\n" + subclass));
    }

    @Test
    @DisplayName("REFUSES when the FIRST statement is not a superclass assignment, even though a "
        + "later one is")
    void refusesWhenTheRunStopsAtTheFirstStatement() throws Exception {
        ToolResponse r = tool.execute(argsFor("Engaged"));

        assertFalse(r.isSuccess(), "Engaged assigns its OWN field first, so the leading run is"
            + " empty — and the superclass assignments below it are deliberately not reached,"
            + " because a later statement may depend on an earlier one");
        assertEquals(PullUpConstructorBodyTool.Refusal.NOTHING_TO_PULL_UP,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a class that extends nothing but Object")
    void refusesAClassWithNoSuperclass() throws Exception {
        ToolResponse r = tool.execute(argsFor("Unattached"));

        assertFalse(r.isSuccess(), "there is nowhere to pull the body up to");
        assertEquals(PullUpConstructorBodyTool.Refusal.NO_SUPERCLASS,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a position that is not a constructor")
    void refusesANonConstructor() throws Exception {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("direction", "pull_up_constructor_body");
        args.put("symbol", "com.example.ConstructorBodyTargets.Contractor#started");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "an ordinary method has no constructor body to pull up");
        assertEquals(PullUpConstructorBodyTool.Refusal.NOT_A_CONSTRUCTOR,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("auto_apply=false STAGES: neither file is touched until the change is applied")
    void stagesTheChangeAndAppliesItOnDemand() throws Exception {
        String before = read();
        ObjectNode args = argsFor("Contractor");
        args.put("auto_apply", false);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertEquals(before, read(), "STAGING must not write");

        Object changeId = ((java.util.Map<?, ?>) r.getData()).get("changeId");
        assertNotNull(changeId, "the response carries no changeId: " + r.getData());
        ObjectNode apply = new ObjectMapper().createObjectNode();
        apply.put("action", "apply");
        apply.put("changeId", String.valueOf(changeId));
        assertTrue(lifecycle.execute(apply).isSuccess());
        assertTrue(read().contains("super(employer, grade);"),
            "and the staged change must be the real one");
    }

    @Test
    @DisplayName("the undo handle RESTORES the file")
    void undoRestoresTheFile() throws Exception {
        String before = read();
        ToolResponse r = tool.execute(argsFor("Contractor"));
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertFalse(before.equals(read()),
            "the CONTROL: with nothing changed, an undo that restores nothing would pass");

        Object handle = ((java.util.Map<?, ?>) r.getData()).get("undoChangeId");
        assertNotNull(handle, "the response carries no undoChangeId: " + r.getData());
        ObjectNode undo = new ObjectMapper().createObjectNode();
        undo.put("action", "undo");
        undo.put("undoChangeId", String.valueOf(handle));
        assertTrue(lifecycle.execute(undo).isSuccess());
        assertEquals(before, read(), "the file is restored");
    }

    @Test
    @DisplayName("the door routes the kind")
    void theDoorRoutesIt() {
        assertTrue(tool.publishedKinds().contains("pull_up_constructor_body"),
            "a kind that is dispatched and not published is invisible in tools/list");
        assertTrue(tool.delegates().get("pull_up_constructor_body")
                instanceof PullUpConstructorBodyTool,
            "and the routing table must reach this delegate");
    }
}
