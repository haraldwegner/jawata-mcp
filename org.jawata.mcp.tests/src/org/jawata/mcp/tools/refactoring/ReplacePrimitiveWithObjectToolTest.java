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
 * Stage 5, row 54 — Replace Primitive with Object, reached as {@code data
 * kind=replace_primitive}.
 *
 * <p>A primitive field that has grown a meaning gets its own type, and — the half that
 * distinguishes this row from the shipped {@code replace_type_code_with_class} — every usage
 * of it is migrated. The fixture is {@code ReplacePrimitiveTargets.java}, which carries the
 * canonical case beside each shape the operation must refuse, plus
 * {@code ReplacePrimitiveUser.java}, which reads and writes a field from ANOTHER file.</p>
 *
 * <p><b>What is asserted is the MIGRATION, not merely the generated type.</b> A run that
 * produced the record and retyped the declaration and left the usages alone would satisfy a
 * test that only looked for {@code record Priority} — and would not compile, because every
 * reader would then be handed a record where a String was expected. So each test names the
 * rewritten usage it expects, and the cross-file test asserts it in the file the operation
 * was not pointed at.</p>
 */
class ReplacePrimitiveWithObjectToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DataTool tool;
    private Path targets;
    private Path user;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new DataTool(() -> service, new org.jawata.mcp.refactoring.RefactoringChangeCache());
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example");
        targets = pkg.resolve("ReplacePrimitiveTargets.java");
        user = pkg.resolve("ReplacePrimitiveUser.java");
    }

    /**
     * The caret on a field NAME, which is what {@code getElementAtPosition} needs to resolve
     * an {@code IField}. The column is derived from the line rather than counted by hand, so
     * a reformat of the fixture moves the test with it instead of breaking it.
     */
    private ToolResponse at(String declaration, String fieldName) throws Exception {
        String[] lines = Files.readString(targets, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(declaration)) {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "replace_primitive");
                args.put("filePath", targets.toString());
                args.put("line", i);
                args.put("column", lines[i].indexOf(fieldName));
                return tool.execute(args);
            }
        }
        throw new AssertionError("the fixture no longer declares: " + declaration);
    }

    @Test
    @DisplayName("the canonical case: a record generated AND every usage migrated to it")
    void replacesAPrimitiveAndMigratesItsUsages() throws Exception {
        ToolResponse r = at("private String priority;", "priority");
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        assertTrue(after.contains("public record Priority(String value)"),
            "the value type must be generated as a record — the language's own spelling of a"
                + " value object, which supplies equals/hashCode/toString:\n" + after);
        assertTrue(after.contains("private Priority priority;"),
            "and the field must be retyped to it:\n" + after);

        // THE MIGRATION, per usage kind. Without these the test would pass on a run that
        // generated the type and left every reader broken.
        assertTrue(after.contains("this.priority = new Priority(priority);"),
            "a write is WRAPPED — the constructor's parameter is still a String:\n" + after);
        assertTrue(after.contains("\"order at \" + priority.value()"),
            "a read is UNWRAPPED through the record's accessor:\n" + after);
        assertTrue(after.contains("\"high\".equals(priority.value())"),
            "including a read that is an argument rather than an operand:\n" + after);
        assertTrue(after.contains("priority = new Priority(\"high\");"),
            "and a bare assignment outside the constructor is wrapped too:\n" + after);
    }

    @Test
    @DisplayName("the usages in ANOTHER file are migrated — the claim that distinguishes this row")
    void migratesUsagesInOtherFiles() throws Exception {
        ToolResponse r = at("public String carrier", "carrier");
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String other = Files.readString(user, StandardCharsets.UTF_8);
        // THE WHOLE STATEMENT, not a fragment of it. The first version of this test asserted
        // `shipment.carrier.value()` and the fixture's own javadoc contained that string, so
        // the test passed on a comment and would have passed with no rewrite at all. The
        // parity golden caught it by disagreeing with a green test; the fixture's comments no
        // longer quote any output, and these assertions name statements a comment cannot be.
        assertTrue(other.contains("return \"manifest: \" + shipment.carrier.value();"),
            "a read in a file this call was never pointed at must be unwrapped, or the"
                + " operation has retyped a field and broken its readers:\n" + other);
        // The record is NESTED in the field's declaring type — Shipment, not the outer
        // ReplacePrimitiveTargets — so from outside it is reached through THAT owner.
        assertTrue(other.contains("shipment.carrier = new Shipment.Carrier(name);"),
            "and a write there must construct through the type that declares the field,"
                + " since the record is nested in it:\n" + other);
    }

    @Test
    @DisplayName("a write whose value READS the same field unwraps inside the wrapping")
    void unwrapsAReadNestedInItsOwnWrite() throws Exception {
        ToolResponse r = at("private int total;", "total");
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        // `total = total + amount` — the read is INSIDE the value being wrapped, so the two
        // rewrites are one edit or they conflict. The wrong answers are visible in the text:
        // `new Total(total + amount)` does not compile, and `new Total(total).value() + ...`
        // is a different expression.
        assertTrue(after.contains("total = new Total(total.value() + amount);"),
            "the value is wrapped AND the read inside it unwrapped, in one edit:\n" + after);
        assertTrue(after.contains("return total.value();"),
            "and the ordinary read elsewhere is untouched by that special case:\n" + after);
    }

    @Test
    @DisplayName("REFUSES a static final constant, naming the positions that take no object")
    void refusesAConstant() throws Exception {
        ToolResponse r = at("public static final int MAX_ITEMS", "MAX_ITEMS");
        assertFalse(r.isSuccess(), "a constant may be a case label or an annotation value");
        assertTrue(String.valueOf(r.getError()).contains("static final"),
            "the refusal must name that reason: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a field changed in place — there is no single right rewrite")
    void refusesACompoundAssignment() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = at("private int hits;", "hits");
        assertFalse(r.isSuccess(), "`hits += 1` has no mechanical wrapping");
        assertTrue(String.valueOf(r.getError()).contains("changed in place"),
            "the refusal must name that reason: " + r.getError());
        assertTrue(before.equals(Files.readString(targets, StandardCharsets.UTF_8)),
            "and a refusal must leave the file byte-for-byte untouched");
    }

    @Test
    @DisplayName("REFUSES a shared declaration, which would have to be split first")
    void refusesASharedDeclaration() throws Exception {
        ToolResponse r = at("private int width, height;", "width");
        assertFalse(r.isSuccess(), "retyping one fragment of `int width, height;` means"
            + " splitting the line, which is a different edit");
        assertTrue(String.valueOf(r.getError()).contains("alongside others on one line"),
            "the refusal must name that reason: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a field that is already an object")
    void refusesANonPrimitive() throws Exception {
        ToolResponse r = at("private java.util.List<String> items", "items");
        assertFalse(r.isSuccess(), "there is no primitive to replace");
        assertTrue(String.valueOf(r.getError()).contains("nothing to replace"),
            "the refusal must name that reason: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a name the declaring type already declares")
    void refusesANameCollision() throws Exception {
        // Ticket.severity would default to the name `Severity`, which Ticket already has.
        // The fixture carries the clash so the guard is exercised on its own, rather than
        // reached as a side effect of a second run — after which the field is no longer
        // primitive and a DIFFERENT refusal fires, which would prove nothing about this one.
        ToolResponse r = at("private String severity;", "severity");
        assertFalse(r.isSuccess(), "generating over the existing type would not compile");
        assertTrue(String.valueOf(r.getError()).contains("already declares a nested"),
            "the refusal must name the clash: " + r.getError());
        assertTrue(String.valueOf(r.getError()).contains("typeName"),
            "and it must point at the way out — the caller can choose another name: "
                + r.getError());
    }

    @Test
    @DisplayName("a caller-supplied typeName is honoured, and clears the clash")
    void acceptsARequestedTypeName() throws Exception {
        // The control for the refusal above. Without it, the clash test would pass equally
        // well on a tool that refused EVERY name, and the parameter would be decoration.
        String[] lines = Files.readString(targets, StandardCharsets.UTF_8).split("\n", -1);
        int line = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("private String severity;")) {
                line = i;
            }
        }
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "replace_primitive");
        args.put("filePath", targets.toString());
        args.put("line", line);
        args.put("column", lines[line].indexOf("severity"));
        args.put("typeName", "SeverityLevel");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "the name Ticket does NOT declare must be accepted: "
            + r.getError());
        String after = Files.readString(targets, StandardCharsets.UTF_8);
        assertTrue(after.contains("public record SeverityLevel(String value)"),
            "and the caller's name is what gets generated:\n" + after);
        assertTrue(after.contains("\"ticket \" + severity.value()"),
            "with the usages migrated as in every other case:\n" + after);
    }

    @Test
    @DisplayName("the kind is routed and published by the door")
    void theDoorRoutesIt() {
        assertTrue(tool.delegates().containsKey("replace_primitive"),
            "row 54 must be reachable as data kind=replace_primitive");
        assertEquals("replace_primitive",
            tool.delegates().get("replace_primitive").kindName(),
            "the routing key and the delegate's own name are two spellings of one fact");
        assertTrue(tool.publishedKinds().contains("replace_primitive"),
            "and a client reading tools/list must see it — the enum is the routing table");
    }
}
