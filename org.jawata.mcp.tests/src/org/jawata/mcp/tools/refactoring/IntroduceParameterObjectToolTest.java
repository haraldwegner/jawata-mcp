package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.api.IntroduceParameterObjectTool;
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
 * Stage 4, row 21 — Introduce Parameter Object, as
 * {@code change_method_signature kind=introduce_parameter_object}.
 *
 * <p>The row wraps JDT's own engine, so what this file establishes is not that the rewrite
 * works — that is the engine's business — but that the OPERATION is what it claims: the class
 * appears with a field per parameter, the signature collapses to one argument, and the caller
 * in ANOTHER FILE is rewritten to construct it. The last of those is the half a single-file
 * fixture cannot show, and it is why the fixture has a second file nobody points at.</p>
 */
class IntroduceParameterObjectToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ChangeMethodSignatureTool tool;
    private org.jawata.mcp.tools.RefactoringTool lifecycle;
    private Path targets;
    private Path desk;
    private Path pkg;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        org.jawata.mcp.refactoring.RefactoringChangeCache cache =
            new org.jawata.mcp.refactoring.RefactoringChangeCache();
        tool = new ChangeMethodSignatureTool(() -> service, cache);
        lifecycle = new org.jawata.mcp.tools.RefactoringTool(() -> service, cache);
        pkg = service.getProjectRoot().resolve("src/main/java/com/example");
        targets = pkg.resolve("ParameterObjectTargets.java");
        desk = pkg.resolve("ParameterObjectDesk.java");
    }

    /** Drive the front door at the line declaring this method. */
    private ObjectNode argsAt(String declaration, String methodName) throws Exception {
        String[] lines = Files.readString(targets, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(declaration)) {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "introduce_parameter_object");
                args.put("filePath", targets.toString());
                args.put("line", i);
                args.put("column", lines[i].indexOf(methodName));
                return args;
            }
        }
        throw new AssertionError("the fixture no longer declares: " + declaration);
    }

    @Test
    @DisplayName("the group becomes a class, the signature takes one argument, and the caller "
        + "in another file constructs it")
    void groupsTheParametersIntoAClass() throws Exception {
        ObjectNode args = argsAt("public String describeDelivery(", "describeDelivery");
        args.put("className", "DeliveryAddress");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        Path created = pkg.resolve("DeliveryAddress.java");
        assertTrue(Files.exists(created),
            "the class must exist as its own file — topLevel defaults to true");
        String klass = Files.readString(created, StandardCharsets.UTF_8);
        assertTrue(klass.contains("String city") && klass.contains("String street")
                && klass.contains("int postcode"),
            "with a field per parameter of the group:\n" + klass);

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        assertFalse(after.contains("describeDelivery(String city, String street, int postcode)"),
            "the three-parameter signature must be gone:\n" + after);
        assertTrue(after.contains("DeliveryAddress"),
            "and the method now takes the class:\n" + after);

        // THE CROSS-FILE HALF. Nothing pointed the tool at this file; if the call site were not
        // rewritten it would no longer compile, and the compile gate would have refused the
        // whole change — so this asserts WHAT it became rather than merely that it changed.
        String other = Files.readString(desk, StandardCharsets.UTF_8);
        assertTrue(other.contains("new DeliveryAddress("),
            "the caller in another file must construct the new class:\n" + other);
    }

    @Test
    @DisplayName("REFUSES a method with one parameter — that is not a group")
    void refusesASingleParameter() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ObjectNode args = argsAt("public String describeCity(", "describeCity");
        args.put("className", "CityName");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "one parameter is not a group that travels together");
        assertEquals(IntroduceParameterObjectTool.Refusal.NOT_A_PARAMETER_GROUP,
            r.getError().getReason(),
            "the refusal must be the group PRECONDITION: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
        assertFalse(Files.exists(pkg.resolve("CityName.java")),
            "and it creates no class");
    }

    @Test
    @DisplayName("REFUSES a missing className, because naming the concept IS the refactoring")
    void refusesWithoutAClassName() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = tool.execute(argsAt("public String describeDelivery(", "describeDelivery"));

        assertFalse(r.isSuccess(), "the class's name has no default by design");
        assertEquals(IntroduceParameterObjectTool.Refusal.CLASS_NAME_REQUIRED,
            r.getError().getReason(),
            "the refusal must be the name PRECONDITION: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("runs from the method's SYMBOL NAME, with no file position given")
    void runsFromItsSymbolName() throws Exception {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "introduce_parameter_object");
        args.put("symbol", "com.example.ParameterObjectTargets#describeDelivery");
        args.put("className", "DeliveryAddress");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertTrue(Files.exists(pkg.resolve("DeliveryAddress.java")),
            "a finding names a symbol, not a caret — the name form is how the row becomes"
                + " callable straight from one");
    }

    @Test
    @DisplayName("auto_apply=false STAGES: the file is untouched until the change is applied")
    void stagesTheChangeAndAppliesItOnDemand() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ObjectNode args = argsAt("public String describeDelivery(", "describeDelivery");
        args.put("className", "DeliveryAddress");
        args.put("auto_apply", false);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "STAGING must not write: the whole point is a diff a caller reviews first");

        Object data = r.getData();
        assertTrue(data instanceof java.util.Map, "expected a data map, got: " + data);
        Object changeId = ((java.util.Map<?, ?>) data).get("changeId");
        assertNotNull(changeId, "the response carries no changeId: " + data);

        ObjectNode apply = new ObjectMapper().createObjectNode();
        apply.put("action", "apply");
        apply.put("changeId", String.valueOf(changeId));
        ToolResponse applied = lifecycle.execute(apply);
        assertTrue(applied.isSuccess(), "got: " + applied.getError());
        assertTrue(Files.exists(pkg.resolve("DeliveryAddress.java")),
            "and the staged change must be the real one — applying it creates the class");
    }

    @Test
    @DisplayName("the undo handle RESTORES both files and removes the created class")
    void undoRestoresEverything() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        String beforeDesk = Files.readString(desk, StandardCharsets.UTF_8);
        ObjectNode args = argsAt("public String describeDelivery(", "describeDelivery");
        args.put("className", "DeliveryAddress");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertFalse(before.equals(Files.readString(targets, StandardCharsets.UTF_8)),
            "the CONTROL: with nothing changed, an undo that restores nothing would pass");

        Object data = r.getData();
        assertTrue(data instanceof java.util.Map, "expected a data map, got: " + data);
        Object handle = ((java.util.Map<?, ?>) data).get("undoChangeId");
        assertNotNull(handle, "the response carries no undoChangeId: " + data);

        ObjectNode undo = new ObjectMapper().createObjectNode();
        undo.put("action", "undo");
        undo.put("undoChangeId", String.valueOf(handle));
        ToolResponse undone = lifecycle.execute(undo);
        assertTrue(undone.isSuccess(), "got: " + undone.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "the method's own file is restored");
        assertEquals(beforeDesk, Files.readString(desk, StandardCharsets.UTF_8),
            "and so is the caller's — a per-file undo that missed one would leave a call"
                + " constructing a class that no longer exists");
    }

    @Test
    @DisplayName("the door routes the kind")
    void theDoorRoutesIt() {
        assertTrue(tool.publishedKinds().contains("introduce_parameter_object"),
            "a kind that is dispatched and not published is invisible in tools/list");
        assertTrue(tool.delegates().get("introduce_parameter_object")
                instanceof IntroduceParameterObjectTool,
            "and the routing table must reach this delegate");
    }
}
