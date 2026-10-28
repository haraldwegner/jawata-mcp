package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.RunTestsTool;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.spi.lang.LoadedWorkspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S19-63 (dogfood 2026-10-28, installed 4.4.17): in a workspace of many projects, run_tests with no
 * projectKey tested the FIRST project loaded, whatever project held the class asked for, and answered
 * success with zero tests. A scope that names a type or a package cannot say which project owns it, so
 * with several projects loaded the call is refused and the projects are listed; a scope that names a
 * file belongs to the project whose folder holds the file; and an answer names the project it ran.
 */
class RunTestsNamesItsProjectTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String OWNER_FIXTURE = "runner-module-relative-file";

    private ObjectNode classScope(String typeName) {
        ObjectNode args = new ObjectMapper().createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "class");
        scope.put("typeName", typeName);
        args.put("framework", "junit5");
        args.put("timeoutSeconds", 120);
        return args;
    }

    private RunTestsTool toolOver(JdtServiceImpl service) {
        return new RunTestsTool(() -> service, new org.jawata.mcp.java.JavaProvider(() -> service)::testRuntime);
    }

    private ObjectNode packageScope(String packageName) {
        ObjectNode args = new ObjectMapper().createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "package");
        scope.put("packageName", packageName);
        args.put("framework", "junit5");
        args.put("timeoutSeconds", 120);
        return args;
    }

    private static LoadedWorkspace.ProjectInfo ownerOf(JdtServiceImpl service, String fixture) {
        return service.projects().stream()
            .filter(p -> p.root().toString().contains(fixture))
            .findFirst().orElseThrow(() -> new AssertionError("no project of " + fixture + " in " + service.projects()));
    }

    @Test
    @DisplayName("run_tests: a type that exactly one loaded project holds runs in that project with no projectKey (S19-63c)")
    void aTypeOneProjectHoldsRunsInThatProjectWithoutAKey() throws Exception {
        // The project that does NOT hold the test comes first, as event-aggregator did before singleton.
        JdtServiceImpl service = helper.loadWorkspaceCopy("compile-clean", OWNER_FIXTURE);
        assertTrue(service.projects().size() > 1, "the fixture workspace holds several projects");

        ToolResponse r = toolOver(service).execute(classScope("com.example.rel.RelativeFileTest"));

        assertTrue(r.isSuccess(), "the workspace can say which project holds the class, so it runs: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(ownerOf(service, OWNER_FIXTURE).key(), data.get("project"), "the answer names the project that held it: " + data);
    }

    @Test
    @DisplayName("run_tests: a type that no loaded project holds is refused as not found, not as an unnamed project (S19-63c)")
    void aTypeNoProjectHoldsIsRefusedAsNotFound() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy("compile-clean", OWNER_FIXTURE);

        ToolResponse r = toolOver(service).execute(classScope("com.example.rel.NoSuchTest"));

        assertFalse(r.isSuccess(), "a typo must not answer success: " + r.getData());
        String error = String.valueOf(r.getError());
        assertTrue(error.contains("com.example.rel.NoSuchTest") && error.contains("No loaded project holds"),
            "the refusal says no project holds the name it was given: " + error);
        assertFalse(error.contains("can be in any of them"), "it does not claim the call was merely unnamed: " + error);
    }

    @Test
    @DisplayName("run_tests: a package that exactly one loaded project holds runs in that project with no projectKey (S19-63c)")
    void aPackageOneProjectHoldsRunsInThatProjectWithoutAKey() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy("compile-clean", OWNER_FIXTURE);

        ToolResponse r = toolOver(service).execute(packageScope("com.example.rel"));

        assertTrue(r.isSuccess(), "the package is held by one project, so it runs: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(ownerOf(service, OWNER_FIXTURE).key(), data.get("project"), "the answer names the project that held it: " + data);
    }

    @Test
    @DisplayName("run_tests: a package that several projects hold is refused and lists only those projects (S19-63c)")
    void aPackageSeveralProjectsHoldListsOnlyThoseProjects() throws Exception {
        // compile-clean and compose-clean both declare com.example; the third project declares only com.acme.lone.
        // (The owner fixture declares com.example.rel, which is BELOW com.example, so a run of com.example would reach it too.)
        JdtServiceImpl service = helper.loadWorkspaceCopy("compile-clean", "compose-clean", "maven-pom-no-modules");
        LoadedWorkspace.ProjectInfo first = ownerOf(service, "compile-clean");
        LoadedWorkspace.ProjectInfo second = ownerOf(service, "compose-clean");
        LoadedWorkspace.ProjectInfo outsider = ownerOf(service, "maven-pom-no-modules");

        ToolResponse r = toolOver(service).execute(packageScope("com.example"));

        assertFalse(r.isSuccess(), "two projects hold com.example, so the call must not pick one: " + r.getData());
        String error = String.valueOf(r.getError());
        assertTrue(error.contains("projectKey"), "the refusal names the parameter that settles it: " + error);
        assertTrue(error.contains(first.key()) && error.contains(second.key()), "it lists the projects that hold the package: " + error);
        assertFalse(error.contains(outsider.key()), "it does not list a project that does not hold it: " + error);
    }

    @Test
    @DisplayName("run_tests: coverage_import and coverage_mutation with no projectKey in a workspace of several projects are refused too (S19-63b)")
    void theCoverageActionsRefuseAnUnnamedProjectToo() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy("compile-clean", OWNER_FIXTURE);
        assertTrue(service.projects().size() > 1, "the fixture workspace holds several projects");
        RunTestsTool tool = toolOver(service);

        ObjectNode importArgs = new ObjectMapper().createObjectNode();
        importArgs.put("action", "coverage_import");
        importArgs.put("execFile", "no-such.exec");
        ToolResponse imported = tool.execute(importArgs);
        assertFalse(imported.isSuccess(), "coverage_import answered: " + imported.getData());
        assertTrue(String.valueOf(imported.getError()).contains("projectKey"),
            "coverage_import names the parameter that settles it: " + imported.getError());
        assertFalse(String.valueOf(imported.getError()).contains("a type or a package"),
            "coverage_import names no type or package, so the refusal must not talk about one (dogfood 2026-10-28): " + imported.getError());

        ObjectNode mutationArgs = new ObjectMapper().createObjectNode();
        mutationArgs.put("action", "coverage_mutation");
        mutationArgs.putArray("targetClasses").add("com.example.rel.Anything");
        mutationArgs.putArray("targetTests").add("com.example.rel.RelativeFileTest");
        ToolResponse mutated = tool.execute(mutationArgs);
        assertFalse(mutated.isSuccess(), "coverage_mutation answered: " + mutated.getData());
        assertTrue(String.valueOf(mutated.getError()).contains("projectKey"),
            "coverage_mutation names the parameter that settles it: " + mutated.getError());
    }

    @Test
    @DisplayName("run_tests: a scope that names an absolute file inside a project runs that project with no projectKey (S19-63)")
    void anAbsoluteFileInsideAProjectNamesItsProject() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy("compile-clean", OWNER_FIXTURE);
        LoadedWorkspace.ProjectInfo owner = service.projects().stream()
            .filter(p -> p.root().toString().contains(OWNER_FIXTURE))
            .findFirst().orElseThrow(() -> new AssertionError("no project of " + OWNER_FIXTURE));
        java.nio.file.Path file;
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(owner.root())) {
            file = walk.filter(p -> p.getFileName().toString().equals("RelativeFileTest.java")).findFirst()
                .orElseThrow(() -> new AssertionError("RelativeFileTest.java not under " + owner.root()));
        }
        java.util.List<String> lines = java.nio.file.Files.readAllLines(file);
        int line = 0;
        while (line < lines.size() && !lines.get(line).contains("class RelativeFileTest")) {
            line++;
        }
        assertTrue(line < lines.size(), "the class declaration is in " + file);

        ObjectNode args = new ObjectMapper().createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "class");
        scope.put("filePath", file.toAbsolutePath().toString());
        scope.put("line", line);
        scope.put("column", lines.get(line).indexOf("class RelativeFileTest"));
        args.put("framework", "junit5");
        args.put("timeoutSeconds", 120);
        ToolResponse r = toolOver(service).execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(owner.key(), data.get("project"), "the project holding the file ran: " + data);
    }

    @Test
    @DisplayName("run_tests: a relative file path, or a file outside every project, is refused when several projects are loaded (S19-63)")
    void aRelativeOrUnownedFileIsRefusedWhenSeveralProjectsAreLoaded() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy("compile-clean", OWNER_FIXTURE);
        for (String path : new String[] {"src/test/java/com/example/rel/RelativeFileTest.java", "/nowhere/at/all/Foo.java"}) {
            ObjectNode args = new ObjectMapper().createObjectNode();
            ObjectNode scope = args.putObject("scope");
            scope.put("kind", "class");
            scope.put("filePath", path);
            scope.put("line", 0);
            scope.put("column", 0);
            ToolResponse r = toolOver(service).execute(args);
            assertFalse(r.isSuccess(), path + " answered: " + r.getData());
            String error = String.valueOf(r.getError());
            assertTrue(error.contains(path) && error.contains("absolute path"), "the refusal names the file and what settles it: " + error);
        }
    }

    @Test
    @DisplayName("run_tests: with the owning project's key the class runs, and the answer names the project it ran")
    void withTheProjectKeyTheClassRunsAndTheAnswerNamesTheProject() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy("compile-clean", OWNER_FIXTURE);
        LoadedWorkspace.ProjectInfo owner = service.projects().stream()
            .filter(p -> p.root().toString().contains(OWNER_FIXTURE))
            .findFirst().orElseThrow(() -> new AssertionError("no project of " + OWNER_FIXTURE + " in " + service.projects()));

        ObjectNode args = classScope("com.example.rel.RelativeFileTest");
        args.put("projectKey", owner.key());
        ToolResponse r = toolOver(service).execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(owner.key(), data.get("project"), "the answer names the project it ran: " + data);
    }
}
