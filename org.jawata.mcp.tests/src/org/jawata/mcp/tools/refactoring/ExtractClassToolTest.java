package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ExtractTool;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d Stage 7 / S7.1 — {@code extract(kind=class)} through the FRONT DOOR.
 *
 * <p>S7.0 proved JDT's engine runs headlessly by driving it directly. This asserts
 * the thing a user actually reaches: the {@code extract} tool, dispatching on
 * {@code kind=class}. The distinction matters — a capability that works when
 * called from a test and is not wired to the front door is the exact shape this
 * project keeps shipping, most recently a recall engine that was green 1591/1591
 * while production never constructed it.</p>
 *
 * <p><b>The fixture.</b> {@code PointBuilder} in the sample project carries
 * {@code label} and {@code unit} — a field pair disjoint from the rest of its
 * state, which is the shape Extract Class exists to move. {@code loadProjectCopy}
 * works on a temp copy, so the sample project on disk is never touched.</p>
 *
 * <p><b>What this does NOT assert.</b> The done-definition — that the old shape
 * is GONE, established by a reference query returning zero rather than by reading
 * the diff — is S7.2. Parity and undo are S7.3. And the before-case here is a
 * FIXTURE, which C7 rules insufficient in terms: the operation must also be shown
 * on code we did not author, which is S7.4.</p>
 */
class ExtractClassToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ExtractTool tool;
    private ObjectMapper mapper;
    private Path targetFile;
    private Path pkgDir;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        tool = new ExtractTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        pkgDir = helper.getTempDirectory().resolve("simple-maven/src/main/java/com/example");
        targetFile = pkgDir.resolve("SrpCohesionTargets.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    /** PointBuilder's declaration sits at 0-based line 42 in the fixture. */
    private ObjectNode args(String newTypeName, String... fields) {
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", "class");
        n.put("filePath", targetFile.toString());
        n.put("line", 42);
        n.put("column", 6);
        n.put("newTypeName", newTypeName);
        ArrayNode arr = n.putArray("fields");
        for (String f : fields) {
            arr.add(f);
        }
        return n;
    }

    @Test
    @DisplayName("S7.1: the front door extracts a field group into a new class")
    void theFrontDoorExtractsAFieldGroup() throws Exception {
        String before = Files.readString(targetFile);
        assertTrue(before.contains("private String label;") && before.contains("private String unit;"),
            "PROOF OF LIFE: the fixture must declare both fields before the move, or a green"
                + " below would mean the operation moved nothing");

        ToolResponse r = tool.execute(args("PointMeta", "label", "unit"));
        assertTrue(r.isSuccess(),
            () -> "extract(kind=class) failed: " + r.getError());

        Map<String, Object> data = getData(r);
        assertNotNull(data.get("filesModified"), "the response must name what it changed");
        assertNotNull(data.get("undoChangeId"),
            "every applied refactoring owes an undo handle — a mutating tool that returns no"
                + " way back has moved the risky half of the work onto the caller");
        assertEquals("PointMeta", data.get("newClass"));
        assertEquals("PointBuilder", data.get("extractedFrom"));

        Path created = pkgDir.resolve("PointMeta.java");
        assertTrue(Files.exists(created),
            "the extracted class must exist as its own file (createTopLevel defaults true)");
        String extracted = Files.readString(created);
        assertTrue(extracted.contains("label") && extracted.contains("unit"),
            () -> "the moved fields must live in the new class now:\n" + extracted);
    }

    /**
     * S7.2 — THE DONE-DEFINITION, and it is asserted by QUERY.
     *
     * <p>Stage 7's binding rule is that an operation "migrates references and leaves
     * the old shape GONE — checked by query, zero or not done". The distinction from
     * reading the diff is the whole point: a diff shows what the tool BELIEVES it
     * did, and a refactoring that adds the new class while leaving the old fields
     * behind produces a diff that looks entirely correct. Only asking the model
     * whether the field still exists can tell those apart.</p>
     *
     * <p>The type is re-resolved BY NAME rather than by the original coordinates,
     * because the extraction moves lines — a position-based lookup afterwards could
     * land on a different type and answer confidently about the wrong one.</p>
     */
    @Test
    @DisplayName("S7.2: the moved fields are GONE from the original, established by query")
    void theOldShapeIsGoneCheckedByQuery() throws Exception {
        ICompilationUnit cu = service.getCompilationUnit(targetFile);
        IType before = cu.getType("PointBuilder");
        assertTrue(before.getField("label").exists() && before.getField("unit").exists(),
            "PROOF OF LIFE: both fields must be ON the type before the move, or 'they are"
                + " gone' afterwards is true of a type that never had them");

        ToolResponse r = tool.execute(args("PointMeta", "label", "unit"));
        assertTrue(r.isSuccess(), () -> "extract(kind=class) failed: " + r.getError());

        IType after = service.getCompilationUnit(targetFile).getType("PointBuilder");
        assertTrue(after.exists(),
            "the original type must still exist — Extract Class moves state out of it, it"
                + " does not delete it");
        assertFalse(after.getField("label").exists(),
            "'label' is still declared on PointBuilder. The operation is not done: the new"
                + " class exists AND the old field survives, so the state now has two homes"
                + " and the next reader cannot tell which one is authoritative");
        assertFalse(after.getField("unit").exists(),
            "'unit' is still declared on PointBuilder — same defect as above");

        // And the state must have ARRIVED, not merely departed. Without this, deleting
        // the fields outright would satisfy every assertion above.
        IType extracted = service.getCompilationUnit(pkgDir.resolve("PointMeta.java"))
            .getType("PointMeta");
        assertTrue(extracted.exists(), "the extracted type must exist as a real type");
        assertTrue(extracted.getField("label").exists() && extracted.getField("unit").exists(),
            "both fields must now be declared on PointMeta. Gone-from-the-original is only"
                + " half the done-definition; a refactoring that dropped them would pass the"
                + " other half");
    }

    @Test
    @DisplayName("S7.1 refusal: a field that is not there is NAMED, and nothing is written")
    void anUnknownFieldIsRefusedByName() throws Exception {
        String before = Files.readString(targetFile);

        ToolResponse r = tool.execute(args("PointMeta", "label", "noSuchFieldAnywhere"));
        assertFalse(r.isSuccess(), "a field that does not exist must not silently be skipped —"
            + " that would extract a smaller class than asked for and report success");
        assertTrue(r.getError().getMessage().contains("noSuchFieldAnywhere"),
            () -> "the refusal must NAME the offending field: " + r.getError().getMessage());

        assertEquals(before, Files.readString(targetFile),
            "a refused operation must leave the source byte-identical");
        assertFalse(Files.exists(pkgDir.resolve("PointMeta.java")),
            "a refused operation must not leave a half-created class behind");
    }

    @Test
    @DisplayName("S7.1 refusal: naming no fields is refused rather than defaulted")
    void namingNoFieldsIsRefused() throws Exception {
        ToolResponse r = tool.execute(args("PointMeta"));
        assertFalse(r.isSuccess(),
            "an empty field list must be refused. Defaulting to 'all fields' or 'none' would"
                + " make the tool guess the one design decision the caller is there to make");
        assertFalse(Files.exists(pkgDir.resolve("PointMeta.java")));
    }
}
