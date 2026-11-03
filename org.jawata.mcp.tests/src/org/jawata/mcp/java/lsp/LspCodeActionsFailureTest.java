package org.jawata.mcp.java.lsp;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.eclipse.jdt.core.ICompilationUnit;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h, S16e (b), the audit's finding 2. The quick-fix tool now answers a failed search for a type to import as an error, and the
 * editor's code action request must not turn it back into "no actions", which says no import exists.
 */
class LspCodeActionsFailureTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    /** A gate that answers every call with the given response. */
    private static ToolCalls answering(ToolResponse response) {
        return new ToolCalls() {
            @Override
            public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                return response;
            }

            @Override
            public List<Map<String, Object>> getToolDefinitions() {
                return List.of();
            }
        };
    }

    @Test
    @DisplayName("a failed search for an import is an error to the editor and not an empty list of actions; any other failed listing is still no actions")
    void aFailedSearchForAnImportIsAnErrorNotNoActions() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        Path file = helper.getFixturePath("simple-maven").resolve("src/main/java/com/example/Calculator.java");
        Function<String, ICompilationUnit> unitOf = uri -> service.getCompilationUnit(Path.of(URI.create(uri)));
        JsonNode params = JSON.readTree("{\"textDocument\":{\"uri\":\"" + file.toUri() + "\"},"
            + "\"range\":{\"start\":{\"line\":5,\"character\":0},\"end\":{\"line\":5,\"character\":0}},\"context\":{\"only\":[\"quickfix\"]}}");
        CallerIdentity caller = CallerIdentity.supplied("code-action-failure-test");

        LspCodeActions searchBroke = new LspCodeActions(
            answering(ToolResponse.error("FIX_SEARCH_FAILED", "the search for a type named 'X' to import failed", "ask again")), caller, unitOf, uri -> false);
        LspCodeActions otherFailure = new LspCodeActions(
            answering(ToolResponse.error("PROBLEMS_UNKNOWN", "the file could not be parsed", "run get_diagnostics")), caller, unitOf, uri -> false);

        LspCodeActions cancelled = new LspCodeActions(
            answering(ToolResponse.error("OPERATION_CANCELLED", "the request was cancelled while it was being worked", "Ask again.")), caller, unitOf, uri -> false);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> searchBroke.codeAction(params));
        assertTrue(thrown.getMessage().contains("search for a type"), thrown.getMessage());
        assertThrows(IllegalStateException.class, () -> cancelled.codeAction(params));
        assertTrue(((List<?>) otherFailure.codeAction(params)).isEmpty(), "control: another failed listing must stay no actions");
    }
}
