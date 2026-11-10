package org.jawata.mcp.java.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.core.ICompilationUnit;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.engine.ToolNotFoundException;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.protocol.JsonRpcMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What the editor's Testing view asks of the engine (Sprint 28i, Stage 25, step A4; ARCHITECTURE-28i-s25.md): {@code jawata/tests/list} names the
 * test classes and methods of every loaded project, and {@code jawata/tests/run} runs one of them and says how it went. Both are the engine's own
 * tools, {@code find_tests} and {@code run_tests}, asked through the one gate as the editor's caller, so the editor sees the runs an agent would.
 *
 * <p>A run is an engine session the engine itself watches: it starts the run, asks for its status until it ends, and pushes a
 * {@code jawata/tests/progress} notification each time the counts move, so no editor polls. If the editor drops the request, the run is cancelled.</p>
 */
final class LspTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** The first frame of a stack trace that points into a source file of the test class: {@code (FooTest.java:42)}. */
    private static final Pattern FRAME = Pattern.compile("\\(([A-Za-z0-9_$]+)\\.java:(\\d+)\\)");

    private final ToolCalls tools;
    private final CallerIdentity caller;
    private final Supplier<IJdtService> service;
    private final Consumer<JsonRpcMessage> out;
    private final long pollMillis;

    /**
     * @param pollMillis how long to wait between two looks at a running session
     */
    LspTests(ToolCalls tools, CallerIdentity caller, Supplier<IJdtService> service, Consumer<JsonRpcMessage> out, long pollMillis) {
        this.tools = tools;
        this.caller = caller;
        this.service = service;
        this.out = out;
        this.pollMillis = pollMillis;
    }

    /**
     * The test classes the engine finds, each with its methods and the scope that runs it.
     *
     * @param params an optional {@code pattern}, a glob over class names
     */
    Object list(JsonNode params) throws ToolNotFoundException {
        ObjectNode arguments = JSON.createObjectNode();
        if (params != null && params.path("pattern").isTextual()) {
            arguments.put("pattern", params.path("pattern").asText());
        }
        ToolResponse answer = tools.callTool("find_tests", arguments, caller);
        if (!answer.isSuccess()) {
            throw new IllegalStateException("the tests could not be listed: " + message(answer));
        }
        List<Map<String, Object>> classes = new ArrayList<>();
        for (JsonNode found : JSON.valueToTree(answer.getData()).path("testClasses")) {
            classes.add(entryOf(found));
        }
        return classes;
    }

    private Map<String, Object> entryOf(JsonNode found) {
        String name = found.path("className").asText();
        Path file = resolve(found.path("filePath").asText());
        String type = packageOf(file).map(pkg -> pkg.isEmpty() ? name : pkg + "." + name).orElse(name);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("uri", file == null ? "" : file.toUri().toString());
        entry.put("line", found.path("line").asInt());
        entry.put("framework", found.path("framework").asText(""));
        entry.put("scope", Map.of("kind", "class", "typeName", type));
        List<Map<String, Object>> methods = new ArrayList<>();
        for (JsonNode method : found.path("testMethods")) {
            Map<String, Object> shown = new LinkedHashMap<>();
            shown.put("name", method.path("name").asText());
            shown.put("label", method.path("displayName").asText(method.path("name").asText()));
            shown.put("line", method.path("line").asInt());
            shown.put("scope", Map.of("kind", "method", "typeName", type, "methodName", method.path("name").asText()));
            methods.add(shown);
        }
        entry.put("tests", methods);
        return entry;
    }

    /**
     * Runs the tests the scope names and answers when the run has ended, with the totals and each failure's message and source line.
     *
     * @param params {@code scope}, as {@code run_tests} takes it, and the optional {@code token} the progress notifications carry
     */
    Object run(JsonNode params) throws ToolNotFoundException, InterruptedException {
        ObjectNode start = JSON.createObjectNode().put("action", "start");
        start.set("scope", params.path("scope"));
        ToolResponse started = tools.callTool("run_tests", start, caller);
        if (!started.isSuccess()) {
            throw new IllegalStateException("the tests could not be started: " + message(started));
        }
        String session = JSON.valueToTree(started.getData()).path("sessionId").asText();
        Object token = params.has("token") ? params.get("token") : null;
        String last = "";
        boolean ended = false;
        try {
            while (true) {
                ToolResponse status = tools.callTool("run_tests", JSON.createObjectNode().put("action", "status").put("sessionId", session), caller);
                if (!status.isSuccess()) {
                    throw new IllegalStateException("the run could not be followed: " + message(status));
                }
                JsonNode data = JSON.valueToTree(status.getData());
                String progress = data.path("progress").toString();
                if (!progress.equals(last)) {
                    last = progress;
                    push(token, session, data);
                }
                if (!"RUNNING".equals(data.path("state").asText())) {
                    ended = true;
                    return resultOf(session, data);
                }
                Thread.sleep(pollMillis);
            }
        } finally {
            if (!ended) {
                // the editor stopped waiting, or the run could not be followed: the runner JVM must not be left running
                tools.callTool("run_tests", JSON.createObjectNode().put("action", "cancel").put("sessionId", session), caller);
            }
        }
    }

    private void push(Object token, String session, JsonNode data) {
        ObjectNode params = JSON.createObjectNode().put("sessionId", session);
        if (token != null) {
            params.set("token", JSON.valueToTree(token));
        }
        params.set("progress", data.path("progress"));
        out.accept(JsonRpcMessage.notification("jawata/tests/progress", params));
    }

    private static Map<String, Object> resultOf(String session, JsonNode data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session);
        result.put("state", data.path("state").asText());
        result.put("summary", JSON.convertValue(data.path("summary"), Map.class));
        List<Map<String, Object>> failures = new ArrayList<>();
        for (JsonNode failure : data.path("failures")) {
            Map<String, Object> shown = new LinkedHashMap<>();
            shown.put("testClass", failure.path("testClass").asText());
            shown.put("testMethod", failure.path("testMethod").asText());
            shown.put("status", failure.path("status").asText());
            shown.put("message", failure.path("message").asText(""));
            lineOf(failure.path("testClass").asText(), failure.path("stackTrace").asText("")).ifPresent(line -> shown.put("line", line));
            failures.add(shown);
        }
        result.put("failures", failures);
        return result;
    }

    /** The zero-based line of the first frame inside the test class's own source file, when the trace has one. */
    static OptionalInt lineOf(String testClass, String stackTrace) {
        String simple = testClass.substring(testClass.lastIndexOf('.') + 1);
        int nested = simple.indexOf('$');
        String file = nested < 0 ? simple : simple.substring(0, nested);
        Matcher frame = FRAME.matcher(stackTrace);
        while (frame.find()) {
            if (frame.group(1).equals(file)) {
                return OptionalInt.of(Integer.parseInt(frame.group(2)) - 1);
            }
        }
        return OptionalInt.empty();
    }

    /** The file {@code find_tests} named: as it is when absolute, else under the first loaded project that holds it; null when none does. */
    private Path resolve(String reported) {
        Path path = Path.of(reported);
        if (path.isAbsolute()) {
            return path;
        }
        IJdtService engine = service.get();
        if (engine != null) {
            for (LoadedProject project : engine.allProjects()) {
                Path candidate = project.projectRoot().resolve(reported);
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** The package a file declares, as the engine's model holds it; empty when the model does not know the file. */
    private Optional<String> packageOf(Path file) {
        IJdtService engine = service.get();
        if (file == null || engine == null) {
            return Optional.empty();
        }
        ICompilationUnit unit = engine.getCompilationUnit(file);
        return unit == null ? Optional.empty() : Optional.of(unit.getParent().getElementName());
    }

    private static String message(ToolResponse failed) {
        return failed.getError() == null ? "no reason given" : failed.getError().getMessage();
    }
}
