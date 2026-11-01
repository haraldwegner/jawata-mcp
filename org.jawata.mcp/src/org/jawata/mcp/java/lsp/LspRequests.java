package org.jawata.mcp.java.lsp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.jawata.mcp.protocol.JsonRpcMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The language server's requests that take time (Sprint 28h, S16b): each is answered off the connection's thread, so a slow completion
 * does not hold up the next edit, and each can be abandoned. Two ways an answer is not given:
 * <ul>
 *   <li>the editor sent {@code $/cancelRequest}: the request is answered {@code RequestCancelled} at once and whatever the handler
 *       finds later is discarded;</li>
 *   <li>the text of the document the request is about changed while the handler worked: the answer would describe text that is gone,
 *       so the request is answered {@code ContentModified} and the editor asks again.</li>
 * </ul>
 * Whichever happens first answers; a request is answered exactly once.
 */
final class LspRequests {

    private static final Logger log = LoggerFactory.getLogger(LspRequests.class);

    static final int REQUEST_CANCELLED = -32800;
    static final int CONTENT_MODIFIED = -32801;

    /** One kind of request: given its parameters, the result to answer (null is a valid answer). */
    @FunctionalInterface
    interface Handler {
        Object handle(JsonNode params) throws Exception;
    }

    /** One request being worked: answered once, by whoever gets there first. */
    private static final class Running {
        private final AtomicBoolean answered = new AtomicBoolean();
        private volatile Future<?> work;

        boolean claimAnswer() {
            return answered.compareAndSet(false, true);
        }
    }

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lsp-request");
        t.setDaemon(true);
        return t;
    });

    private final Consumer<JsonRpcMessage> out;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final Map<String, Object> capabilities = new LinkedHashMap<>();
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> versions = new ConcurrentHashMap<>();

    LspRequests(Consumer<JsonRpcMessage> out) {
        this.out = out;
    }

    /**
     * Makes {@code method} a request the server answers.
     *
     * @param capability the key of the server capabilities that tells the editor it may ask, such as {@code completionProvider}
     * @param value      what that capability holds, such as {@code true} or an options map
     */
    void register(String method, String capability, Object value, Handler handler) {
        handlers.put(method, handler);
        capabilities.put(capability, value);
    }

    boolean handles(String method) {
        return handlers.containsKey(method);
    }

    /** What the editor is told the server can do, besides what the server itself adds. */
    Map<String, Object> capabilities() {
        return Map.copyOf(capabilities);
    }

    /** Starts answering a request; returns at once. */
    void dispatch(JsonRpcMessage request) {
        Object id = request.getId();
        String key = String.valueOf(id);
        Handler handler = handlers.get(request.getMethod());
        JsonNode params = request.getParams();
        String uri = params == null ? null : params.path("textDocument").path("uri").asText(null);
        long seen = uri == null ? 0 : version(uri).get();
        Running current = new Running();
        running.put(key, current);
        current.work = POOL.submit(() -> {
            try {
                Object result = handler.handle(params);
                if (current.claimAnswer()) {
                    boolean moved = uri != null && version(uri).get() != seen;
                    out.accept(moved
                        ? JsonRpcMessage.errorResponse(id, CONTENT_MODIFIED, "the text changed while the answer was found", null)
                        : JsonRpcMessage.successResponse(id, result));
                }
            } catch (Exception e) {
                log.warn("The request {} failed", request.getMethod(), e);
                if (current.claimAnswer()) {
                    out.accept(JsonRpcMessage.errorResponse(id, JsonRpcMessage.JsonRpcError.INTERNAL_ERROR, String.valueOf(e), null));
                }
            } finally {
                running.remove(key);
            }
        });
    }

    /** The editor no longer wants the answer to request {@code id}. */
    void cancel(JsonNode id) {
        Running current = running.get(id.asText());
        if (current != null && current.claimAnswer()) {
            Future<?> work = current.work;
            if (work != null) {
                work.cancel(true);
            }
            out.accept(JsonRpcMessage.errorResponse(id.isIntegralNumber() ? (Object) id.asInt() : id.asText(), REQUEST_CANCELLED,
                "the request was cancelled", null));
        }
    }

    /** The text of {@code uri} changed (opened, edited, closed): requests about it that are still being worked will not be answered. */
    void textChanged(String uri) {
        version(uri).incrementAndGet();
    }

    /** The conversation is over: nothing still being worked is answered. */
    void closeAll() {
        for (Running current : running.values()) {
            current.claimAnswer();
            Future<?> work = current.work;
            if (work != null) {
                work.cancel(true);
            }
        }
        running.clear();
    }

    private AtomicLong version(String uri) {
        return versions.computeIfAbsent(uri, key -> new AtomicLong());
    }
}
