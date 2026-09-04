package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.MoveTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue, row 23 — Move Field, driven through the {@code move} front door.
 *
 * <p>The assertion that matters is the THIRD file. A move that relocates the declaration
 * and leaves a reader behind is the obvious way to get this wrong, and it is invisible in
 * a two-file fixture — so the fixture has a class that reads the field from outside, and
 * the test checks that reference travelled.</p>
 */
class MoveFieldToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private MoveTool tool;
    private ObjectMapper mapper;
    private Path source;
    private Path target;
    private Path user;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new MoveTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        Path root = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example");
        source = root.resolve("MoveFieldSource.java");
        target = root.resolve("MoveFieldTarget.java");
        user = root.resolve("MoveFieldUser.java");
    }

    private String read(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** Zero-based line of the first line containing this marker, in the source file. */
    private int lineOf(String marker) throws Exception {
        String[] lines = read(source).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                return i;
            }
        }
        throw new AssertionError("PROOF OF LIFE: the fixture no longer contains " + marker);
    }

    private ObjectNode argsAt(int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "field");
        args.put("filePath", source.toString());
        args.put("line", line);
        args.put("column", column);
        args.put("targetType", "com.example.MoveFieldTarget");
        return args;
    }

    @Test
    @DisplayName("a static field moves, and the reader in ANOTHER class moves with it")
    void theStaticFieldAndItsOutsideReaderMove() throws Exception {
        assertTrue(read(user).contains("MoveFieldSource.SHARED_LIMIT"),
            "PROOF OF LIFE: the third file must read the field through its old owner,"
                + " or this test cannot tell a complete move from a broken one");

        ToolResponse r = tool.execute(argsAt(lineOf("SHARED_LIMIT = 42"), 28));
        assertTrue(r.isSuccess(), "the move must run; got: " + r.getError());

        assertFalse(read(source).contains("SHARED_LIMIT = 42"),
            "the declaration left the source:\n" + read(source));
        assertTrue(read(target).contains("SHARED_LIMIT"),
            "and arrived in the existing destination:\n" + read(target));
        // THE POINT OF THE THIRD FILE.
        assertTrue(read(user).contains("MoveFieldTarget.SHARED_LIMIT"),
            "the outside reader must now read it from the destination — a move that"
                + " relocates a declaration and leaves its readers behind produces code"
                + " that does not compile:\n" + read(user));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("undoChangeId"), "and it is reversible: " + data);
    }

    @Test
    @DisplayName("an instance field is refused, and the refusal says what is missing")
    void anInstanceFieldIsRefused() throws Exception {
        String before = read(source);
        ToolResponse r = tool.execute(argsAt(lineOf("private int instanceCount"), 16));

        assertFalse(r.isSuccess(), "moving instance state needs a receiver nobody named");
        assertTrue(String.valueOf(r.getError()).contains("receiver"),
            "and the refusal must name the missing part rather than just decline: "
                + r.getError());
        assertEquals(before, read(source), "nothing may be written on a refusal");
    }

    @Test
    @DisplayName("moving a field to the class it already lives in is refused")
    void movingIntoItsOwnClassIsRefused() throws Exception {
        String before = read(source);
        ObjectNode args = argsAt(lineOf("SHARED_LIMIT = 42"), 28);
        args.put("targetType", "com.example.MoveFieldSource");

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "there is nothing to move");
        assertEquals(before, read(source), "nothing may be written on a refusal");
    }
}
