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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mcp#63 — {@code data kind=add_record_component}.
 *
 * <p>{@code change_method_signature} refuses a record's canonical constructor because the
 * language fixes its parameter list to the record's components. The constructor is
 * DOWNSTREAM of the header, so the header is the edit — and every {@code new} must pass the
 * new component. That cross-file half is what these tests assert, because a run that changed
 * the header and left the constructions alone would not compile.</p>
 *
 * <p>The three canonical-constructor shapes are all in the fixture on purpose: IMPLICIT (the
 * commonest, and the one the issue was filed about), COMPACT (handled — its parameter list
 * is implicit, so it needs no edit) and EXPLICIT (refused — its body assigns each component,
 * so a new one needs an assignment this operation would be INVENTING).</p>
 */
class AddRecordComponentToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DataTool tool;
    private Path targets;
    private Path desk;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new DataTool(() -> service, new org.jawata.mcp.refactoring.RefactoringChangeCache());
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example");
        targets = pkg.resolve("RecordComponentTargets.java");
        desk = pkg.resolve("RecordComponentDesk.java");
    }

    private ObjectNode args(String typeName, String type, String name, String value) {
        ObjectNode a = new ObjectMapper().createObjectNode();
        a.put("kind", "add_record_component");
        a.put("typeName", typeName);
        a.put("componentType", type);
        a.put("componentName", name);
        if (value != null) {
            a.put("defaultValue", value);
        }
        return a;
    }

    private static String reason(ToolResponse r) {
        return r.getError() == null ? "" : String.valueOf(r.getError().getReason());
    }

    /** The whole error, for a failure message that has to explain itself. */
    private static String detail(ToolResponse r) {
        return r.getError() == null ? "(no error)" : r.getError().getReason()
            + " / " + r.getError().getCode() + " / " + r.getError().getMessage();
    }

    private String read(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the header gains the component and EVERY construction passes it, across files")
    void addsTheComponentAndMigratesEveryConstruction() throws Exception {
        ToolResponse response = tool.execute(
            args("com.example.RecordComponentTargets.Reading", "long", "takenAt", "0L"));
        assertTrue(response.isSuccess(), () -> "expected success, got " + detail(response));

        String header = read(targets);
        String sites = read(desk);
        assertAll(
            () -> assertTrue(
                header.contains("record Reading(String sensor, int celsius, long takenAt)"),
                "the HEADER carries the new component: " + header),
            // The cross-file half. Without it the header change does not compile, so a test
            // that only looked at the header would pass over a broken tree.
            () -> assertTrue(
                sites.contains("new RecordComponentTargets.Reading(\"north\", 21, 0L)"),
                "the first construction passes the default: " + sites),
            () -> assertTrue(
                sites.contains("new RecordComponentTargets.Reading(\"south\", 19, 0L)"),
                "the second construction passes it too: " + sites),
            // Nothing this operation was not pointed at moves.
            () -> assertTrue(sites.contains("new RecordComponentTargets.Bounded(1, 10)"),
                "an unrelated record's construction is untouched: " + sites));
    }

    @Test
    @DisplayName("a COMPACT canonical constructor is handled — its parameter list is implicit")
    void aCompactCanonicalConstructorNeedsNoEdit() throws Exception {
        ToolResponse response = tool.execute(
            args("com.example.RecordComponentTargets.Bounded", "String", "unit", "\"C\""));
        assertTrue(response.isSuccess(), () -> "compact must be handled, got " + detail(response));

        String header = read(targets);
        assertAll(
            () -> assertTrue(header.contains("record Bounded(int low, int high, String unit)"),
                "the header gains the component: " + header),
            // The compact constructor's body is untouched — it declares no parameter list, so
            // there is nothing in it that names the components.
            () -> assertTrue(header.contains("if (low > high)"),
                "the compact constructor's own check survives: " + header),
            () -> assertTrue(read(desk).contains("new RecordComponentTargets.Bounded(1, 10, \"C\")"),
                "its construction passes the default: " + read(desk)));
    }

    @Test
    @DisplayName("an EXPLICIT canonical constructor is refused — its body would need invented code")
    void refusesAnExplicitCanonicalConstructor() throws Exception {
        ToolResponse response = tool.execute(
            args("com.example.RecordComponentTargets.Labelled", "String", "unit", "\"kg\""));
        assertFalse(response.isSuccess(), "an explicit canonical constructor must refuse");
        assertEquals("EXPLICIT_CANONICAL_CONSTRUCTOR", reason(response));
        assertTrue(read(targets).contains("record Labelled(String name, int weight)"),
            "and the header is untouched by a refusal");
    }

    @Test
    @DisplayName("a CLASS is refused and pointed at change_method_signature, which performs it")
    void refusesAClass() throws Exception {
        ToolResponse response = tool.execute(
            args("com.example.RecordComponentTargets", "int", "extra", "0"));
        assertFalse(response.isSuccess(), "a class is not this operation's subject");
        assertEquals("NOT_A_RECORD", reason(response));
        assertTrue(String.valueOf(response.getError().getMessage())
                .contains("change_method_signature"),
            "the refusal names the operation that DOES perform it: " + response.getError());
    }

    @Test
    @DisplayName("a component name the record already declares is refused, not suffixed")
    void refusesANameTheRecordAlreadyDeclares() throws Exception {
        ToolResponse response = tool.execute(
            args("com.example.RecordComponentTargets.Reading", "int", "celsius", "0"));
        assertFalse(response.isSuccess(), "a duplicate component does not compile");
        assertEquals("COMPONENT_NAME_TAKEN", reason(response));
    }

    @Test
    @DisplayName("existing constructions with no defaultValue are refused — a value that compiles is the worst guess")
    void refusesWhenConstructionsExistAndNoDefaultIsGiven() throws Exception {
        ToolResponse response = tool.execute(
            args("com.example.RecordComponentTargets.Reading", "long", "takenAt", null));
        assertFalse(response.isSuccess(), "the constructions need a value");
        assertEquals("NO_DEFAULT_FOR_EXISTING_CALLS", reason(response));

        // THE CONTROL. A record nothing constructs owes no default, so the same missing
        // argument SUCCEEDS there — which is what proves the refusal is about the call sites
        // rather than about defaultValue being absent.
        ToolResponse unbuilt = tool.execute(
            args("com.example.RecordComponentTargets.Unbuilt", "int", "count", null));
        assertTrue(unbuilt.isSuccess(),
            () -> "a record with no construction owes no default, got " + detail(unbuilt));
        assertTrue(read(targets).contains("record Unbuilt(String only, int count)"),
            "and it still gains the component: " + read(targets));
    }
}
