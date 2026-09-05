package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.api.PreserveWholeObjectTool;
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
 * Stage 4, row 28 — Preserve Whole Object, as
 * {@code change_method_signature kind=preserve_whole_object}.
 *
 * <p>The row infers the object from the call sites, so every refusal is a way that inference could
 * go wrong: one caller unpacking two objects, two callers disagreeing on an accessor, a call that
 * already carries the object, and a body that would evaluate an accessor more often than the call
 * evaluated a value.</p>
 */
class PreserveWholeObjectToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ChangeMethodSignatureTool tool;
    private org.jawata.mcp.tools.RefactoringTool lifecycle;
    private Path targets;
    private Path desk;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        org.jawata.mcp.refactoring.RefactoringChangeCache cache =
            new org.jawata.mcp.refactoring.RefactoringChangeCache();
        tool = new ChangeMethodSignatureTool(() -> service, cache);
        lifecycle = new org.jawata.mcp.tools.RefactoringTool(() -> service, cache);
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example");
        targets = pkg.resolve("WholeObjectTargets.java");
        desk = pkg.resolve("WholeObjectDesk.java");
    }

    private ObjectNode argsFor(String method, String... parameters) {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "preserve_whole_object");
        args.put("symbol", "com.example.WholeObjectTargets#" + method);
        args.putArray("parameters").add(parameters[0]).add(parameters[1]);
        return args;
    }

    @Test
    @DisplayName("the two parameters become the object, the body asks it, and BOTH callers shorten")
    void foldsTheParametersIntoTheObject() throws Exception {
        ToolResponse r = tool.execute(argsFor("withinRange", "low", "high"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        String callers = Files.readString(desk, StandardCharsets.UTF_8);
        Assertions.assertAll(
            () -> assertTrue(after.contains("public boolean withinRange(Range range, int reading)"),
                "the two folded parameters must become ONE, in the first one's place, and the"
                    + " parameter the callers did not unpack must survive beside it:\n" + after),
            () -> assertTrue(after.contains("reading >= range.low() && reading <= range.high()"),
                "and the body must ask the object for each value it used to be handed:\n" + after),

            // BOTH call sites, because one is not evidence of a rewrite that must reach every
            // caller — and the object is inserted at the FIRST folded position, so the
            // surviving argument's order is part of the claim rather than incidental.
            () -> assertEquals(2,
                callers.split("targets\\.withinRange\\(range, range\\.reading\\(\\)\\)", -1)
                    .length - 1,
                "both callers must now pass the range once, with the argument they did not"
                    + " unpack still after it:\n" + callers));
    }

    @Test
    @DisplayName("an object from ANOTHER package is imported, not written as a bare simple name")
    void addsTheImportForACrossPackageObject() throws Exception {
        ToolResponse r = tool.execute(argsFor("outside", "floor", "ceiling"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        Assertions.assertAll(
            () -> assertTrue(after.contains("import com.example.service.Bounds;"),
                "the import must be ADDED — this file never mentioned that package before, and"
                    + " row 16 established that a bare simple name compiles only where the type"
                    + " is already visible, so the compile gate would refuse the whole change:\n"
                    + after),
            () -> assertTrue(after.contains("public boolean outside(Bounds bounds, int reading)"),
                "and the parameter is then written with the SIMPLE name the import makes"
                    + " legal:\n" + after),
            () -> assertTrue(after.contains("reading < bounds.floor() || reading > bounds.ceiling()"),
                "with the body reading through it:\n" + after));
    }

    @Test
    @DisplayName("REFUSES a caller that unpacks TWO objects, because there is no whole object")
    void refusesTwoObjectsInOneCall() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = tool.execute(argsFor("spans", "low", "high"));

        assertFalse(r.isSuccess(), "the two values come from different ranges");
        assertEquals(PreserveWholeObjectTool.Refusal.CALLERS_DISAGREE,
            r.getError().getReason(), "got: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("REFUSES when two callers derive the same parameter through DIFFERENT accessors")
    void refusesCallersThatDisagreeOnAnAccessor() throws Exception {
        ToolResponse r = tool.execute(argsFor("contested", "low", "high"));

        assertFalse(r.isSuccess(), "one caller reads high(), the other ceiling()");
        assertEquals(PreserveWholeObjectTool.Refusal.CALLERS_DISAGREE,
            r.getError().getReason(), "got: " + r.getError());
        assertTrue(String.valueOf(r.getError()).contains("high")
                && String.valueOf(r.getError()).contains("ceiling"),
            "and it must NAME both accessors it saw, so the caller can see the conflict rather"
                + " than being told there is one: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES when the call ALREADY passes the object, and names the row that fits")
    void refusesWhenTheObjectIsAlreadyAnArgument() throws Exception {
        ToolResponse r = tool.execute(argsFor("alsoCarriesTheRange", "low", "high"));

        assertFalse(r.isSuccess(), "folding these in would pass the range twice");
        assertEquals(PreserveWholeObjectTool.Refusal.OBJECT_ALREADY_PASSED,
            r.getError().getReason(), "got: " + r.getError());
        assertTrue(String.valueOf(r.getError()).contains("replace_parameter_with_query"),
            "and it must name the row that DOES fit, rather than leaving the caller to guess"
                + " which of ten kinds is theirs: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a body that reads a folded parameter twice, because a value became a "
        + "query")
    void refusesARepeatedRead() throws Exception {
        ToolResponse r = tool.execute(argsFor("weighted", "low", "high"));

        assertFalse(r.isSuccess(), "the callers agree, so the only thing left to refuse on is"
            + " that one evaluation would become two");
        assertEquals(PreserveWholeObjectTool.Refusal.PARAMETER_READ_REPEATEDLY,
            r.getError().getReason(),
            "and it must NOT be the unanimity refusal — the callers agree here: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES ONE parameter, and names the row that fits instead")
    void refusesASingleParameter() throws Exception {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "preserve_whole_object");
        args.put("symbol", "com.example.WholeObjectTargets#withinRange");
        args.putArray("parameters").add("low");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "an object in place of one value is longer, not shorter");
        assertEquals(PreserveWholeObjectTool.Refusal.PARAMETERS_REQUIRED,
            r.getError().getReason(), "got: " + r.getError());
        assertTrue(String.valueOf(r.getError()).contains("replace_parameter_with_query"),
            "and it must name row 53, which is the operation a single parameter wants: "
                + r.getError());
    }

    @Test
    @DisplayName("REFUSES a parameter the method does not declare")
    void refusesAnUnknownParameter() throws Exception {
        ToolResponse r = tool.execute(argsFor("withinRange", "low", "nosuch"));

        assertFalse(r.isSuccess(), "there is no such parameter to fold");
        assertEquals(PreserveWholeObjectTool.Refusal.PARAMETER_NOT_FOUND,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("parameterName overrides the name the callers happen to use")
    void honoursAnExplicitParameterName() throws Exception {
        ObjectNode args = argsFor("withinRange", "low", "high");
        args.put("parameterName", "bounds");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        Assertions.assertAll(
            () -> assertTrue(after.contains("withinRange(Range bounds, int reading)"),
                "the declaration must take the name asked for:\n" + after),
            () -> assertTrue(after.contains("bounds.low()") && after.contains("bounds.high()"),
                "and the body must read through it, not through the callers' name:\n" + after));
    }

    @Test
    @DisplayName("auto_apply=false STAGES: the file is untouched until the change is applied")
    void stagesTheChangeAndAppliesItOnDemand() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ObjectNode args = argsFor("withinRange", "low", "high");
        args.put("auto_apply", false);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "STAGING must not write");

        Object changeId = ((java.util.Map<?, ?>) r.getData()).get("changeId");
        assertNotNull(changeId, "the response carries no changeId: " + r.getData());
        ObjectNode apply = new ObjectMapper().createObjectNode();
        apply.put("action", "apply");
        apply.put("changeId", String.valueOf(changeId));
        assertTrue(lifecycle.execute(apply).isSuccess());
        assertTrue(Files.readString(targets, StandardCharsets.UTF_8)
                .contains("withinRange(Range range, int reading)"),
            "and the staged change must be the real one");
    }

    @Test
    @DisplayName("the undo handle RESTORES both files")
    void undoRestoresEverything() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        String beforeDesk = Files.readString(desk, StandardCharsets.UTF_8);
        ToolResponse r = tool.execute(argsFor("withinRange", "low", "high"));
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertFalse(before.equals(Files.readString(targets, StandardCharsets.UTF_8)),
            "the CONTROL: with nothing changed, an undo that restores nothing would pass");

        Object handle = ((java.util.Map<?, ?>) r.getData()).get("undoChangeId");
        assertNotNull(handle, "the response carries no undoChangeId: " + r.getData());
        ObjectNode undo = new ObjectMapper().createObjectNode();
        undo.put("action", "undo");
        undo.put("undoChangeId", String.valueOf(handle));
        assertTrue(lifecycle.execute(undo).isSuccess());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "the method's own file is restored");
        assertEquals(beforeDesk, Files.readString(desk, StandardCharsets.UTF_8),
            "and so are the callers'");
    }

    @Test
    @DisplayName("the door routes the kind")
    void theDoorRoutesIt() {
        assertTrue(tool.publishedKinds().contains("preserve_whole_object"),
            "a kind that is dispatched and not published is invisible in tools/list");
        assertTrue(tool.delegates().get("preserve_whole_object")
                instanceof PreserveWholeObjectTool,
            "and the routing table must reach this delegate");
    }
}
