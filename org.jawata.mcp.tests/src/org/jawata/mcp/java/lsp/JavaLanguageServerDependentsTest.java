package org.jawata.mcp.java.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.CoreException;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.engine.ToolNotFoundException;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.resident.workspace.OwnedReconciler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 28h S16a, the re-check of dependents: a file the editor has open shows the problems that another program's change to a file it uses
 * causes, though the open file did not change. The fixture is {@code ReadingFunctions} (changed on disk) and {@code ReadingCaller}
 * (open, calling {@code ReadingFunctions.baseCharge}) of simple-maven.
 */
class JavaLanguageServerDependentsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lsp-dependents-test-scheduler");
        t.setDaemon(true);
        return t;
    });

    /** The engine's gate reads the disk before every call; the test's stand-in does the same, so a change another program made is seen. */
    private static ToolCalls readingTheDiskFirst(JdtServiceImpl service) {
        ToolCalls tools = EditorTools.over(service, call -> { });
        // 28h D15: the scan's suffixes come from the language, so the stand-in reconciles through the language-aware one, as the engine does.
        org.jawata.spi.host.Reconciler disk = new org.jawata.mcp.engine.LanguageAwareReconciler(new OwnedReconciler(() -> service),
            () -> List.of(new org.jawata.mcp.java.JavaProvider()));
        return new ToolCalls() {
            @Override
            public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) throws ToolNotFoundException {
                try {
                    disk.syncBeforeCall();
                } catch (CoreException e) {
                    throw new IllegalStateException(e);
                }
                return tools.callTool(name, arguments, caller);
            }

            @Override
            public List<Map<String, Object>> getToolDefinitions() {
                return List.of();
            }
        };
    }

    @Test
    @DisplayName("a change on disk to a file an open file uses makes the open file's problems show, though the open file did not change")
    void aChangeOnDiskChecksTheOpenFileThatUsesIt() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        Path sources = helper.getFixturePath("simple-maven").resolve("src/main/java/com/example");
        Path functions = sources.resolve("ReadingFunctions.java");
        Path caller = sources.resolve("ReadingCaller.java");
        String functionsText = Files.readString(functions);
        List<JsonRpcMessage> sent = new CopyOnWriteArrayList<>();
        JavaLanguageServer server = new JavaLanguageServer(() -> service, readingTheDiskFirst(service),
            CallerIdentity.editor("dependents-test"), sent::add, SCHEDULER, 0);
        open(server, caller, Files.readString(caller));
        awaitErrors(sent, caller, 0);

        // another program (an agent) renames the function the open file calls, and the editor tells the server the file changed
        Files.writeString(functions, functionsText.replace("baseCharge", "renamedWithoutTheCallers"));
        server.receive(JsonRpcMessage.notification("workspace/didChangeWatchedFiles", JSON.valueToTree(Map.of("changes",
            List.of(Map.of("uri", functions.toUri().toString(), "type", 2))))));

        awaitErrors(sent, caller, 1);
    }

    @Test
    @DisplayName("an unsaved edit to one open file makes the problems of another open file that uses it show, though that file did not change")
    void anUnsavedEditChecksTheOpenFileThatUsesIt() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        Path sources = helper.getFixturePath("simple-maven").resolve("src/main/java/com/example");
        Path functions = sources.resolve("ReadingFunctions.java");
        Path caller = sources.resolve("ReadingCaller.java");
        String functionsText = Files.readString(functions);
        List<JsonRpcMessage> sent = new CopyOnWriteArrayList<>();
        JavaLanguageServer server = new JavaLanguageServer(() -> service, EditorTools.over(service, call -> { }),
            CallerIdentity.editor("dependents-test"), sent::add, SCHEDULER, 0);
        open(server, caller, Files.readString(caller));
        open(server, functions, functionsText);
        awaitErrors(sent, caller, 0);

        // the person renames the function in the editor, without saving: the file that calls it did not change
        server.receive(JsonRpcMessage.notification("textDocument/didChange", JSON.valueToTree(Map.of(
            "textDocument", Map.of("uri", functions.toUri().toString(), "version", 2),
            "contentChanges", List.of(Map.of("text", functionsText.replace("baseCharge", "renamedWithoutTheCallers")))))));

        awaitErrors(sent, caller, 1);
        org.junit.jupiter.api.Assertions.assertEquals(functionsText, Files.readString(functions), "the file on disk is as it was");
    }

    private static void open(JavaLanguageServer server, Path file, String text) {
        server.receive(JsonRpcMessage.notification("textDocument/didOpen", JSON.valueToTree(Map.of("textDocument",
            Map.of("uri", file.toUri().toString(), "languageId", "java", "version", 1, "text", text)))));
    }

    /** Waits until the last problems published for {@code file} hold at least {@code atLeast} errors (0 means: any publish at all). */
    private static void awaitErrors(List<JsonRpcMessage> sent, Path file, int atLeast) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < until) {
            JsonNode last = null;
            for (JsonRpcMessage message : sent) {
                if ("textDocument/publishDiagnostics".equals(message.getMethod())
                        && file.toUri().toString().equals(message.getParams().path("uri").asText())) {
                    last = message.getParams();
                }
            }
            if (last != null) {
                int errors = 0;
                for (JsonNode diagnostic : last.path("diagnostics")) {
                    errors += diagnostic.path("severity").asInt() == 1 ? 1 : 0;
                }
                if (errors >= atLeast && (atLeast > 0 || errors == 0)) {
                    return;
                }
            }
            Thread.sleep(25);
        }
        List<String> published = new ArrayList<>();
        for (JsonRpcMessage message : sent) {
            if ("textDocument/publishDiagnostics".equals(message.getMethod())) {
                published.add(message.getParams().toString());
            }
        }
        throw new AssertionError("the problems of " + file.getFileName() + " never held " + atLeast + " error(s): " + published);
    }
}
