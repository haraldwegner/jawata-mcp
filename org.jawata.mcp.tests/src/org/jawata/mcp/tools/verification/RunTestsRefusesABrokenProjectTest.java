package org.jawata.mcp.tools.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ErrorInfo;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.RunTestsTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * S19 dogfood, 2026-10-21: {@code run_tests} on a project that does not compile answered
 * {@code INVALID_PARAMETER} with the hint "Check the tool's inputSchema", although every argument
 * was valid. The project's own state is what declined, so the refusal is a precondition, named.
 */
class RunTestsRefusesABrokenProjectTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    void aProjectThatDoesNotCompileIsAPreconditionNotABadArgument() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("coverage-target");
        Path src = service.getProjectRoot().resolve("src/main/java/com/example/cov/Covered.java");
        Files.writeString(src, Files.readString(src).replace("return x + 1;", "return undefinedName + 1;"));
        service.getJavaProject().getProject().refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());

        ObjectNode args = new ObjectMapper().createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "class");
        scope.put("typeName", "com.example.cov.CoveredTest");
        ToolResponse response = new RunTestsTool(() -> service).execute(args);

        assertFalse(response.isSuccess(), "a project that does not compile cannot be tested");
        ErrorInfo error = response.getError();
        assertEquals(ErrorInfo.PRECONDITION_NOT_MET, error.getCode(),
            "every argument was valid, so the refusal must not be INVALID_PARAMETER: " + error.getMessage());
        assertEquals("PROJECT_DOES_NOT_COMPILE", error.getReason(), "the reason is named for a caller");
        assertTrue(error.getMessage().contains("compile error"), "the message still says what failed: " + error.getMessage());
    }
}
