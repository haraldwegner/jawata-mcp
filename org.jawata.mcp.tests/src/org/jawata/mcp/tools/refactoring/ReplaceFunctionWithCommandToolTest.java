package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ExtractTool;
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
 * Sprint 28d-rescue, row 48 — Replace Function with Command, through {@code extract}.
 *
 * <p>The refusal test is the instance method beside the static one in the same fixture. A
 * command for an instance method has to carry the receiver as well, which is a field the
 * caller never named and a decision about whether it holds the object or what it read from
 * the object — so the tool declines instead of choosing.</p>
 */
class ReplaceFunctionWithCommandToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ExtractTool tool;
    private ObjectMapper mapper;
    private Path pkg;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new ExtractTool(() -> service, new RefactoringChangeCache());
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

    private ToolResponse toCommand(String marker, int column, String newTypeName)
            throws Exception {
        Path source = pkg.resolve("Scoring.java");
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "function_to_command");
        args.put("filePath", source.toString());
        args.put("line", lineOf(source, marker));
        args.put("column", column);
        args.put("newTypeName", newTypeName);
        return tool.execute(args);
    }

    @Test
    @DisplayName("the function becomes a command whose fields are its parameters")
    void theFunctionBecomesACommand() throws Exception {
        Path owner = pkg.resolve("Scoring.java");
        Path caller = pkg.resolve("ScoringCaller.java");
        assertTrue(read(caller).contains("Scoring.score(10, 2)"),
            "PROOF OF LIFE: the caller must call the function before this runs");

        ToolResponse r = toCommand("public static int score", 25, "ScoreCommand");
        assertTrue(r.isSuccess(), "the replacement must run; got: " + r.getError());

        Path created = pkg.resolve("ScoreCommand.java");
        assertTrue(Files.exists(created), "the command must be written to its own file");
        String command = read(created);
        assertTrue(command.contains("private final int base;")
                && command.contains("private final int bonus;"),
            "both parameters became fields — that is the point of the shape:\n" + command);
        assertTrue(command.contains("public int execute()"),
            "and the body became execute():\n" + command);
        assertTrue(command.contains("int weighted = base * 3;"),
            "carrying the body across, where `base` now resolves to the field:\n" + command);

        assertFalse(read(owner).contains("static int score"),
            "the function left its old home:\n" + read(owner));
        String afterCaller = read(caller);
        assertTrue(afterCaller.contains("new ScoreCommand(10, 2).execute()")
                && afterCaller.contains("new ScoreCommand(20, bonus).execute()"),
            "and both call sites construct and run it, arguments in order:\n" + afterCaller);
    }

    @Test
    @DisplayName("an instance method is refused — its receiver would be a field nobody named")
    void anInstanceMethodIsRefused() throws Exception {
        Path owner = pkg.resolve("Scoring.java");
        String before = read(owner);

        ToolResponse r = toCommand("public int instanceScore", 18, "InstanceCommand");

        assertFalse(r.isSuccess(), "a command for an instance method needs its receiver too");
        String error = String.valueOf(r.getError());
        assertTrue(error.contains("receiver"),
            "and the refusal must say what is missing, not just that it declined: " + error);
        assertTrue(before.equals(read(owner)), "nothing may change on a refusal");
        assertFalse(Files.exists(pkg.resolve("InstanceCommand.java")),
            "and no file may be created on a refusal");
    }
}
