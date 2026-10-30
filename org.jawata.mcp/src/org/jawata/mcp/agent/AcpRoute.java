package org.jawata.mcp.agent;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.jawata.mcp.protocol.JsonRpcMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * The agent's door on the engine's HTTP listener (Sprint 28h, S5): one ACP connection is a pair of requests that carry the same
 * {@code conn} id. A host program (the launcher's {@code --acp}) POSTs each message it reads from its host to {@code /acp}, and holds a
 * GET open on {@code /acp/events} that streams every message the agent sends back as one server-sent event. {@code DELETE /acp} ends the
 * connection. The listener has already checked the key; this class holds no logic about what the messages mean.
 *
 * <p>A connection is opened by whichever of the two requests arrives first, so the host program may start either one. The agent's
 * messages wait for the stream, so none is lost between the two.</p>
 */
public final class AcpRoute implements HttpHandler {

    static final String PATH = "/acp";
    static final String EVENTS = "/acp/events";

    private static final Logger log = LoggerFactory.getLogger(AcpRoute.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long KEEPALIVE_SECONDS = 15;

    private final AgentFace face;
    private final Supplier<AgentBrain> brains;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    AcpRoute(AgentFace face, Supplier<AgentBrain> brains) {
        this.face = face;
        this.brains = brains;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String conn = query(exchange, "conn");
            if (conn == null || conn.isBlank()) {
                reply(exchange, 400);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (PATH.equals(path) && "POST".equals(method)) {
                post(exchange, conn);
            } else if (EVENTS.equals(path) && "GET".equals(method)) {
                events(exchange, conn);
            } else if (PATH.equals(path) && "DELETE".equals(method)) {
                Connection ended = connections.remove(conn);
                if (ended != null) {
                    ended.close();
                }
                reply(exchange, 204);
            } else {
                reply(exchange, 405);
            }
        } catch (IllegalStateException e) {
            log.warn("ACP request refused: {}", e.getMessage());
            reply(exchange, 503);
        } finally {
            exchange.close();
        }
    }

    private void post(HttpExchange exchange, String conn) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonRpcMessage message;
        try {
            message = JSON.readValue(body, JsonRpcMessage.class);
        } catch (JsonProcessingException e) {
            reply(exchange, 400);
            return;
        }
        connection(conn).submit(message);
        reply(exchange, 202);
    }

    private void events(HttpExchange exchange, String conn) throws IOException {
        Connection connection = connection(conn);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        try {
            while (!connection.closed) {
                String next = connection.outbox.poll(KEEPALIVE_SECONDS, TimeUnit.SECONDS);
                if (next != null && next.isEmpty()) {
                    continue; // the wake-up a closing connection leaves; the loop condition ends it
                }
                byte[] frame = (next == null ? ": keepalive\n\n" : "data: " + next + "\n\n").getBytes(StandardCharsets.UTF_8);
                out.write(frame);
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.debug("ACP stream for {} ended: {}", conn, e.getMessage());
        }
    }

    private Connection connection(String id) {
        return connections.computeIfAbsent(id, key -> new Connection(face, brains.get()));
    }

    private static String query(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return null;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && name.equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void reply(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    /** One host connection: the agent's server, the messages waiting for the stream, and the one thread that works the host's messages in order. */
    private static final class Connection {

        final BlockingQueue<String> outbox = new LinkedBlockingQueue<>();
        final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "acp-connection");
            t.setDaemon(true);
            return t;
        });
        final AcpServer server;
        volatile boolean closed;

        Connection(AgentFace face, AgentBrain brain) {
            this.server = face.open(brain, this::send);
        }

        void submit(JsonRpcMessage message) {
            worker.execute(() -> {
                try {
                    server.receive(message);
                } catch (RuntimeException e) {
                    log.error("The agent failed on {}", message.getMethod(), e);
                }
            });
        }

        private void send(JsonRpcMessage message) {
            try {
                outbox.add(JSON.writeValueAsString(message));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("an ACP message could not be written", e);
            }
        }

        void close() {
            closed = true;
            worker.shutdownNow();
            outbox.add("");
        }
    }
}
