package org.jawata.mcp.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.GetDiagnosticsTool;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h S1, route A spike: a scripted language-protocol client drives {@link LspRouteASpikeServer}, which answers from the engine's
 * own JDT, on a Maven tree and on a pair of Eclipse plug-in bundles. Each observation is printed as a {@code SPIKE-OBS} line with what
 * produced it, so the plan's four observations are read from this test's output and not inferred.
 */
class LspRouteASpikeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final ObjectMapper JSON = new ObjectMapper();

    private static void obs(String text) {
        System.out.println("SPIKE-OBS " + text);
    }

    /** The scripted client: frames requests, collects every message the server sends, and waits for the one it wants. */
    private static final class Client implements AutoCloseable {
        private final PipedOutputStream toServer = new PipedOutputStream();
        private final PipedOutputStream serverOut = new PipedOutputStream();
        private final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        private final List<JsonNode> seen = new ArrayList<>();
        private final Thread serverThread;
        private final Thread readerThread;
        private int nextId = 1;

        Client(LspRouteASpikeServer server) throws IOException {
            PipedInputStream serverIn = new PipedInputStream(toServer, 1 << 20);
            PipedInputStream clientIn = new PipedInputStream(serverOut, 1 << 20);
            serverThread = new Thread(() -> {
                try {
                    server.serve(serverIn, serverOut);
                } catch (IOException e) {
                    System.out.println("SPIKE-OBS server stopped: " + e);
                }
            }, "spike-server");
            readerThread = new Thread(() -> {
                try {
                    while (true) {
                        JsonNode message = readFrame(clientIn);
                        if (message == null) {
                            return;
                        }
                        inbox.add(message);
                    }
                } catch (IOException e) {
                    // the server closed its end
                }
            }, "spike-client-reader");
            serverThread.setDaemon(true);
            readerThread.setDaemon(true);
            serverThread.start();
            readerThread.start();
        }

        private static JsonNode readFrame(InputStream in) throws IOException {
            int length = -1;
            while (true) {
                StringBuilder line = new StringBuilder();
                int c;
                while ((c = in.read()) != -1 && c != '\n') {
                    if (c != '\r') {
                        line.append((char) c);
                    }
                }
                if (c == -1) {
                    return null;
                }
                if (line.length() == 0) {
                    break;
                }
                if (line.toString().toLowerCase().startsWith("content-length:")) {
                    length = Integer.parseInt(line.substring("content-length:".length()).trim());
                }
            }
            return JSON.readTree(new String(in.readNBytes(length), StandardCharsets.UTF_8));
        }

        private void send(Map<String, Object> message) throws IOException {
            byte[] body = JSON.writeValueAsBytes(message);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            frame.write(body);
            toServer.write(frame.toByteArray());
            toServer.flush();
        }

        void notify(String method, Object params) throws IOException {
            send(Map.of("jsonrpc", "2.0", "method", method, "params", params));
        }

        JsonNode request(String method, Object params) throws Exception {
            int id = nextId++;
            send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
            return await(m -> m.has("id") && m.path("id").asInt() == id && !m.has("method"), 30_000);
        }

        /** The next message satisfying the test, taking from messages already seen first; null when none arrives in time. */
        JsonNode await(java.util.function.Predicate<JsonNode> wanted, long millis) throws InterruptedException {
            for (JsonNode message : seen) {
                if (wanted.test(message)) {
                    seen.remove(message);
                    return message;
                }
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (true) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    return null;
                }
                JsonNode message = inbox.poll(left, TimeUnit.MILLISECONDS);
                if (message == null) {
                    return null;
                }
                if (wanted.test(message)) {
                    return message;
                }
                seen.add(message);
            }
        }

        JsonNode diagnosticsFor(String uri, long millis) throws InterruptedException {
            return await(m -> "textDocument/publishDiagnostics".equals(m.path("method").asText()) && uri.equals(m.path("params").path("uri").asText()), millis);
        }

        @Override
        public void close() throws Exception {
            notify("exit", Map.of());
            serverThread.join(5_000);
        }
    }

    private static Map<String, Object> at(String uri, int line, int character) {
        return Map.of("textDocument", Map.of("uri", uri), "position", Map.of("line", line, "character", character));
    }

    private static void open(Client client, String uri, String text) throws IOException {
        client.notify("textDocument/didOpen", Map.of("textDocument", Map.of("uri", uri, "languageId", "java", "version", 1, "text", text)));
    }

    private static void changeTo(Client client, String uri, int version, String text) throws IOException {
        client.notify("textDocument/didChange", Map.of("textDocument", Map.of("uri", uri, "version", version),
            "contentChanges", List.of(Map.of("text", text))));
    }

    private static String messages(JsonNode diagnostics) {
        StringBuilder text = new StringBuilder();
        for (JsonNode row : diagnostics.path("params").path("diagnostics")) {
            text.append(row.path("message").asText()).append(" @").append(row.path("range").path("start")).append(';');
        }
        return text.toString();
    }

    /** What the one process holds: the machine's load, this process's resident memory and the Java processes on the machine. */
    private static void processEvidence(String label) throws Exception {
        obs(label + " load=" + Files.readString(Path.of("/proc/loadavg")).trim());
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            if (line.startsWith("VmRSS") || line.startsWith("VmHWM")) {
                obs(label + " this-process " + line.replaceAll("\\s+", " "));
            }
        }
        Process ps = new ProcessBuilder("ps", "-eo", "pid,rss,etime,args", "--sort=-rss").redirectErrorStream(true).start();
        String all = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        ps.waitFor();
        int shown = 0;
        for (String line : all.split("\n")) {
            if (line.contains("java") && shown++ < 6) {
                obs(label + " ps " + (line.length() > 170 ? line.substring(0, 170) : line));
            }
        }
        long jdt = 0;
        List<String> foreign = new ArrayList<>();
        try {
            Class<?> util = Class.forName("org.osgi.framework.FrameworkUtil");
            Object bundle = util.getMethod("getBundle", Class.class).invoke(null, JdtServiceImpl.class);
            Object context = bundle.getClass().getMethod("getBundleContext").invoke(bundle);
            for (Object b : (Object[]) context.getClass().getMethod("getBundles").invoke(context)) {
                String name = (String) b.getClass().getMethod("getSymbolicName").invoke(b);
                if ("org.eclipse.jdt.core".equals(name)) {
                    jdt++;
                }
                if (name != null && (name.contains("m2e") || name.contains("jdt.ls") || name.contains("redhat"))) {
                    foreign.add(name);
                }
            }
            obs(label + " bundles org.eclipse.jdt.core copies=" + jdt + " other-importer-or-Red-Hat-bundles=" + foreign);
        } catch (ReflectiveOperationException | RuntimeException e) {
            obs(label + " bundle count not run: " + e);
        }
    }

    @Test
    @DisplayName("S1 route A on a Maven tree: diagnostics, hover, definition, references, symbols, and an edit that is not saved")
    void aMavenTree() throws Exception {
        Path copy = helper.copyFixtureAs("simple-maven", "lsp-maven");
        Path hello = copy.resolve("src/main/java/com/example/HelloWorld.java");
        Path broken = hello.resolveSibling("Broken.java");
        String brokenText = "package com.example;\nclass Broken {\n    int x = \"text\";\n}\n";
        Files.writeString(broken, brokenText);
        long loadStart = System.nanoTime();
        JdtServiceImpl service = new JdtServiceImpl();
        helper.disposeAfter(service);
        service.loadProject(copy);
        obs("maven load " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loadStart) + " ms, projects=" + service.projects());
        processEvidence("maven-before");

        try (Client client = new Client(new LspRouteASpikeServer(() -> service))) {
            JsonNode init = client.request("initialize", Map.of("processId", 0, "rootUri", copy.toUri().toString(), "capabilities", Map.of()));
            obs("maven initialize capabilities=" + init.path("result").path("capabilities"));
            client.notify("initialized", Map.of());

            long first = System.nanoTime();
            open(client, broken.toUri().toString(), brokenText);
            JsonNode diagnostics = client.diagnosticsFor(broken.toUri().toString(), 30_000);
            assertNotNull(diagnostics, "no publishDiagnostics for the broken file");
            obs("maven diagnostics (unsaved-text path) first answer after " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - first) + " ms: " + messages(diagnostics));
            assertTrue(messages(diagnostics).contains("String"), messages(diagnostics));

            ToolResponse viaTool = new GetDiagnosticsTool(() -> service, () -> new org.jawata.mcp.java.JavaSourceDiagnostics())
                .execute(JSON.createObjectNode().put("filePath", broken.toString()));
            obs("maven diagnostics via the existing get_diagnostics tool on the same saved file: " + JSON.writeValueAsString(viaTool.getData()));

            String helloUri = hello.toUri().toString();
            String helloText = Files.readString(hello);
            open(client, helloUri, helloText);
            JsonNode clean = client.diagnosticsFor(helloUri, 30_000);
            obs("maven diagnostics for a clean file: " + messages(clean) + "(none expected)");
            assertEquals(0, clean.path("params").path("diagnostics").size());

            JsonNode hover = client.request("textDocument/hover", at(helloUri, 52, 16));
            obs("maven hover " + hover.path("result"));
            assertTrue(hover.path("result").path("contents").path("value").asText().contains("printGreeting"));

            JsonNode definition = client.request("textDocument/definition", at(helloUri, 52, 16));
            obs("maven definition " + definition.path("result"));
            assertTrue(definition.path("result").path("uri").asText().endsWith("HelloWorld.java"));

            JsonNode refs = client.request("textDocument/references", at(helloUri, 42, 16));
            obs("maven references " + refs.path("result"));
            assertEquals(1, refs.path("result").size());

            JsonNode symbols = client.request("textDocument/documentSymbol", Map.of("textDocument", Map.of("uri", helloUri)));
            obs("maven documentSymbol top=" + symbols.path("result").path(0).path("name").asText() + " children=" + symbols.path("result").path(0).path("children").size());
            assertEquals("HelloWorld", symbols.path("result").path(0).path("name").asText());

            // an edit that is not saved: the diagnostics follow it, the tool-backed answers do not
            changeTo(client, broken.toUri().toString(), 2, brokenText.replace("\"text\"", "1"));
            JsonNode fixed = client.diagnosticsFor(broken.toUri().toString(), 30_000);
            obs("maven diagnostics after an UNSAVED fix: " + messages(fixed) + "(none expected)");
            assertEquals(0, fixed.path("params").path("diagnostics").size());

            changeTo(client, helloUri, 2, helloText.replace("public void printGreeting()", "public void printGreetingRenamedUnsaved()"));
            client.diagnosticsFor(helloUri, 30_000);
            JsonNode afterEdit = client.request("textDocument/documentSymbol", Map.of("textDocument", Map.of("uri", helloUri)));
            boolean sawUnsaved = afterEdit.toString().contains("printGreetingRenamedUnsaved");
            obs("maven documentSymbol after an UNSAVED rename sees the new name: " + sawUnsaved + " (false means the tool answers from the saved file)");
            assertFalse(sawUnsaved);

            client.request("shutdown", Map.of());
        }
        processEvidence("maven-after");
    }

    @Test
    @DisplayName("S1 route A on two Eclipse plug-in bundles: diagnostics and a definition that crosses from one bundle into the other")
    void aPairOfPluginBundles() throws Exception {
        long loadStart = System.nanoTime();
        JdtServiceImpl service = helper.loadWorkspaceCopy("pde-bundle-b", "pde-bundle-a");
        obs("pde load " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loadStart) + " ms, projects=" + service.projects());
        Path caller = helper.getTempDirectory().resolve("pde-bundle-a/src/com/example/a/Caller.java");
        String text = Files.readString(caller);
        String bad = text.replace("return greeter.greet(name);", "int bad = greeter.greet(name);\n        return null;");
        assertFalse(text.equals(bad));
        String uri = caller.toUri().toString();

        try (Client client = new Client(new LspRouteASpikeServer(() -> service))) {
            client.request("initialize", Map.of("processId", 0, "rootUri", caller.getParent().toUri().toString(), "capabilities", Map.of()));
            client.notify("initialized", Map.of());

            long first = System.nanoTime();
            open(client, uri, bad);
            JsonNode diagnostics = client.diagnosticsFor(uri, 30_000);
            assertNotNull(diagnostics, "no publishDiagnostics for the plug-in bundle file");
            obs("pde diagnostics first answer after " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - first) + " ms: " + messages(diagnostics));
            assertTrue(messages(diagnostics).contains("String"), messages(diagnostics));

            JsonNode definition = client.request("textDocument/definition", at(uri, 8, 24));
            obs("pde definition from bundle a into bundle b " + definition.path("result"));
            assertTrue(definition.path("result").path("uri").asText().contains("pde-bundle-b"), String.valueOf(definition));

            JsonNode hover = client.request("textDocument/hover", at(uri, 8, 24));
            obs("pde hover " + hover.path("result").path("contents").path("value").asText().replace('\n', ' '));
            assertTrue(hover.path("result").path("contents").path("value").asText().contains("greet"));
            client.request("shutdown", Map.of());
        }
        processEvidence("pde-after");
    }
}
