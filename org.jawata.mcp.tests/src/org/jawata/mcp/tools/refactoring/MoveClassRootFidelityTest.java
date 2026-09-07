package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.MoveClassTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mcp#17 — <b>a class keeps the SOURCE ROOT it already lives in.</b>
 *
 * <p>{@code move(kind=class)} chose the destination root by scanning the target project
 * for a root that already declares the package, falling back to the FIRST source root.
 * The moved class's own root was never an input, so a TEST class moving to a package that
 * exists in {@code src/main/java} was written into the PRODUCTION root — reported as a
 * success, and compile-verified, because the result is valid Java.</p>
 *
 * <p><b>A package name in one root says nothing about the other.</b> {@code src/main/java}
 * and {@code src/test/java} are separate compilation scopes that routinely declare the same
 * package; that is the normal Maven layout, not a coincidence. So "this root already has
 * {@code com.example.service}" is not evidence about where a test class belongs, and acting
 * on it moves the class across a module boundary — production code can then compile against
 * a test class, which is the state the two roots exist to prevent.</p>
 *
 * <p>THE FIXTURE IS AN ISOLATED COPY and the targets are written by this test. The move
 * physically relocates a file, and {@code simple-maven} is shared and monotonically
 * growing — this sprint has recorded it moving a counted population five times. Its
 * {@code SampleTest} would have served as the mover, but five other tests assert on that
 * file's exact contents, so a target of our own is written instead.</p>
 */
class MoveClassRootFidelityTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private MoveClassTool tool;
    private Path projectRoot;

    /** A TEST class, in a package the PRODUCTION root also declares. */
    private static final String TEST_TARGET = """
        package com.example;

        public class RootFidelityTarget {
            int value() {
                return 1;
            }
        }
        """;

    /** A PRODUCTION class, so the rule is shown to be "its own root" and not "always test". */
    private static final String MAIN_TARGET = """
        package com.example;

        public class RootFidelityMain {
            int value() {
                return 2;
            }
        }
        """;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        projectRoot = service.getProjectRoot();

        Files.createDirectories(projectRoot.resolve("src/test/java/com/example"));
        Files.writeString(projectRoot.resolve("src/test/java/com/example/RootFidelityTarget.java"),
            TEST_TARGET);
        Files.writeString(projectRoot.resolve("src/main/java/com/example/RootFidelityMain.java"),
            MAIN_TARGET);
        // The production reconcile, which is how the product itself notices a file written
        // outside it — rather than a test-only refresh, which would prove less.
        new org.jawata.core.workspace.StrictDiskSync(() -> service).syncBeforeCall();

        tool = new MoveClassTool(() -> service, new org.jawata.mcp.refactoring.RefactoringChangeCache());
    }

    @Test
    @DisplayName("mcp#17: a TEST class moved to a package the PRODUCTION root has stays in the test root")
    void aTestClassKeepsItsOwnSourceRoot() throws Exception {
        Path source = projectRoot.resolve("src/test/java/com/example/RootFidelityTarget.java");
        // `com.example.service` exists in src/main/java and NOWHERE in src/test/java —
        // which is precisely the input that used to decide the destination.
        ToolResponse response = move(source, "RootFidelityTarget", "com.example.service");

        assertTrue(response.isSuccess(), "got: " + response.getError());

        Path inTestRoot = projectRoot.resolve("src/test/java/com/example/service/RootFidelityTarget.java");
        Path inMainRoot = projectRoot.resolve("src/main/java/com/example/service/RootFidelityTarget.java");

        assertAll(
            // THE DEFECT: this file was written into the production root.
            () -> assertFalse(Files.exists(inMainRoot),
                "a test class must not be written into the PRODUCTION root: " + inMainRoot),
            () -> assertTrue(Files.exists(inTestRoot),
                "it belongs in its own root: " + inTestRoot),
            () -> assertFalse(Files.exists(source), "and it is gone from where it was"));
    }

    @Test
    @DisplayName("THE CONTROL — a PRODUCTION class keeps the production root, so the rule is its OWN root")
    void aProductionClassKeepsItsOwnSourceRoot() throws Exception {
        // Without this, a fix that simply preferred src/test/java would pass the case above
        // while breaking every ordinary move, and the two would be indistinguishable.
        Path source = projectRoot.resolve("src/main/java/com/example/RootFidelityMain.java");
        ToolResponse response = move(source, "RootFidelityMain", "com.example.service");

        assertTrue(response.isSuccess(), "got: " + response.getError());

        assertAll(
            () -> assertTrue(Files.exists(
                    projectRoot.resolve("src/main/java/com/example/service/RootFidelityMain.java")),
                "a production class belongs in the production root"),
            () -> assertFalse(Files.exists(
                    projectRoot.resolve("src/test/java/com/example/service/RootFidelityMain.java")),
                "and not in the test root"));
    }

    /**
     * Drives the door the way a caller does — by POSITION, with the caret computed from the
     * file's own text rather than written as a literal, so an edit to the target above
     * cannot silently point this at a different construct.
     */
    private ToolResponse move(Path file, String typeName, String targetPackage) throws Exception {
        String text = Files.readString(file);
        String declaration = "public class " + typeName;
        int offset = text.indexOf(declaration);
        if (offset < 0) {
            throw new IllegalStateException("target declaration absent from " + file);
        }
        long line = text.substring(0, offset).chars().filter(c -> c == '\n').count();
        int column = declaration.length() - typeName.length();

        ObjectNode args = OM.createObjectNode();
        args.put("filePath", file.toString());
        args.put("line", (int) line);
        args.put("column", column);
        args.put("targetPackage", targetPackage);
        return tool.execute(args);
    }
}
