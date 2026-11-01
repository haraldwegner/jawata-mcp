package org.jawata.mcp.java.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 28h S16a, the plug-in half of the scripted exit clause: the same language server over a tree of two Eclipse plug-in bundles, where
 * a file of one bundle uses a type of the other. An unsaved edit in {@code Caller.java} of {@code pde-bundle-a} yields the compiler's
 * error at the characters the faulty expression covers, in that bundle, though the type it misuses lives in {@code pde-bundle-b}.
 */
class JavaLanguageServerPluginTreeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lsp-plugin-test-scheduler");
        t.setDaemon(true);
        return t;
    });
    private static final String CALL = "greeter.greet(name)";

    @Test
    @DisplayName("an unsaved edit in one plug-in bundle yields the error of the other bundle's type, at the characters of the faulty call")
    void anUnsavedEditInAPluginBundleIsDiagnosedAtItsRange() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy("pde-bundle-b", "pde-bundle-a");
        Path caller = helper.getTempDirectory().resolve("pde-bundle-a/src/com/example/a/Caller.java");
        String saved = Files.readString(caller);
        String broken = saved.replace("return " + CALL + ";", "int bad = " + CALL + ";\n        return null;");
        assertFalse(saved.equals(broken), "the fixture has the line the edit changes");
        List<JsonRpcMessage> sent = new CopyOnWriteArrayList<>();
        JavaLanguageServer server = new JavaLanguageServer(() -> service, EditorTools.over(service, call -> { }),
            CallerIdentity.editor("plugin-test"), sent::add, SCHEDULER, 0);

        server.receive(JsonRpcMessage.notification("textDocument/didOpen", JSON.valueToTree(Map.of("textDocument",
            Map.of("uri", caller.toUri().toString(), "languageId", "java", "version", 1, "text", broken)))));

        JsonNode error = awaitFirstError(sent);
        int line = lineOf(broken, "int bad");
        int from = broken.split("\n")[line].indexOf(CALL);
        assertEquals("Type mismatch: cannot convert from String to int", error.path("message").asText());
        assertEquals(line, error.path("range").path("start").path("line").asInt(), "the line of the faulty statement");
        assertEquals(from, error.path("range").path("start").path("character").asInt(), "starts where the call starts");
        assertEquals(from + CALL.length(), error.path("range").path("end").path("character").asInt(), "ends where the call ends");
        assertEquals(saved, Files.readString(caller), "the file on disk is as it was");
    }

    private static int lineOf(String text, String token) {
        return (int) text.substring(0, text.indexOf(token)).chars().filter(c -> c == '\n').count();
    }

    private static JsonNode awaitFirstError(List<JsonRpcMessage> sent) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < until) {
            for (JsonRpcMessage message : sent) {
                if ("textDocument/publishDiagnostics".equals(message.getMethod())) {
                    for (JsonNode diagnostic : message.getParams().path("diagnostics")) {
                        if (diagnostic.path("severity").asInt() == 1) {
                            return diagnostic;
                        }
                    }
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no error was published for the plug-in bundle file: " + sent);
    }
}
