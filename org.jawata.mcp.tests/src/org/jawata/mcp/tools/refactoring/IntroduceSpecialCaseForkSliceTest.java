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
 * ROW 22 (Introduce Special Case) performed on code we did not author.
 *
 * <p>Unlike row 16, this row HAS a candidate in the fork and this is a demonstration rather
 * than a measured absence. {@code com.iluwatar.stepbuilder.Character} is upstream's own
 * domain object — a plain class with accessors, a public constructor and nothing final about
 * it — and a null object for it is exactly what a client holding a possibly-absent Character
 * would want instead of a check.</p>
 *
 * <p>It reuses the slice vendored for row 16 rather than copying a second module in. The
 * slice's {@code PROVENANCE.md} records the commit and the upstream path; nothing about it
 * was chosen for this row, which is what makes the demonstration worth having — the code was
 * already there for another reason.</p>
 *
 * <p><b>What is asserted is the neutrality on UPSTREAM's own types</b>, not merely that a
 * class appeared. Character's accessors return String and List, so this exercises two entries
 * of the neutrality table on shapes a fixture did not choose.</p>
 */
class IntroduceSpecialCaseForkSliceTest {

    private static final String PKG = "src/main/java/com/iluwatar/stepbuilder";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DataTool tool;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("fork-step-builder");
        tool = new DataTool(() -> service, new org.jawata.mcp.refactoring.RefactoringChangeCache());
        root = service.getProjectRoot();
    }

    private static int lineOf(Path file, String marker) throws Exception {
        String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                return i;
            }
        }
        throw new AssertionError("the vendored slice no longer contains: " + marker);
    }

    @Test
    @DisplayName("a null object is generated for upstream's own Character")
    void performsOnUpstreamsCharacter() throws Exception {
        Path character = root.resolve(PKG).resolve("Character.java");

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "special_case");
        args.put("filePath", character.toString());
        args.put("line", lineOf(character, "public class Character"));
        args.put("column", 13);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "upstream's Character is a plain, non-final class with a"
            + " reachable constructor — the shape this row performs on: " + r.getError());

        String after = Files.readString(character, StandardCharsets.UTF_8);
        assertTrue(after.contains("class NullCharacter extends Character"),
            "the special case must extend upstream's type:\n" + after);
        assertTrue(after.contains("public static final NullCharacter INSTANCE"),
            "and offer the shared instance clients would hold:\n" + after);
        assertTrue(after.contains("return \"\";"),
            "a String accessor answers with the empty string on UPSTREAM's own type, which is"
                + " a shape no fixture here chose:\n" + after);
    }

    @Test
    @DisplayName("upstream's step interfaces are refused — a special case for one is an implementation")
    void refusesUpstreamsStepInterface() throws Exception {
        // CharacterStepBuilder declares the nested step interfaces the builder walks through.
        // They are the commonest thing in this module and none can take a subclass.
        Path builder = root.resolve(PKG).resolve("CharacterStepBuilder.java");
        String before = Files.readString(builder, StandardCharsets.UTF_8);

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "special_case");
        args.put("filePath", builder.toString());
        args.put("line", lineOf(builder, "interface NameStep"));
        args.put("column", 14);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "an interface takes an implementation, not a subclass");
        assertTrue(String.valueOf(r.getError()).contains("is an INTERFACE"),
            "and the refusal must name that reason: " + r.getError());
        assertTrue(before.equals(Files.readString(builder, StandardCharsets.UTF_8)),
            "a refusal must leave upstream's file byte-for-byte untouched");
    }
}
