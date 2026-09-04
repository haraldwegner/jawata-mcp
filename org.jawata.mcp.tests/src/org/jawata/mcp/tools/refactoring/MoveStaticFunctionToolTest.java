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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue, row 24 — Move Function, the static half, through {@code move
 * kind=method}.
 *
 * <p>It is the same kind as the instance move on purpose. A caller moving a method should
 * not have to know which JDT engine the language implies, so the tool reads {@code static}
 * off the method and picks; what the caller supplies is a destination type instead of a
 * receiver, because a static method has no receiver to name. The refusal test below is
 * about exactly that boundary — asking for a receiver on a method that cannot have one.</p>
 */
class MoveStaticFunctionToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private MoveTool tool;
    private ObjectMapper mapper;
    private Path pkg;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new MoveTool(() -> service, new RefactoringChangeCache());
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

    private ObjectNode moveArgs(Path file, String marker, int column) throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "method");
        args.put("filePath", file.toString());
        args.put("line", lineOf(file, marker));
        args.put("column", column);
        return args;
    }

    @Test
    @DisplayName("a static method moves to the named type and every qualified call repoints")
    void theStaticMethodMoves() throws Exception {
        Path home = pkg.resolve("StaticHome.java");
        Path destination = pkg.resolve("StaticDestination.java");
        Path caller = pkg.resolve("StaticCaller.java");
        assertTrue(read(caller).contains("StaticHome.roundUp("),
            "PROOF OF LIFE: the caller must qualify by the old owner before this runs");

        ObjectNode args = moveArgs(home, "public static int roundUp", 22);
        args.put("targetType", "com.example.StaticDestination");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "the move must run; got: " + r.getError());

        assertTrue(read(destination).contains("roundUp"),
            "the method arrived at the destination:\n" + read(destination));
        assertFalse(read(home).contains("roundUp"),
            "and left its old home — no forwarder, since keepDelegate defaults false:\n"
                + read(home));

        String afterCaller = read(caller);
        assertTrue(afterCaller.contains("StaticDestination.roundUp("),
            "the call sites name the new owner:\n" + afterCaller);
        assertFalse(afterCaller.contains("StaticHome.roundUp("),
            "and none still names the old one:\n" + afterCaller);
    }

    @Test
    @DisplayName("a static method without targetType is refused, and told that `target` cannot apply")
    void aStaticMethodWithoutADestinationIsRefused() throws Exception {
        Path home = pkg.resolve("StaticHome.java");
        // `target` is the INSTANCE path's parameter, and passing it here is the mistake the
        // refusal exists to answer: it names a receiver for a method that has none.
        ObjectNode args = moveArgs(home, "public static int roundUp", 22);
        args.put("target", "somethingReceiverish");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "there is no destination and no receiver to infer one from");
        String error = String.valueOf(r.getError());
        assertTrue(error.contains("targetType"),
            "the refusal must name the parameter that would fix it: " + error);
        assertTrue(error.contains("no receiver"),
            "and say why `target` is not that parameter: " + error);
        assertTrue(read(home).contains("roundUp"), "nothing may move on a refusal");
    }
}
