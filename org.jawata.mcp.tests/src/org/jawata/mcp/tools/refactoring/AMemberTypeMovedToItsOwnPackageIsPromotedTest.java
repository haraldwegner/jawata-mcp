package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.MoveClassTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S3 / D7e — a MEMBER type told to move to its OWN package is promoted to
 * its own compilation unit, and the nest-mate consequence is reported.
 *
 * <p>The refusal the field report hit — <em>"Target package equals source package in the
 * source project; nothing to move"</em> — is correct for a top-level type and wrong for a
 * nested one: there, moving to the same package is Fowler's Move Inner To Top Level and is
 * a real change. The third test below is the CONTROL that keeps the correct half of that
 * refusal alive, because a fix that simply deleted it would pass the first two.</p>
 *
 * <p>The nest-mate half is the part a caller cannot see from the diff alone: a non-static
 * inner class that reads its outer's state cannot stand alone without carrying a reference
 * to the instance it was born from, so the response says that an enclosing-instance field
 * was added and under what name.</p>
 */
class AMemberTypeMovedToItsOwnPackageIsPromotedTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private MoveClassTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new MoveClassTool(() -> service, new RefactoringChangeCache());
        objectMapper = new ObjectMapper();
        projectPath = service.getProjectRoot();
    }

    @Test
    @DisplayName("a static member type becomes its own file, and no enclosing instance is involved")
    void aStaticMemberTypeIsPromotedWithNoEnclosingInstance() throws Exception {
        ToolResponse r = promote("Standalone");
        assertTrue(r.isSuccess(), () -> "promotion failed: " + describe(r));

        Map<String, Object> data = dataOf(r);
        assertEquals(Boolean.TRUE, data.get("promotedToTopLevel"),
            () -> "the response must say the member type was promoted rather than moved: " + data);
        assertTrue(Files.exists(
                projectPath.resolve("src/main/java/com/example/Standalone.java")),
            "the promoted type must exist as its own compilation unit in the same package");

        Map<?, ?> enclosing = (Map<?, ?>) data.get("enclosingInstance");
        assertNotNull(enclosing, () -> "the nest-mate report is the deliverable: " + data);
        assertEquals(Boolean.FALSE, enclosing.get("required"),
            () -> "a STATIC member type reads nothing of its outer, so no enclosing instance "
                + "is needed and the report must not claim one: " + enclosing);

        // The created file is READ rather than trusted, for the reason the sibling test
        // states: the gate parses a created file without resolving it.
        String promoted = Files.readString(
            projectPath.resolve("src/main/java/com/example/Standalone.java"));
        String outer = Files.readString(
            projectPath.resolve("src/main/java/com/example/NestedPromotionTargets.java"));

        assertTrue(promoted.contains("public class Standalone {"),
            () -> "the promoted type stands alone, and `static` is meaningless on a "
                + "top-level class so the engine must have dropped it: " + promoted);
        assertFalse(promoted.contains("NestedPromotionTargets"),
            () -> "a type that read nothing of its outer must NOT acquire a back-reference "
                + "to it — that is the difference from the nest-mate case: " + promoted);
        // THE DISCRIMINATOR against the sibling test: there every construction gained an
        // argument. Here none does, and a report claiming otherwise would be wrong.
        assertTrue(outer.contains("return new Standalone(\"initial\");"),
            () -> "no construction site changes shape for a standalone member type: " + outer);
        assertFalse(outer.contains("class Standalone"),
            () -> "the member type must be gone from its old home: " + outer);
    }

    @Test
    @DisplayName("an inner type that reads its outer is promoted WITH its enclosing instance, named")
    void anInnerTypeThatReadsItsOuterReportsTheEnclosingInstance() throws Exception {
        ToolResponse r = promote("NestMate");
        assertTrue(r.isSuccess(), () -> "promotion failed: " + describe(r));

        Map<String, Object> data = dataOf(r);
        assertEquals(Boolean.TRUE, data.get("promotedToTopLevel"),
            () -> "the response must say the member type was promoted: " + data);
        assertTrue(Files.exists(
                projectPath.resolve("src/main/java/com/example/NestMate.java")),
            "the promoted type must exist as its own compilation unit in the same package");

        Map<?, ?> enclosing = (Map<?, ?>) data.get("enclosingInstance");
        assertNotNull(enclosing, () -> "the nest-mate report is the deliverable: " + data);
        // This is the half the diff does not announce: the promoted type gained a field and
        // every construction gained an argument, because `outerCount` is the outer's.
        assertEquals(Boolean.TRUE, enclosing.get("required"),
            () -> "NestMate reads its outer's private field, so standing alone REQUIRES an "
                + "enclosing instance and the report must say so: " + enclosing);
        Object fieldName = enclosing.get("fieldName");
        assertNotNull(fieldName, () -> "a required enclosing instance a caller cannot name is "
            + "not a report: " + enclosing);
        assertFalse(String.valueOf(fieldName).isBlank(),
            () -> "the enclosing-instance field must be named: " + enclosing);

        // THE CREATED FILE IS READ, not trusted. The compile gate PARSES a file the
        // refactoring created and does not RESOLVE it (measured in this sprint on row 59),
        // and this operation's whole output is a created file — so a promoted type that
        // referred to something it cannot reach would be applied and reported clean. Every
        // string below was read off the engine's own output, not predicted.
        String promoted = Files.readString(
            projectPath.resolve("src/main/java/com/example/NestMate.java"));
        String outer = Files.readString(
            projectPath.resolve("src/main/java/com/example/NestedPromotionTargets.java"));

        assertTrue(promoted.contains(
                "private final NestedPromotionTargets " + fieldName + ";"),
            () -> "the promoted type must hold the enclosing instance the report named: "
                + promoted);
        assertTrue(promoted.contains("return this." + fieldName + ".outerCount * 2;"),
            () -> "the outer's field must now be read THROUGH that instance: " + promoted);
        assertTrue(outer.contains("return new NestMate(this);"),
            () -> "every construction must pass the enclosing instance, which is the half "
                + "the response has to announce: " + outer);

        // AND THE ENCAPSULATION CONSEQUENCE, which is why the note mentions it: `outerCount`
        // was declared `private` and is now package-private, because a separate top-level
        // class cannot read a private member. Without this the created file would not
        // compile — and nothing downstream would have said so.
        assertFalse(outer.contains("private final int outerCount;"),
            () -> "promoting a nest mate RELAXES the field it reads; the fixture declared it "
                + "private and the engine must have widened it: " + outer);
        assertTrue(outer.contains("final int outerCount;"),
            () -> "the field must survive, widened rather than removed: " + outer);
    }

    @Test
    @DisplayName("CONTROL: a TOP-LEVEL type told to move to its own package is still refused")
    void aTopLevelTypeIsStillRefused() throws Exception {
        Path source = projectPath.resolve("src/main/java/com/example/NestedPromotionTargets.java");
        int[] at = declarationOf(source, "public class NestedPromotionTargets");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", source.toString());
        args.put("line", at[0]);
        args.put("column", at[1]);
        args.put("targetPackage", "com.example");

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "a top-level type already in the target package has nothing to move, and the fix "
                + "for the nested case must not have deleted that refusal");
        assertTrue(String.valueOf(describe(r)).contains("nothing to move"),
            () -> "the surviving refusal must still be the same one: " + describe(r));
    }

    /** Points {@code move kind=class} at the named member type, naming its own package. */
    private ToolResponse promote(String memberName) throws Exception {
        Path source = projectPath.resolve("src/main/java/com/example/NestedPromotionTargets.java");
        assertTrue(Files.exists(source), "fixture must exist before the refactoring");
        int[] at = declarationOf(source, "class " + memberName + " {");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", source.toString());
        args.put("line", at[0]);
        args.put("column", at[1]);
        args.put("targetPackage", "com.example");
        return tool.execute(args);
    }

    /**
     * The ZERO-BASED line and column of the type NAME in the first line containing
     * {@code needle}, read off the file rather than counted by hand — a hand-counted
     * fixture coordinate has been wrong twice in this sprint, and an {@code indexOf}
     * that quietly returned -1 produced a red that measured nothing.
     */
    private static int[] declarationOf(Path file, String needle) throws Exception {
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i);
            int at = text.indexOf(needle);
            if (at < 0) continue;
            int nameAt = text.indexOf("class ", at) + "class ".length();
            assertTrue(nameAt > 0, () -> "no type name on: " + text);
            return new int[]{i, nameAt};
        }
        throw new AssertionError("fixture no longer declares '" + needle + "' in " + file);
    }

    private static String describe(ToolResponse r) {
        return r.isSuccess() ? String.valueOf(r.getData())
            : r.getError().getCode() + " / " + r.getError().getMessage();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }
}
