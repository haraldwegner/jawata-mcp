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
    /** How long a host's closing DELETE waits for the prompt it is still waiting on. */
    static final long FINISH_SECONDS = 300;

    private final AgentFace face;
    private final Supplier<AgentBrain> brains;
    private final long keepaliveSeconds;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    AcpRoute(AgentFace face, Supplier<AgentBrain> brains) {
        this(face, brains, KEEPALIVE_SECONDS);
    }

    /** A route that writes to an idle stream every {@code keepaliveSeconds}, which is also how long a gone host program goes unnoticed. */
    AcpRoute(AgentFace face, Supplier<AgentBrain> brains, long keepaliveSeconds) {
        this.face = face;
        this.brains = brains;
        this.keepaliveSeconds = keepaliveSeconds;
    }

    /** How many host connections are open now. */
    int openConnections() {
        return connections.size();
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
                    ended.finish();
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
            while (!(connection.closed && connection.outbox.isEmpty())) {
                String next = connection.outbox.poll(keepaliveSeconds, TimeUnit.SECONDS);
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
        } finally {
            // The stream is the connection's other half: when the host program is gone (a crash sends no DELETE) the next write fails
            // and the connection, its worker thread and its queue are released here instead of living as long as the resident.
            if (connections.remove(conn, connection)) {
                connection.close();
            }
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
            Runnable work = () -> {
                try {
                    server.receive(message);
                } catch (RuntimeException e) {
                    log.error("The agent failed on {}", message.getMethod(), e);
                }
            };
            if ("session/set_config_option".equals(message.getMethod())) {
                // The person's switch must act while a prompt is running; queued behind it, the grant would arrive after the turn it was for.
                work.run();
            } else {
                worker.execute(work);
            }
        }

        private void send(JsonRpcMessage message) {
            try {
                outbox.add(JSON.writeValueAsString(message));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("an ACP message could not be written", e);
            }
        }

        /** The host is gone: nothing can be delivered, so work in flight is interrupted and the stream ends at once. */
        void close() {
            closed = true;
            worker.shutdownNow();
            outbox.add("");
        }

        /**
         * The host closed on purpose: the prompt it is waiting on finishes (up to {@link #FINISH_SECONDS}), then the stream delivers
         * what is queued and ends, so a host that writes its last prompt and closes its input still gets the answer.
         */
        void finish() {
            worker.shutdown();
            try {
                if (!worker.awaitTermination(FINISH_SECONDS, TimeUnit.SECONDS)) {
                    worker.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                worker.shutdownNow();
            }
            closed = true;
            outbox.add("");
        }
    }
}
