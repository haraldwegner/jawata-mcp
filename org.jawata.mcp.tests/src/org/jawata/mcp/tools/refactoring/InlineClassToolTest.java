package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.InlineTool;
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
 * Sprint 28d-rescue, row 17 — Inline Class, through the {@code inline} front door.
 *
 * <p>This one DELETES a file, so the refusals matter more than the happy path. Each one
 * below is a way the operation would destroy code rather than reshape it, and the fixture
 * carries a real case for the sharpest of them: a class with two users, where folding it
 * into either leaves the other reaching for a type that is gone.</p>
 */
class InlineClassToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private InlineTool tool;
    private ObjectMapper mapper;
    private Path pkg;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new InlineTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        pkg = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example");
    }

    private String read(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private int lineOf(Path file, String marker) throws Exception {
        String[] lines = read(file).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                return i;
            }
        }
        throw new AssertionError("PROOF OF LIFE: " + file.getFileName() + " no longer has "
            + marker);
    }

    private ToolResponse inline(Path file, String marker, int column) throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "class");
        args.put("filePath", file.toString());
        args.put("line", lineOf(file, marker));
        args.put("column", column);
        return tool.execute(args);
    }

    @Test
    @DisplayName("the class folds into its single user, its accesses lose the holder, and the file goes")
    void theClassIsFoldedIn() throws Exception {
        Path source = pkg.resolve("InlineMe.java");
        Path absorber = pkg.resolve("InlineAbsorber.java");
        assertTrue(read(absorber).contains("helper.record()"),
            "PROOF OF LIFE: the user must reach through the holder before this runs");

        ToolResponse r = inline(source, "public class InlineMe", 13);
        assertTrue(r.isSuccess(), "the inline must run; got: " + r.getError());

        String after = read(absorber);
        assertTrue(after.contains("private int calls"),
            "the members arrived in the absorber:\n" + after);
        assertTrue(after.contains("return record()") || after.contains("record();"),
            "and the accesses lost the holder qualifier:\n" + after);
        assertFalse(after.contains("helper.record()"),
            "nothing still reaches through the holder:\n" + after);
        assertFalse(after.contains("private final InlineMe helper"),
            "and the holder field is gone:\n" + after);
        // A file, not just a declaration. This is the assertion that makes it Inline
        // CLASS rather than a members-only move.
        assertFalse(Files.exists(source), "the inlined class's file must be deleted");
    }

    @Test
    @DisplayName("a class with TWO users is refused — there is no single destination")
    void twoUsersIsRefused() throws Exception {
        Path shared = pkg.resolve("SharedHelper.java");
        ToolResponse r = inline(shared, "public class SharedHelper", 13);

        assertFalse(r.isSuccess(), "folding it into one user strands the other");
        assertTrue(String.valueOf(r.getError()).contains("2 classes"),
            "and the refusal must say how many, so the caller knows what to fix: "
                + r.getError());
        assertTrue(Files.exists(shared), "nothing may be deleted on a refusal");
        assertTrue(read(pkg.resolve("SharedUserTwo.java")).contains("helper.value()"),
            "and the second user is untouched");
    }
}
