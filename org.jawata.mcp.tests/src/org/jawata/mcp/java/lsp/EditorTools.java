package org.jawata.mcp.java.lsp;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.java.JavaSourceDiagnostics;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.GetDiagnosticsTool;
import org.jawata.resident.workspace.JdtServiceImpl;

import com.fasterxml.jackson.databind.JsonNode;

/** What the language-server tests hand the server as the engine's gate to its tools: the real {@code get_diagnostics} over a loaded project. */
final class EditorTools {

    private EditorTools() {
    }

    /** The tools of an engine that publishes only {@code get_diagnostics}, over {@code service}; every call is told to {@code seen} first. */
    static ToolCalls over(JdtServiceImpl service, Consumer<CallerIdentity> seen) {
        GetDiagnosticsTool diagnostics = new GetDiagnosticsTool(() -> service, JavaSourceDiagnostics::new);
        return new ToolCalls() {
            @Override
            public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                seen.accept(caller);
                if (!"get_diagnostics".equals(name)) {
                    throw new IllegalArgumentException("this test engine publishes only get_diagnostics: " + name);
                }
                return diagnostics.execute(arguments);
            }

            @Override
            public List<Map<String, Object>> getToolDefinitions() {
                return List.of();
            }
        };
    }
}
