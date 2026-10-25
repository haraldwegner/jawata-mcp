package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.data.AddRecordComponentTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S3 / D7g — {@code add_record_component} can change the SHAPE instead of every
 * call site.
 *
 * <p>The field report: asked three times to add an optional factor with default
 * {@code Double.NaN}, the tool correctly rewrote <strong>78 construction sites in 24
 * files</strong>, and all three runs were undone. The wanted shape was one four-argument
 * secondary constructor in one file. The tool did what it says; what it did not do is notice
 * that the ask carried the answer — a sentinel default is a value no site wanted.</p>
 *
 * <p>No fixture was added for this. {@code RecordComponentTargets.Reading} already has an
 * implicit canonical constructor and two construction sites in a second file, which is
 * exactly the shape needed — and a new file in this shared project has moved a counted
 * population four times this sprint.</p>
 */
class AnOverloadLeavesEveryCallSiteAloneTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private AddRecordComponentTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new AddRecordComponentTool(() -> service, new RefactoringChangeCache());
        objectMapper = new ObjectMapper();
        projectPath = service.getProjectRoot();
    }

    @Test
    @DisplayName("viaSecondaryConstructor adds the overload and leaves every construction as it was")
    void theOverloadModeTouchesNoCallSite() throws Exception {
        String deskBefore = read("RecordComponentDesk.java");
        assertTrue(deskBefore.contains("new RecordComponentTargets.Reading(\"north\", 21)"),
            "the fixture's call sites are the subject; they must exist first");

        ToolResponse r = tool.execute(args(true, "Double.NaN"));
        assertTrue(r.isSuccess(), () -> "the overload mode failed: " + describe(r));

        String targets = read("RecordComponentTargets.java");
        assertTrue(targets.contains("public record Reading(String sensor, int celsius, double factor)"),
            () -> "the header must gain the component in either mode: " + targets);

        // THE OVERLOAD ITSELF, read off the output rather than predicted. Its parameter list
        // is the OLD one, and its body delegates with the default.
        assertTrue(targets.contains("public Reading(String sensor, int celsius)"),
            () -> "the secondary constructor must take the OLD component list: " + targets);
        assertTrue(targets.contains("this(sensor, celsius, Double.NaN);"),
            () -> "and delegate to the canonical one with the default: " + targets);

        // THE WHOLE POINT, and the discriminator against the default mode: the other file is
        // byte-for-byte what it was. 78 sites in 24 files is what this mode exists to avoid.
        assertEquals(deskBefore, read("RecordComponentDesk.java"),
            "viaSecondaryConstructor must leave every construction site untouched");

        // THE WHITESPACE IS ASSERTED, because a `contains` cannot see it and this sprint has
        // four recorded instances of generated code arriving TAB-indented into a
        // space-indented file. Read off a probe run rather than predicted.
        assertTrue(targets.contains("\n        public Reading(String sensor, int celsius) {\n"
                + "            this(sensor, celsius, Double.NaN);\n"),
            () -> "the generated constructor must be SPACE-indented at the record's own "
                + "nesting depth — 8 for the declaration, 12 for its body: "
                + targets.replace("\t", "<TAB>"));

        // A COSMETIC ARTEFACT, PINNED RATHER THAN FIXED, so it is visible instead of
        // discovered. The fixture declares `public record Reading(...) { }` — a one-line
        // body — so inserting a member leaves the record's own closing brace on the line
        // after the member's: `} }`. It is valid Java and the compile gate passed it. The
        // fix would be reformatting the whole record declaration, whose blast radius is
        // wider than the defect; a record with a multi-line body does not produce it.
        assertTrue(targets.contains("} }"),
            () -> "if this stops holding the engine's brace placement changed — re-read the "
                + "generated region before relaxing the assertion above: " + targets);
    }

    @Test
    @DisplayName("CONTROL: without it, the call sites are still migrated as before")
    void theDefaultModeStillMigratesEveryCallSite() throws Exception {
        ToolResponse r = tool.execute(args(false, "Double.NaN"));
        assertTrue(r.isSuccess(), () -> "the default mode failed: " + describe(r));

        String desk = read("RecordComponentDesk.java");
        assertTrue(desk.contains("new RecordComponentTargets.Reading(\"north\", 21, Double.NaN)"),
            () -> "the pre-existing behaviour must survive the new mode: " + desk);
        assertFalse(read("RecordComponentTargets.java")
                .contains("public Reading(String sensor, int celsius)"),
            "and it must NOT also generate an overload — that would be both at once");
    }

    @Test
    @DisplayName("a sentinel default suggests the overload, naming the call it should have been")
    void aSentinelSuggestsTheOverload() {
        ToolResponse r = tool.execute(args(false, "Double.NaN"));
        assertTrue(r.isSuccess(), () -> "run failed: " + describe(r));

        Map<?, ?> alternative = (Map<?, ?>) dataOf(r).get("overloadAlternative");
        assertNotNull(alternative,
            () -> "a NaN default is the report's own signal and must be answered: "
                + dataOf(r).keySet());
        assertEquals("data kind=add_record_component", alternative.get("operation"));

        Map<?, ?> suggested = (Map<?, ?>) alternative.get("arguments");
        assertEquals(Boolean.TRUE, suggested.get("viaSecondaryConstructor"),
            () -> "the suggestion must be RUNNABLE, not prose about one: " + alternative);
        assertEquals("Double.NaN", suggested.get("defaultValue"),
            () -> "and carry the caller's own default back: " + alternative);
        assertTrue(String.valueOf(alternative.get("why")).contains("sentinel"),
            () -> "the reason is the sentinel, not the count: " + alternative);
    }

    @Test
    @DisplayName("an ORDINARY default suggests nothing — the signal is the sentinel")
    void anOrdinaryDefaultSuggestsNothing() {
        ToolResponse r = tool.execute(args(false, "0.0"));
        assertTrue(r.isSuccess(), () -> "run failed: " + describe(r));
        assertNull(dataOf(r).get("overloadAlternative"),
            () -> "0.0 is a value a site could genuinely mean, so migrating the sites is a "
                + "legitimate answer and nothing should second-guess it: " + dataOf(r).keySet());
    }

    @Test
    @DisplayName("the overload mode still requires a defaultValue — its body has nothing else to pass")
    void theOverloadModeStillNeedsADefault() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.RecordComponentTargets.Reading");
        args.put("componentType", "double");
        args.put("componentName", "factor");
        args.put("viaSecondaryConstructor", true);
        // no defaultValue

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "a delegation with nothing to delegate is not a change");
        assertEquals("NO_DEFAULT_FOR_EXISTING_CALLS", r.getError().getReason(),
            () -> "the refusal must be the named one: " + describe(r));
        assertTrue(r.getError().getMessage().contains("secondary constructor"),
            () -> "and must say why THIS mode needs it, not quote a call-site count: "
                + describe(r));
    }

    private ObjectNode args(boolean viaOverload, String defaultValue) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.RecordComponentTargets.Reading");
        args.put("componentType", "double");
        args.put("componentName", "factor");
        args.put("defaultValue", defaultValue);
        if (viaOverload) {
            args.put("viaSecondaryConstructor", true);
        }
        return args;
    }

    private String read(String name) throws Exception {
        return Files.readString(projectPath.resolve("src/main/java/com/example/" + name));
    }

    private static String describe(ToolResponse r) {
        return r.isSuccess() ? String.valueOf(r.getData())
            : r.getError().getCode() + " / " + r.getError().getMessage();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }
}
