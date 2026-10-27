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

    @Test
    @DisplayName("run_tests: a type named with no projectKey in a workspace of several projects is refused, not run in the first one")
    void aTypeNamedWithoutAProjectKeyIsRefusedWhenSeveralProjectsAreLoaded() throws Exception {
        // The project that does NOT hold the test comes first, as event-aggregator did before singleton.
        JdtServiceImpl service = helper.loadWorkspaceCopy("compile-clean", OWNER_FIXTURE);
        assertTrue(service.projects().size() > 1, "the fixture workspace holds several projects");

        ToolResponse r = toolOver(service).execute(classScope("com.example.rel.RelativeFileTest"));

        assertFalse(r.isSuccess(), "a run in the wrong project must not answer success: " + r.getData());
        String error = String.valueOf(r.getError());
        assertTrue(error.contains("projectKey"), "the refusal names the parameter that settles it: " + error);
        for (LoadedWorkspace.ProjectInfo p : service.projects()) {
            assertTrue(error.contains(p.key()), "the refusal lists project '" + p.key() + "': " + error);
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
