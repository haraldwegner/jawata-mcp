package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.InlineTool;
import org.jawata.mcp.tools.MoveTool;
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
 * CALLABLE BY NAME, exercised rather than declared.
 *
 * <p>The per-row contract says each row must be reachable "by symbol name and by file
 * position". The position half is what every {@code <Name>ToolTest} drives. The NAME half
 * was published on all three front doors and tested by nothing — a C6 audit counted zero
 * tests passing {@code symbol} or {@code typeName} anywhere in Stage 6, and it was right.
 * Worse, {@code inline}'s schema said the name form applied to {@code kind=method} only,
 * which stopped being true the moment the door took three type-targeted kinds.</p>
 *
 * <p>An agent that already knows a symbol's name should go straight to it — that is the
 * whole argument for the form, and a form nothing exercises is a claim. These are the two
 * shapes: a TYPE target (row 17) and a MEMBER target (row 23).</p>
 */
class Stage6NameFormTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ObjectMapper mapper;
    private Path pkg;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        mapper = new ObjectMapper();
        pkg = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example");
    }

    private String read(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a TYPE-targeted kind runs from a fully-qualified name, with no coordinates")
    void inlineClassRunsFromATypeName() throws Exception {
        Path source = pkg.resolve("InlineMe.java");
        assertTrue(Files.exists(source), "PROOF OF LIFE: the type must be there to name");

        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "class");
        // NO filePath, NO line, NO column. That absence is the assertion: the name form
        // materialises the position, and a test that also passed coordinates would prove
        // the coordinates work.
        args.put("symbol", "com.example.InlineMe");
        ToolResponse r = new InlineTool(() -> service, new RefactoringChangeCache())
            .execute(args);

        assertTrue(r.isSuccess(), "the name form must reach the operation; got: "
            + r.getError());
        assertFalse(Files.exists(source),
            "and it must have done the same work the positional call does");
        assertTrue(read(pkg.resolve("InlineAbsorber.java")).contains("private int calls"),
            "the members arrived:\n" + read(pkg.resolve("InlineAbsorber.java")));
    }

    @Test
    @DisplayName("a MEMBER-targeted kind runs from pkg.Type#member, with no coordinates")
    void moveFieldRunsFromAMemberName() throws Exception {
        Path source = pkg.resolve("MoveFieldSource.java");
        assertTrue(read(source).contains("SHARED_LIMIT"),
            "PROOF OF LIFE: the field must be there to name");

        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "field");
        args.put("symbol", "com.example.MoveFieldSource#SHARED_LIMIT");
        args.put("targetType", "com.example.MoveFieldTarget");
        ToolResponse r = new MoveTool(() -> service, new RefactoringChangeCache())
            .execute(args);

        assertTrue(r.isSuccess(), "the member name form must reach the operation; got: "
            + r.getError());
        assertTrue(read(pkg.resolve("MoveFieldTarget.java")).contains("SHARED_LIMIT"),
            "the field arrived:\n" + read(pkg.resolve("MoveFieldTarget.java")));
        assertFalse(read(source).contains("SHARED_LIMIT = 42"),
            "and left:\n" + read(source));
    }

    @Test
    @DisplayName("a name that does not resolve is refused, not silently ignored")
    void anUnresolvableNameIsRefused() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "class");
        args.put("symbol", "com.example.NoSuchTypeAnywhere");
        ToolResponse r = new InlineTool(() -> service, new RefactoringChangeCache())
            .execute(args);

        // The control on the two above: without it, a name form that resolved NOTHING and
        // fell through to a positional path with no coordinates would also look like a
        // refusal, and the successes would be the only evidence either way.
        assertFalse(r.isSuccess(), "a name nothing answers to cannot be a target");
        assertTrue(String.valueOf(r.getError()).contains("NoSuchTypeAnywhere"),
            "and the refusal must repeat the name, so a stale memory can correct itself: "
                + r.getError());
    }
}
