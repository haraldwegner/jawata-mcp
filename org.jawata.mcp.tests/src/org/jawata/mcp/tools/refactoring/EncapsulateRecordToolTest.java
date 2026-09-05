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
 * Stage 5, row 10 — Encapsulate Record, as {@code data kind=encapsulate_record}.
 *
 * <p>The row is {@code encapsulate_field} run over every public field of a class, through the
 * recipe engine, so what this file has to establish beyond the single-field operation is: that
 * every field is taken rather than the first, that the fields it must NOT take are skipped and
 * named, and that a reader in ANOTHER file follows — the accesses that matter are the ones
 * nobody pointed the tool at.</p>
 *
 * <p>The target is a MEMBER class on purpose. Between steps the row re-resolves its type, and
 * a nested class is where a name-keyed lookup goes wrong: row 54 met that as a silent no-op.
 * A top-level fixture would pass whether or not the resolution handles one.</p>
 */
class EncapsulateRecordToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DataTool tool;
    private org.jawata.mcp.tools.RefactoringTool lifecycle;
    private Path targets;
    private Path user;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        org.jawata.mcp.refactoring.RefactoringChangeCache cache =
            new org.jawata.mcp.refactoring.RefactoringChangeCache();
        tool = new DataTool(() -> service, cache);
        lifecycle = new org.jawata.mcp.tools.RefactoringTool(() -> service, cache);
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example");
        targets = pkg.resolve("EncapsulateRecordTargets.java");
        user = pkg.resolve("EncapsulateRecordUser.java");
    }

    private ToolResponse at(String declaration, String className) throws Exception {
        String[] lines = Files.readString(targets, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(declaration)) {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "encapsulate_record");
                args.put("filePath", targets.toString());
                args.put("line", i);
                args.put("column", lines[i].indexOf(className));
                return tool.execute(args);
            }
        }
        throw new AssertionError("the fixture no longer declares: " + declaration);
    }

    @Test
    @DisplayName("every public field is encapsulated, the constant is skipped, and a reader "
        + "in another file follows")
    void encapsulatesEveryPublicField() throws Exception {
        ToolResponse r = at("public static class Coordinate", "Coordinate");
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        assertTrue(after.contains("private double latitude;"),
            "the first field is now the class's own:\n" + after);
        assertTrue(after.contains("private double longitude;"),
            "and so is the SECOND — a row that took only the first would pass every"
                + " single-field assertion:\n" + after);
        assertTrue(after.contains("public double getLatitude()")
                && after.contains("public double getLongitude()"),
            "with an accessor apiece:\n" + after);
        assertTrue(after.contains("public static final String DATUM"),
            "the constant is SKIPPED and left exactly as it was — a getter in front of a"
                + " compile-time constant buys nothing:\n" + after);

        String other = Files.readString(user, StandardCharsets.UTF_8);
        assertTrue(other.contains("where.getLatitude()") && other.contains("where.getLongitude()"),
            "and the reads in a file this call was never pointed at become calls, or the"
                + " encapsulation leaves them reading something private:\n" + other);
    }

    @Test
    @DisplayName("the skip is REPORTED, not silent")
    void namesWhatItSkipped() throws Exception {
        ToolResponse r = at("public static class Coordinate", "Coordinate");
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertTrue(String.valueOf(r.getData()).contains("DATUM"),
            "a caller who asked about a class and got fewer fields than it has must be told"
                + " which and why, rather than left to diff: " + r.getData());
    }

    @Test
    @DisplayName("REFUSES a class that already owns its state")
    void refusesAClassAlreadyEncapsulated() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = at("public static class Sealed", "Sealed");
        assertFalse(r.isSuccess(),
            "no public instance field is the state this operation PRODUCES, not one it acts"
                + " on — refusing it is the row's own control");
        assertTrue(String.valueOf(r.getError()).contains("no public instance field"),
            "the refusal must name that reason: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("REFUSES auto_apply=false, because a recipe has no single change to preview")
    void refusesToStageBecauseItIsARecipe() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        String[] lines = before.split("\n", -1);
        ToolResponse r = null;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("public static class Coordinate")) {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "encapsulate_record");
                args.put("filePath", targets.toString());
                args.put("line", i);
                args.put("column", lines[i].indexOf("Coordinate"));
                args.put("auto_apply", false);
                r = tool.execute(args);
                break;
            }
        }
        assertFalse(r == null, "the fixture no longer declares Coordinate");
        assertFalse(r.isSuccess(),
            "each step is built against the file the previous step rewrote, so there is no"
                + " staged change to show before the first has run");
        assertTrue(String.valueOf(r.getError()).contains("COMPOSED"),
            "the refusal must say WHY, and point at the halves a caller can stage: "
                + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("the ONE undo handle reverts the whole recipe, in both files")
    void undoRevertsEveryStep() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        String beforeUser = Files.readString(user, StandardCharsets.UTF_8);
        ToolResponse r = at("public static class Coordinate", "Coordinate");
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertFalse(before.equals(Files.readString(targets, StandardCharsets.UTF_8)),
            "the CONTROL: with nothing changed, an undo that restores nothing would pass");

        Object data = r.getData();
        assertTrue(data instanceof java.util.Map, "expected a data map, got: " + data);
        Object handle = ((java.util.Map<?, ?>) data).get("undoChangeId");
        assertTrue(handle != null, "the recipe must hand back ONE undo handle: " + data);

        ObjectNode undo = new ObjectMapper().createObjectNode();
        undo.put("action", "undo");
        undo.put("undoChangeId", String.valueOf(handle));
        ToolResponse undone = lifecycle.execute(undo);
        assertTrue(undone.isSuccess(), "got: " + undone.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "the class it rewrote is restored");
        assertEquals(beforeUser, Files.readString(user, StandardCharsets.UTF_8),
            "and so is the OTHER file — a per-step undo that missed one would leave a"
                + " caller reading an accessor that no longer exists");
    }

    @Test
    @DisplayName("acts on the class it was pointed at, not on a SIBLING of the same simple name")
    void leavesTheDecoySiblingUntouched() throws Exception {
        ToolResponse r = at("public static class Coordinate", "Coordinate");
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        // Legacy.Coordinate is declared FIRST and shares the target's simple name, so a recipe
        // that carries its target between steps as a name lands here at every step.
        assertTrue(after.contains("public String datum;"),
            "the decoy sibling's public field must be exactly as it was — encapsulating it is"
                + " a rewrite of a class nobody pointed at:\n" + after);
        assertFalse(after.contains("getDatum()"),
            "and it must gain no accessor:\n" + after);
    }

    @Test
    @DisplayName("runs from the class's TYPE NAME, with no file position given")
    void runsFromItsTypeName() throws Exception {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "encapsulate_record");
        args.put("typeName", "com.example.EncapsulateRecordTargets.Coordinate");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertTrue(Files.readString(targets, StandardCharsets.UTF_8)
                .contains("private double latitude;"),
            "the name form must reach the same class the caret does");
    }
}
