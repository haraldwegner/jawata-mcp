package org.jawata.mcp.spike;

import java.nio.file.Files;
import java.nio.file.Path;

import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindReferencesTool;
import org.jawata.mcp.tools.GetDiagnosticsTool;
import org.jawata.mcp.tools.GetDocumentSymbolsTool;
import org.jawata.mcp.tools.GetHoverInfoTool;
import org.jawata.mcp.tools.GoToDefinitionTool;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h S1 (route A spike), step 1: prints the raw answers of the five tools the spike's language server will call, so the server's
 * mapping to the language protocol is written against what the tools really say. Evidence only.
 */
class ToolShapesProbeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final ObjectMapper JSON = new ObjectMapper();

    private static void show(String label, ToolResponse response) throws Exception {
        String text = response.isSuccess() ? JSON.writeValueAsString(response.getData()) : String.valueOf(response.getError());
        System.out.println("SPIKE-SHAPE " + label + " success=" + response.isSuccess() + " " + (text.length() > 1500 ? text.substring(0, 1500) + "..." : text));
    }

    @Test
    void printTheShapes() throws Exception {
        Path copy = helper.copyFixtureAs("simple-maven", "lsp-maven");
        Path hello = copy.resolve("src/main/java/com/example/HelloWorld.java");
        Path broken = hello.resolveSibling("Broken.java");
        Files.writeString(broken, "package com.example;\nclass Broken {\n    int x = \"text\";\n}\n");
        JdtServiceImpl service = helper.disposeAfter(new JdtServiceImpl());
        service.loadProject(copy);
        ObjectNode at = JSON.createObjectNode().put("filePath", hello.toString()).put("line", 52).put("column", 16);
        show("hover", new GetHoverInfoTool(() -> service).execute(at));
        show("diagnostics", new GetDiagnosticsTool(() -> service, () -> new org.jawata.mcp.java.JavaSourceDiagnostics())
            .execute(JSON.createObjectNode().put("filePath", broken.toString())));
        show("diagnostics-all", new GetDiagnosticsTool(() -> service, () -> new org.jawata.mcp.java.JavaSourceDiagnostics())
            .execute(JSON.createObjectNode()));

        JdtServiceImpl pde = helper.loadWorkspaceCopy("pde-bundle-b", "pde-bundle-a");
        Path caller = helper.getTempDirectory().resolve("pde-bundle-a/src/com/example/a/Caller.java");
        ObjectNode greet = JSON.createObjectNode().put("filePath", caller.toString()).put("line", 8).put("column", 24);
        show("pde-definition", new GoToDefinitionTool(() -> pde).execute(greet));
        show("pde-hover", new GetHoverInfoTool(() -> pde).execute(greet));
        show("pde-diagnostics", new GetDiagnosticsTool(() -> pde, () -> new org.jawata.mcp.java.JavaSourceDiagnostics())
            .execute(JSON.createObjectNode().put("filePath", caller.toString())));
    }
}
