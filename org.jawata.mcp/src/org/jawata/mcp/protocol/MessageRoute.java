package org.jawata.mcp.protocol;

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
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * A conversation of JSON-RPC messages on the engine's HTTP listener (Sprint 28h, S5 and S16a): one connection is a pair of requests that
 * carry the same {@code conn} id. A host program (the launcher's {@code --acp} or {@code --lsp}) POSTs each message it reads from its
 * host to the route's path, and holds a GET open on {@code <path>/events} that streams every message the engine sends back as one
 * server-sent event. {@code DELETE <path>} ends the connection. The listener has already checked the key; this class holds no logic
 * about what the messages mean, so the agent's door and the language server's door are the same code with a different session behind them.
 *
 * <p>A connection is opened by whichever of the two requests arrives first, so the host program may start either one. The messages
 * wait for the stream, so none is lost between the two.</p>
 */
public final class MessageRoute implements HttpHandler {

    /** How long a host's closing DELETE waits for the message it is still waiting on. */
    public static final long FINISH_SECONDS = 300;

    /** What one connection talks to: the messages the host sends arrive here, in the order they were sent. */
    @FunctionalInterface
    public interface Session {
        void receive(JsonRpcMessage message);

        /** The host's connection ended, on purpose or not: whatever the session holds for that host (open buffers, pending work) goes now. */
        default void closed() {
        }

        /** Whether this message belongs to a conversation beside the main one, so it is worked on a second thread and does not wait for the turn on the first. */
        default boolean worksBeside(JsonRpcMessage message) {
            return false;
        }
    }

    /** Opens the session of a new connection; what the session says goes out through {@code out}. May refuse with an IllegalStateException. */
    @FunctionalInterface
    public interface Opener {
        Session open(Consumer<JsonRpcMessage> out);
    }

    private static final Logger log = LoggerFactory.getLogger(MessageRoute.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String path;
    private final String eventsPath;
    private final Opener opener;
    private final Predicate<String> runsAtOnce;
    private final long keepaliveSeconds;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    /**
     * @param path             where the route answers, for example {@code /acp}
     * @param opener           makes the session of each new connection
     * @param runsAtOnce       the methods that must act while an earlier message is still being worked, such as a cancel; every other
     *                         message is worked on the connection's one thread, in order
     * @param keepaliveSeconds how often an idle stream is written to, which is also how long a gone host program goes unnoticed
     */
    public MessageRoute(String path, Opener opener, Predicate<String> runsAtOnce, long keepaliveSeconds) {
        this.path = path;
        this.eventsPath = path + "/events";
        this.opener = opener;
        this.runsAtOnce = runsAtOnce;
        this.keepaliveSeconds = keepaliveSeconds;
    }

    /** How many host connections are open now. */
    public int openConnections() {
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
            String requested = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals(requested) && "POST".equals(method)) {
                post(exchange, conn);
            } else if (eventsPath.equals(requested) && "GET".equals(method)) {
                events(exchange, conn);
            } else if (path.equals(requested) && "DELETE".equals(method)) {
                Connection ended = connections.remove(conn);
                if (ended != null) {
                    ended.finish();
                }
                reply(exchange, 204);
            } else {
                reply(exchange, 405);
            }
        } catch (IllegalStateException e) {
            log.warn("A request on {} was refused: {}", path, e.getMessage());
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
            log.debug("The stream of {} on {} ended: {}", conn, path, e.getMessage());
        } finally {
            // The stream is the connection's other half: when the host program is gone (a crash sends no DELETE) the next write fails
            // and the connection, its worker thread and its queue are released here instead of living as long as the resident.
            if (connections.remove(conn, connection)) {
                connection.close();
            }
        }
    }

    private Connection connection(String id) {
        return connections.computeIfAbsent(id, key -> new Connection());
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

    /** One host connection: the session, the messages waiting for the stream, and the one thread that works the host's messages in order. */
    private final class Connection {

        final BlockingQueue<String> outbox = new LinkedBlockingQueue<>();
        final ExecutorService worker = thread("connection" + path);
        /** Works what {@link Session#worksBeside} names, in order among themselves; idle and without a thread until the first such message. */
        final ExecutorService beside = thread("connection-beside" + path);
        final Session session;
        volatile boolean closed;

        Connection() {
            this.session = opener.open(this::send);
        }

        void submit(JsonRpcMessage message) {
            Runnable work = () -> {
                try {
                    session.receive(message);
                } catch (RuntimeException e) {
                    log.error("The session on {} failed on {}", path, message.getMethod(), e);
                }
            };
            if (runsAtOnce.test(message.getMethod())) {
                // The person's switch and cancel must act while a prompt is running; queued behind it, they would arrive after the turn
                // they were for.
                work.run();
            } else if (session.worksBeside(message)) {
                beside.execute(work);
            } else {
                worker.execute(work);
            }
        }

        private static ExecutorService thread(String name) {
            return Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            });
        }

        private void send(JsonRpcMessage message) {
            try {
                outbox.add(JSON.writeValueAsString(message));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("a message could not be written", e);
            }
        }

        /** The host is gone: nothing can be delivered, so work in flight is interrupted and the stream ends at once. */
        void close() {
            closed = true;
            worker.shutdownNow();
            beside.shutdownNow();
            outbox.add("");
            endSession();
        }

        /** Tells the session its host is gone; a session that fails in releasing what it holds does not stop the connection from ending. */
        private void endSession() {
            try {
                session.closed();
            } catch (RuntimeException e) {
                log.warn("The session on {} failed in ending", path, e);
            }
        }

        /**
         * The host closed on purpose: the message it is waiting on finishes (up to {@link #FINISH_SECONDS}), then the stream delivers
         * what is queued and ends, so a host that writes its last message and closes its input still gets the answer.
         */
        void finish() {
            finished(worker);
            finished(beside);
            closed = true;
            outbox.add("");
            endSession();
        }

        private void finished(ExecutorService thread) {
            thread.shutdown();
            try {
                if (!thread.awaitTermination(FINISH_SECONDS, TimeUnit.SECONDS)) {
                    thread.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                thread.shutdownNow();
            }
        }
    }
}
