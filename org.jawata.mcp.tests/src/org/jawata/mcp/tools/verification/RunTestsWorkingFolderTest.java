package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.java.TestWorkingFolder;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.RunTestsTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S19-17 (dogfood 2026-10-24): run_tests started the forked test JVM in the JDT project's own
 * folder, which for a studio-managed project is a link folder in the studio's cache, so a test that
 * read a project-relative file failed (design-patterns' page-object: 4 of 4 red with a 404, the
 * same resident ran singleton 19 of 19 green). Maven starts a module's tests in the module's own
 * folder; the runner now does too.
 */
class RunTestsWorkingFolderTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @TempDir
    Path tmp;

    // ---------------------------------------------------- through the real tool

    @Test
    @DisplayName("a test that reads a file of its sibling module by a relative path passes through run_tests")
    void testsStartInTheirOwnModuleFolder() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("runner-module-relative-file");
        RunTestsTool tool = new RunTestsTool(() -> service, new org.jawata.mcp.java.JavaProvider(() -> service)::testRuntime);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode args = mapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "class");
        scope.put("typeName", "com.example.rel.RelativeFileTest");
        args.put("framework", "junit5");
        args.put("timeoutSeconds", 120);
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertEquals(2, summary.get("total"), "summary: " + summary);
        assertEquals(2, summary.get("passed"),
            "summary: " + summary + " failures: " + data.get("failures"));
    }

    // ---------------------------------------------------------- the choice itself

    @Test
    @DisplayName("the folder is the module that owns the test roots, not the aggregator above it")
    void theModuleOwningTheTestsWins() throws Exception {
        Path root = module(tmp, "agg");
        Path testModule = module(root, "tests");
        Path testRoot = Files.createDirectories(testModule.resolve("src/test/java"));

        assertEquals(testModule, TestWorkingFolder.choose(root, List.of(testRoot)));
    }

    @Test
    @DisplayName("a single-module project starts in its own root")
    void singleModuleStartsInItsRoot() throws Exception {
        Path root = module(tmp, "single");
        Path testRoot = Files.createDirectories(root.resolve("src/test/java"));

        assertEquals(root, TestWorkingFolder.choose(root, List.of(testRoot)));
    }

    @Test
    @DisplayName("two test roots of one module still name that module")
    void severalRootsOfOneModule() throws Exception {
        Path root = module(tmp, "agg2");
        Path m = module(root, "m");
        Path a = Files.createDirectories(m.resolve("src/test/java"));
        Path b = Files.createDirectories(m.resolve("src/it/java"));

        assertEquals(m, TestWorkingFolder.choose(root, List.of(a, b)));
    }

    @Test
    @DisplayName("test roots in two modules cannot share one folder: the project's root")
    void rootsOfTwoModulesFallBackToTheRoot() throws Exception {
        Path root = module(tmp, "agg3");
        Path one = Files.createDirectories(module(root, "one").resolve("src/test/java"));
        Path two = Files.createDirectories(module(root, "two").resolve("src/test/java"));

        assertEquals(root, TestWorkingFolder.choose(root, List.of(one, two)));
    }

    @Test
    @DisplayName("a test root with no build file above it inside the project is not guessed: the project's root")
    void noBuildFileMeansTheRoot() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("plain"));
        Path testRoot = Files.createDirectories(root.resolve("src/test/java"));

        assertEquals(root, TestWorkingFolder.choose(root, List.of(testRoot)));
    }

    @Test
    @DisplayName("one root in a module and one that only the aggregator owns: two folders, so the project's root")
    void aMixedAnswerFallsBackToTheRoot() throws Exception {
        Path root = module(tmp, "agg4");
        Path withModule = Files.createDirectories(module(root, "m").resolve("src/test/java"));
        Path owner = Files.createDirectories(root.resolve("loose/test"));

        assertEquals(root, TestWorkingFolder.choose(root, List.of(withModule, owner)));
    }

    @Test
    @DisplayName("a test root outside the project is ignored; nothing left means the project's root")
    void aRootOutsideTheProjectIsIgnored() throws Exception {
        Path root = module(tmp, "inside");
        Path outside = Files.createDirectories(module(tmp, "elsewhere").resolve("src/test/java"));

        assertEquals(root, TestWorkingFolder.choose(root, List.of(outside)));
    }

    @Test
    @DisplayName("a gradle module counts as a module as well")
    void gradleBuildFilesCount() throws Exception {
        Path root = module(tmp, "agg5");
        Path g = Files.createDirectories(root.resolve("g"));
        Files.writeString(g.resolve("build.gradle.kts"), "// module\n");
        Path testRoot = Files.createDirectories(g.resolve("src/test/java"));

        assertEquals(g, TestWorkingFolder.choose(root, List.of(testRoot)));
    }

    /** A folder holding a pom.xml: a module, or an aggregator. */
    private static Path module(Path parent, String name) throws Exception {
        Path dir = Files.createDirectories(parent.resolve(name));
        Files.writeString(dir.resolve("pom.xml"), "<project/>\n");
        return dir;
    }
}
