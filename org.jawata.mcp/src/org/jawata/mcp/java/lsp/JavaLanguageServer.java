package org.jawata.mcp.java.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.jawata.core.BufferStore;
import org.jawata.core.IJdtService;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.engine.ToolNotFoundException;
import org.jawata.mcp.java.JavaRanges;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * One language-server conversation (Sprint 28h, S16a): what an editor says arrives as {@link JsonRpcMessage}s through {@link #receive},
 * in the order it was said, and what the server says goes out through the consumer it was given. It knows nothing of streams or HTTP:
 * a transport reads frames and feeds this, and writes what comes out.
 *
 * <p>It answers from the engine's own model: the text an editor has open is held in the engine's {@link BufferStore}, so a tool call
 * from an agent and this server read the same unsaved text. A file the model does not know (outside every loaded project) is
 * ignored, and the editor is not told it failed, because an editor opens every file it has.</p>
 *
 * <p>Whatever the engine's tools can answer is asked of the tools through the one gate every call goes through, as the editor's own
 * caller. The problems of an open file are asked of {@code get_diagnostics} a moment after the person stops typing, and the answer is
 * published only if the text is still the text it was asked about.</p>
 */
public final class JavaLanguageServer {

    private static final Logger log = LoggerFactory.getLogger(JavaLanguageServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;

    private static final int SEVERITY_ERROR = 1;
    private static final int SEVERITY_WARNING = 2;
    private static final int MOST_PROBLEMS_PER_FILE = 500;

    private final Supplier<IJdtService> service;
    private final ToolCalls tools;
    private final CallerIdentity caller;
    private final Consumer<JsonRpcMessage> out;
    private final ScheduledExecutorService scheduler;
    private final long debounceMillis;
    private final Map<String, ScheduledFuture<?>> pendingDiagnostics = new ConcurrentHashMap<>();
    /** The files this editor has open and the model holds: what is checked again when something else on disk changes. */
    private final Set<String> openFiles = ConcurrentHashMap.newKeySet();
    private boolean shuttingDown;
    private boolean editorTakesWatchRequests;

    /**
     * @param service        the engine's Java model, asked at each use because a workspace may load after the server exists
     * @param tools          the engine's one gate to its tools
     * @param caller         who this conversation's tool calls are recorded as
     * @param out            where the server's messages go
     * @param scheduler      runs the diagnostics a moment after the last change; shared by the conversations of one engine
     * @param debounceMillis how long the text must stay unchanged before its problems are asked for
     */
    public JavaLanguageServer(Supplier<IJdtService> service, ToolCalls tools, CallerIdentity caller, Consumer<JsonRpcMessage> out,
            ScheduledExecutorService scheduler, long debounceMillis) {
        this.service = service;
        this.tools = tools;
        this.caller = caller;
        this.out = out;
        this.scheduler = scheduler;
        this.debounceMillis = debounceMillis;
    }

    /**
     * Handles one message from the editor.
     *
     * @return false once the editor has said {@code exit}, when the conversation is over
     */
    public boolean receive(JsonRpcMessage message) {
        String method = message.getMethod();
        if (method == null) {
            return true; // an answer to something this server asked: nothing asked yet
        }
        if ("exit".equals(method)) {
            pendingDiagnostics.values().forEach(pending -> pending.cancel(false));
            pendingDiagnostics.clear();
            return false;
        }
        try {
            if (message.isRequest()) {
                request(message);
            } else {
                notification(message);
            }
        } catch (RuntimeException | JavaModelException e) {
            log.warn("The language server failed on {}", method, e);
            if (message.isRequest()) {
                out.accept(JsonRpcMessage.errorResponse(message.getId(), JsonRpcMessage.JsonRpcError.INTERNAL_ERROR, String.valueOf(e), null));
            }
        }
        return true;
    }

    private void request(JsonRpcMessage message) {
        Object id = message.getId();
        if (shuttingDown) {
            out.accept(JsonRpcMessage.errorResponse(id, INVALID_REQUEST, "the server is shutting down", null));
            return;
        }
        switch (message.getMethod()) {
            case "initialize" -> {
                JsonNode params = message.getParams();
                editorTakesWatchRequests = params != null
                    && params.path("capabilities").path("workspace").path("didChangeWatchedFiles").path("dynamicRegistration").asBoolean(false);
                out.accept(JsonRpcMessage.successResponse(id, initializeResult()));
            }
            case "shutdown" -> {
                shuttingDown = true;
                out.accept(JsonRpcMessage.successResponse(id, null));
            }
            default -> out.accept(JsonRpcMessage.errorResponse(id, METHOD_NOT_FOUND, "method not found: " + message.getMethod(), null));
        }
    }

    private void notification(JsonRpcMessage message) throws JavaModelException {
        JsonNode params = message.getParams();
        switch (message.getMethod()) {
            case "textDocument/didOpen" -> {
                JsonNode document = params.path("textDocument");
                String uri = document.path("uri").asText();
                ICompilationUnit saved = savedUnit(uri);
                if (saved != null) {
                    buffers().open(saved, document.path("text").asText(""));
                    openFiles.add(uri);
                    askForProblems(uri);
                }
            }
            case "textDocument/didChange" -> {
                String uri = params.path("textDocument").path("uri").asText();
                ICompilationUnit saved = savedUnit(uri);
                if (saved != null && buffers().view(saved).isPresent()) {
                    String text = buffers().view(saved).get().getSource();
                    buffers().change(saved, DocumentChanges.apply(text, DocumentChanges.parse(params.get("contentChanges"))));
                    askForProblems(uri);
                }
            }
            case "textDocument/didClose" -> {
                String uri = params.path("textDocument").path("uri").asText();
                ICompilationUnit saved = savedUnit(uri);
                if (saved != null) {
                    cancelPending(uri);
                    openFiles.remove(uri);
                    if (buffers().close(saved)) {
                        publish(uri, List.of()); // a closed file has no problems of the editor's to show
                    }
                }
            }
            case "initialized" -> askEditorToWatchTheSources();
            case "workspace/didChangeWatchedFiles" -> openFiles.forEach(this::askForProblems); // a file other than these changed, and may have broken them
            default -> {
                // $/cancelRequest and other notifications: nothing to do yet, and an unknown notification is not an error
            }
        }
    }

    /**
     * Asks the editor to tell the server when a Java file or a build file changes on disk, when the editor says it can be asked. Files
     * the person changes in another program are then noticed, instead of only the ones open in this editor.
     */
    private void askEditorToWatchTheSources() {
        if (!editorTakesWatchRequests) {
            return;
        }
        List<Map<String, Object>> watchers = new ArrayList<>();
        for (String glob : List.of("**/*.java", "**/pom.xml", "**/build.gradle*", "**/.classpath")) {
            watchers.add(Map.of("globPattern", glob));
        }
        out.accept(JsonRpcMessage.request("jawata-watched-files", "client/registerCapability", JSON.valueToTree(Map.of("registrations", List.of(
            Map.of("id", "jawata-watched-files", "method", "workspace/didChangeWatchedFiles", "registerOptions", Map.of("watchers", watchers)))))));
    }

    private static Map<String, Object> initializeResult() {
        Map<String, Object> sync = new LinkedHashMap<>();
        sync.put("openClose", true);
        sync.put("change", 2); // incremental
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("textDocumentSync", sync);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("capabilities", capabilities);
        result.put("serverInfo", Map.of("name", "jawata"));
        return result;
    }

    private BufferStore buffers() {
        return service.get().buffers().orElseThrow(() -> new IllegalStateException("this engine holds no editor buffers"));
    }

    /**
     * The saved unit of the file an editor's URI names, or null when no loaded project holds it. The model hands out the open buffer's
     * working copy for a file that is open; the store is keyed by the saved unit, so the primary unit is asked for.
     */
    private ICompilationUnit savedUnit(String uri) {
        IJdtService engine = service.get();
        ICompilationUnit unit = engine == null ? null : engine.getCompilationUnit(Path.of(URI.create(uri)));
        return unit == null ? null : unit.getPrimary();
    }

    // ---- problems --------------------------------------------------------------------------------------------------------------------

    /** Asks for the file's problems once the text has stayed as it is for the debounce time; a newer change replaces an earlier ask. */
    private void askForProblems(String uri) {
        ScheduledFuture<?> later = scheduler.schedule(() -> {
            try {
                publishProblemsOf(uri);
            } catch (RuntimeException | JavaModelException | ToolNotFoundException e) {
                log.warn("The problems of {} could not be published", uri, e);
            }
        }, debounceMillis, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> earlier = pendingDiagnostics.put(uri, later);
        if (earlier != null) {
            earlier.cancel(false);
        }
    }

    private void cancelPending(String uri) {
        ScheduledFuture<?> earlier = pendingDiagnostics.remove(uri);
        if (earlier != null) {
            earlier.cancel(false);
        }
    }

    @SuppressWarnings("unchecked")
    private void publishProblemsOf(String uri) throws JavaModelException, ToolNotFoundException {
        ICompilationUnit saved = savedUnit(uri);
        if (saved == null || buffers().view(saved).isEmpty()) {
            return; // closed since it was asked for
        }
        String text = buffers().view(saved).get().getSource();
        ToolResponse answer = tools.callTool("get_diagnostics", JSON.createObjectNode()
            .put("filePath", Path.of(URI.create(uri)).toString()).put("maxResults", MOST_PROBLEMS_PER_FILE), caller);
        if (!answer.isSuccess()) {
            log.debug("get_diagnostics refused {}: {}", uri, answer);
            return; // no answer is not a clean file: nothing is published, and the editor keeps what it has
        }
        if (buffers().view(saved).isEmpty() || !text.equals(buffers().view(saved).get().getSource())) {
            return; // the text moved on while the problems were found; the change that moved it asked again
        }
        JavaRanges.Lines lines = JavaRanges.linesOf(text);
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) ((Map<String, Object>) answer.getData()).get("diagnostics")) {
            diagnostics.add(diagnosticOf(row, lines));
        }
        publish(uri, diagnostics);
    }

    private static Map<String, Object> diagnosticOf(Map<String, Object> row, JavaRanges.Lines lines) {
        int line = ((Number) row.get("line")).intValue();
        JavaRanges.Range range;
        if (row.get("startOffset") instanceof Number start && row.get("endOffset") instanceof Number end) {
            range = lines.span(start.intValue(), Math.max(1, end.intValue() - start.intValue() + 1)); // the engine's end offset is the last character
        } else {
            range = new JavaRanges.Range(line, 0, line, 0);
        }
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("range", Map.of(
            "start", Map.of("line", range.line(), "character", range.column()),
            "end", Map.of("line", range.endLine(), "character", range.endColumn())));
        diagnostic.put("severity", "error".equals(row.get("severity")) ? SEVERITY_ERROR : SEVERITY_WARNING);
        diagnostic.put("source", "jawata");
        diagnostic.put("message", String.valueOf(row.get("message")));
        if (row.get("problemId") != null) {
            diagnostic.put("code", row.get("problemId"));
        }
        return diagnostic;
    }

    private void publish(String uri, List<Map<String, Object>> diagnostics) {
        out.accept(JsonRpcMessage.notification("textDocument/publishDiagnostics",
            JSON.valueToTree(Map.of("uri", uri, "diagnostics", diagnostics))));
    }
}
