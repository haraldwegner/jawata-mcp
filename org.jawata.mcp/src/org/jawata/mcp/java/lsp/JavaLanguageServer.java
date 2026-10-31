package org.jawata.mcp.java.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.jawata.core.BufferStore;
import org.jawata.core.IJdtService;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One language-server conversation (Sprint 28h, S16a): what an editor says arrives as {@link JsonRpcMessage}s through {@link #receive},
 * in the order it was said, and what the server says goes out through the consumer it was given. It knows nothing of streams or HTTP:
 * a transport reads frames and feeds this, and writes what comes out.
 *
 * <p>It answers from the engine's own model: the text an editor has open is held in the engine's {@link BufferStore}, so a tool call
 * from an agent and this server read the same unsaved text. A file the model does not know (outside every loaded project) is
 * ignored, and the editor is not told it failed, because an editor opens every file it has.</p>
 */
public final class JavaLanguageServer {

    private static final Logger log = LoggerFactory.getLogger(JavaLanguageServer.class);

    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;

    private final Supplier<IJdtService> service;
    private final Consumer<JsonRpcMessage> out;
    private boolean shuttingDown;

    public JavaLanguageServer(Supplier<IJdtService> service, Consumer<JsonRpcMessage> out) {
        this.service = service;
        this.out = out;
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
            case "initialize" -> out.accept(JsonRpcMessage.successResponse(id, initializeResult()));
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
                ICompilationUnit saved = savedUnit(document.path("uri").asText());
                if (saved != null) {
                    buffers().open(saved, document.path("text").asText(""));
                }
            }
            case "textDocument/didChange" -> {
                ICompilationUnit saved = savedUnit(params.path("textDocument").path("uri").asText());
                if (saved != null && buffers().view(saved).isPresent()) {
                    String text = buffers().view(saved).get().getSource();
                    buffers().change(saved, DocumentChanges.apply(text, DocumentChanges.parse(params.get("contentChanges"))));
                }
            }
            case "textDocument/didClose" -> {
                ICompilationUnit saved = savedUnit(params.path("textDocument").path("uri").asText());
                if (saved != null) {
                    buffers().close(saved);
                }
            }
            default -> {
                // initialized, $/cancelRequest, workspace notifications: nothing to do yet, and an unknown notification is not an error
            }
        }
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
}
