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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 10, Encapsulate Record, on CODE WE DID NOT AUTHOR.
 *
 * <p>Upstream's {@code DatabaseService} carries one public instance field, {@code dataTypeDb},
 * set by its constructor and read twice by its own methods. That is the bare-record shape
 * exactly, and it was found by a census rather than by browsing: over the fork's 1354 main
 * sources there are 28 public instance fields, and this is one of them in a module already
 * vendored for another row.</p>
 *
 * <p><b>What foreign code adds over the fixture</b> is that nobody wrote it for this
 * operation. The fixture's class was authored knowing what Encapsulate Record does — one
 * public field, one constant beside it, one reader in another file — and a rule that was
 * subtly wrong about, say, which fields to skip would still pass it. Upstream's class has
 * private static finals, a {@code DataSource}, real method bodies reading the field in
 * conditionals, and javadoc that names the field; the operation has to leave all of that
 * alone and rewrite only what it must.</p>
 */
class EncapsulateRecordForkSliceTest {

    private static final String PKG = "src/main/java/com/iluwatar/slob/dbservice";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DataTool tool;
    private Path service;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl jdt = helper.loadProjectCopy("fork-serialized-lob");
        tool = new DataTool(() -> jdt, new org.jawata.mcp.refactoring.RefactoringChangeCache());
        service = jdt.getProjectRoot().resolve(PKG).resolve("DatabaseService.java");
    }

    @Test
    @DisplayName("upstream's bare public field is encapsulated, and its own readers follow")
    void encapsulatesUpstreamsPublicField() throws Exception {
        String before = Files.readString(service, StandardCharsets.UTF_8);
        String[] lines = before.split("\n", -1);
        int line = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("class DatabaseService")) {
                line = i;
                break;
            }
        }
        assertTrue(line >= 0, "the vendored slice no longer declares DatabaseService");

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "encapsulate_record");
        args.put("filePath", service.toString());
        args.put("line", line);
        args.put("column", lines[line].indexOf("DatabaseService"));

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(service, StandardCharsets.UTF_8);
        assertFalse(after.contains("public String dataTypeDb;"),
            "the field must stop being public — that is the whole subject:\n" + after);
        assertTrue(after.contains("private String dataTypeDb;"),
            "and become the class's own:\n" + after);
        assertTrue(after.contains("public String getDataTypeDb()"),
            "with a way in for the callers that reached it by name:\n" + after);
        // UPSTREAM'S OWN READERS, which is the half a fixture cannot make convincing: these
        // are inside conditionals in real method bodies nobody wrote for this operation.
        assertTrue(after.contains("getDataTypeDb().equals(BINARY_DATA)"),
            "its own reads become calls, or the encapsulation breaks the class it just"
                + " encapsulated:\n" + after);
    }
}
