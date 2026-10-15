package org.jawata.mcp.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * HTTP transport using the JDK's built-in
 * {@link com.sun.net.httpserver.HttpServer}.
 *
 * <p>Sprint 14a Stage 3 chose the JDK server over an embedded Jetty bundle
 * because the Eclipse target platform did not already include Jetty
 * (pre-flight grep returned empty). Adding Jetty would require a target
 * platform bump plus the Plan-agent-flagged risk of OSGi class-loader vs
 * servlet-thread friction with JDT APIs. The JDK server avoids all of
 * that: no target changes, no new bundle dependencies, no OSGi
 * class-loader concerns, works guaranteed with Java 21.
 *
 * <p><b>Endpoints:</b>
 * <ul>
 *   <li>{@code POST /mcp} — request-response. Body is a JSON-RPC message;
 *       returns the JSON-RPC response (200 + application/json) or 204
 *       (notification). 400 on malformed JSON, 401 on missing/wrong
 *       Bearer token, 405 on non-POST.</li>
 *   <li>{@code GET /mcp/events} — Server-Sent Events (SSE) channel.
 *       Long-lived response of {@code event: <type>\ndata: <json>\n\n}
 *       frames. Sends an initial {@code ready} event on subscription,
 *       then {@code heartbeat} every {@code heartbeatInterval} (default
 *       30 s) until the client disconnects or the transport is closed.
 *       401 on missing/wrong token, 405 on non-GET.</li>
 * </ul>
 *
 * <p><b>READY contract:</b> after a successful bind, exactly one line is
 * written to stdout: {@code READY url=http://<bind>:<port> <token field>}.
 * The token field is decided by {@link ResolvedToken#readyTokenField()}:
 * {@code token-file=<path>} when the token lives in a file,
 * {@code token=provided} when the launcher handed it in, and
 * {@code token=<token>} only for a bare manual launch that has no other
 * channel. The manager-side launcher tees stdout into a log FILE, so a token
 * printed here persists on disk after the process is gone — which is why an
 * automated launch must name a token file (studio#14). The token MUST NOT
 * appear elsewhere on stdout either; see {@code tokenNotInStdoutBeyondReadyLine}.
 *
 * <p><b>Bearer auth:</b> every request must carry
 * {@code Authorization: Bearer <token>}; missing or wrong → 401 with
 * empty body. Localhost binding is the secondary gate.
 *
 * <p><b>Tool-output event integration (Sprint 14a Stage 4 caveat):</b>
 * the SSE channel infrastructure ships in v1.8.5 (heartbeats + the
 * {@link #sendEvent} public API), but the dispatching tools do not yet
 * emit progress events. Wiring up the tool layer to call
 * {@link #sendEvent} for progress / partial-results is a v1.8.6 follow-up
 * (separate scope from the bug #9 leak fix).
 */
public class HttpTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(HttpTransport.class);

    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    /** REL44-32: three silent probes 30 seconds apart — about 90 seconds to notice a dead listener. */
    private static final Duration DEFAULT_WATCHDOG_INTERVAL = Duration.ofSeconds(30);
    private static final int DEFAULT_WATCHDOG_FAILURES = 3;
    private static final Duration WATCHDOG_PROBE_TIMEOUT = Duration.ofSeconds(5);

    private final int requestedPort;
    private final String bindAddress;
    private final String token;
    /**
     * How the READY line discloses the token — studio#14. The manager tees
     * stdout into a workspace log file, so a token printed here outlives the
     * process on disk. A {@link ResolvedToken} answers this itself; the
     * token-only constructor keeps the historical {@code token=<token>} form
     * for a bare manual launch, which has no other channel.
     */
    private final String readyTokenField;
    private final Duration heartbeatInterval;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<SseSubscriber> subscribers = new CopyOnWriteArrayList<>();

    /**
     * REL44-32: how often the transport asks its own listener whether it answers, how many
     * CONSECUTIVE silences declare it deaf, and what to do then (production: exit non-zero).
     */
    private final Duration watchdogInterval;
    private final int watchdogFailures;
    private final Runnable onDeaf;
    private ScheduledExecutorService watchdog;

    private HttpServer server;
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private volatile int actualPort = -1;

    public HttpTransport(int port, String bindAddress, String token) {
        this(port, bindAddress, token, DEFAULT_HEARTBEAT_INTERVAL);
    }

    /**
     * Production constructor — the token knows how it may be disclosed.
     *
     * <p>Prefer this over the raw-token form: with a token file in play the
     * READY line names the FILE instead of the secret, which is what keeps the
     * credential out of the manager's log (studio#14).
     */
    public HttpTransport(int port, String bindAddress, ResolvedToken resolved) {
        this(port, bindAddress, resolved.token(), DEFAULT_HEARTBEAT_INTERVAL,
            resolved.readyTokenField());
    }

    /**
     * Package-private constructor for tests that need a short heartbeat
     * interval to exercise the keep-alive path quickly. Production callers
     * use the three-arg constructor with the 30s default.
     */
    HttpTransport(int port, String bindAddress, String token, Duration heartbeatInterval) {
        this(port, bindAddress, token, heartbeatInterval, "token=" + token);
    }

    private HttpTransport(int port, String bindAddress, String token, Duration heartbeatInterval,
                          String readyTokenField) {
        this(port, bindAddress, token, heartbeatInterval, readyTokenField,
            DEFAULT_WATCHDOG_INTERVAL, DEFAULT_WATCHDOG_FAILURES, HttpTransport::exitBecauseDeaf);
    }

    /**
     * Package-private, for the test that must see the watchdog fire quickly and must not exit the
     * test's own process: a short probe interval, a small failure threshold, and what to do on
     * deafness (REL44-32).
     */
    HttpTransport(int port, String bindAddress, String token, Duration heartbeatInterval,
                  Duration watchdogInterval, int watchdogFailures, Runnable onDeaf) {
        this(port, bindAddress, token, heartbeatInterval, "token=" + token,
            watchdogInterval, watchdogFailures, onDeaf);
    }

    private HttpTransport(int port, String bindAddress, String token, Duration heartbeatInterval,
                          String readyTokenField, Duration watchdogInterval, int watchdogFailures,
                          Runnable onDeaf) {
        if (bindAddress == null || bindAddress.isBlank()) {
            throw new IllegalArgumentException("bindAddress required");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token required (use TokenGenerator.generate() if not provided)");
        }
        if (heartbeatInterval == null || heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
            throw new IllegalArgumentException("heartbeatInterval must be positive");
        }
        this.requestedPort = port;
        this.bindAddress = bindAddress;
        this.token = token;
        this.readyTokenField = readyTokenField;
        this.heartbeatInterval = heartbeatInterval;
        this.watchdogInterval = watchdogInterval;
        this.watchdogFailures = watchdogFailures;
        this.onDeaf = onDeaf;
    }

    /**
     * Start the HTTP listener, emit READY on stdout, then block until
     * {@link #close} is invoked from another thread.
     *
     * <p>If {@code requestedPort} was 0 (auto-allocate), the OS-assigned
     * port is captured and exposed via the READY line + {@link #getActualPort}.
     */
    @Override
    public void run(MessageHandler handler) throws IOException, InterruptedException {
        if (handler == null) {
            throw new IllegalArgumentException("handler required");
        }
        server = HttpServer.create(new InetSocketAddress(bindAddress, requestedPort), 0);
        server.createContext("/mcp", new McpHandler(handler, token, objectMapper));
        server.createContext("/mcp/events", new EventsHandler(token, heartbeatInterval, subscribers));
        // Cached pool — POST handlers return quickly; SSE handlers hold a
        // thread for the connection lifetime. Cached scales as needed
        // without starving POSTs (an issue with fixed pools when several
        // SSE clients are connected).
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "HttpTransport-worker");
            t.setDaemon(true);
            return t;
        }));
        server.start();

        actualPort = server.getAddress().getPort();
        // READY contract: a single line on stdout. The token field is the
        // token's own disclosure decision (studio#14) — with a token file it
        // names the file, so the manager's log never holds the secret.
        System.out.println("READY url=http://" + bindAddress + ":" + actualPort
            + " " + readyTokenField);
        readyLatch.countDown();

        log.info("HTTP transport listening on {}:{}", bindAddress, actualPort);

        // REL44-32: ask the listener itself, on a schedule. The JDK server runs its accept loop on
        // ONE dispatcher thread and, if that dies, the process lives on deaf — measured: ten hours,
        // zero HTTP threads, 51 connections queued, a main thread waiting on the latch below.
        watchdog = new ServerWatchdog(
            ServerWatchdog.httpProbe(localMcpUrl(), WATCHDOG_PROBE_TIMEOUT), watchdogFailures,
            onDeaf).start(watchdogInterval);

        shutdownLatch.await();
        log.info("HTTP transport shutdown signal received");
    }

    /**
     * Returns the OS-assigned port once {@link #run} has bound the
     * listener. Used by tests and by the manager's READY-line capture.
     * Blocks until the server is ready (throws if interrupted).
     */
    public int getActualPort() throws InterruptedException {
        readyLatch.await();
        return actualPort;
    }

    /**
     * The {@code /mcp} URL a process on THIS machine reaches this transport at, or null before
     * {@link #run} has bound the listener — never blocks. A listener on every interface is reached
     * at loopback; one on a named address is reached at that address. Used to hand a hired brain
     * this resident as its one MCP server (jawata-mcp 4.4.1).
     */
    public String localMcpUrl() {
        int port = actualPort;
        if (port < 0) {
            return null;
        }
        String host = "0.0.0.0".equals(bindAddress) || "::".equals(bindAddress)
            ? "127.0.0.1"
            : bindAddress;
        return "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port + "/mcp";
    }

    /** The bearer token every request to this transport must carry. */
    public String bearerToken() {
        return token;
    }

    /**
     * Broadcast an SSE event to every currently-subscribed
     * {@code /mcp/events} client. Public API ready for tool-layer
     * progress integration (v1.8.6 wiring; see class-level Javadoc).
     *
     * @param type event name (becomes {@code event: <type>} in the frame)
     * @param dataJson event body as already-serialized JSON
     *                 (becomes {@code data: <dataJson>})
     */
    public void sendEvent(String type, String dataJson) {
        for (SseSubscriber sub : subscribers) {
            sub.send(type, dataJson);
        }
    }

    /**
     * What a resident does when its listener has stopped answering: say so, then exit non-zero so
     * whatever supervises it sees a dead process instead of a deaf one (REL44-32). A graceful exit
     * is tried first so the shared store closes cleanly; if that hangs — a deaf process may have
     * other things wrong with it — the process is halted after fifteen seconds.
     */
    private static void exitBecauseDeaf() {
        log.error("HTTP LISTENER STOPPED ANSWERING while the process is alive — exiting with status 70"
            + " so the resident is restarted instead of staying deaf");
        Thread fallback = new Thread(() -> {
            try {
                Thread.sleep(15_000);
            } catch (InterruptedException ignored) {
                return;
            }
            Runtime.getRuntime().halt(70);
        }, "HttpTransport-deaf-halt");
        fallback.setDaemon(true);
        fallback.start();
        System.exit(70);
    }

    /**
     * Test seam: stop the HTTP listener WITHOUT the transport's own shutdown, which reproduces the
     * measured failure — the listener gone while the process lives (REL44-32).
     */
    void stopListenerOnly() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Override
    public void close() {
        if (watchdog != null) {
            watchdog.shutdownNow();
            watchdog = null;
        }
        // Close subscribers first so SSE threads exit their wait loops
        // before server.stop() pulls their socket out from under them.
        for (SseSubscriber sub : subscribers) {
            sub.close();
        }
        subscribers.clear();

        if (server != null) {
            try {
                server.stop(0);
            } catch (Exception e) {
                log.warn("Error stopping HTTP server: {}", e.getMessage());
            }
            server = null;
        }
        shutdownLatch.countDown();
    }

    /**
     * Translates HTTP requests on {@code /mcp} into JSON-RPC dispatch via
     * the application-supplied {@link MessageHandler}. Enforces Bearer
     * auth + basic JSON syntactic validation up-front.
     */
    private static final class McpHandler implements HttpHandler {

        private final MessageHandler handler;
        private final String expectedAuthHeader;
        private final ObjectMapper objectMapper;

        /**
         * THE FRAGMENTATION FIX (Sprint 28g S9a M4). One identity for every
         * caller that does not echo a session id — a FIELD, so it is minted once
         * per transport instead of once per request.
         *
         * <p>What it replaces scattered an agent's work across as many
         * identities as it made calls, so every per-session check read a ledger
         * that could never accumulate. What it creates is a SHARED BUCKET for
         * all header-less callers, which is a deliberate trade:
         * {@code Origin.RESIDENT_MINTED} is what stops a reader taking that
         * bucket's history for one agent's session.</p>
         */
        private final org.jawata.mcp.models.CallerIdentity anonymous =
            org.jawata.mcp.models.CallerIdentity.minted("http");

        McpHandler(MessageHandler handler, String token, ObjectMapper objectMapper) {
            this.handler = handler;
            this.expectedAuthHeader = "Bearer " + token;
            this.objectMapper = objectMapper;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendEmpty(exchange, 405);
                    return;
                }

                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (auth == null || !auth.equals(expectedAuthHeader)) {
                    sendEmpty(exchange, 401);
                    return;
                }

                byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
                if (bodyBytes.length == 0) {
                    sendEmpty(exchange, 400);
                    return;
                }
                String body = new String(bodyBytes, StandardCharsets.UTF_8);
                if (body.isBlank()) {
                    sendEmpty(exchange, 400);
                    return;
                }

                // Syntactic JSON check up-front so the dispatcher only sees
                // well-formed messages. McpProtocolHandler does its own
                // semantic JSON-RPC validation downstream.
                try {
                    objectMapper.readTree(body);
                } catch (JsonProcessingException e) {
                    sendEmpty(exchange, 400);
                    return;
                }

                // Sprint 26, amended 28g S9a M4: session identity — honour the
                // client's Mcp-Session-Id, fall back to the ONE minted identity
                // above when absent, echo it back so the client can carry it
                // forward. Keys the per-session ledger and the learner event
                // stream.
                //
                // The fallback used to be `"http-" + randomUUID()` RIGHT HERE,
                // which minted a fresh identity per request — so a client that
                // never echoes the header had its whole history scattered one
                // call per identity, and no per-session check could ever see
                // two of its calls together.
                String supplied = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
                org.jawata.mcp.models.CallerIdentity caller =
                    supplied == null || supplied.isBlank()
                        ? this.anonymous
                        : org.jawata.mcp.models.CallerIdentity.supplied(supplied);
                exchange.getResponseHeaders().add("Mcp-Session-Id", caller.id());

                // Sprint 28b D7: the studio↔store contract version — ALWAYS
                // echoed, so a caller that sends its own version can detect a
                // mismatch as a typed, countable fact instead of silence (the
                // 21c→27a semantic drift was invisible precisely because both
                // sides stayed individually green).
                exchange.getResponseHeaders().add(
                    org.jawata.mcp.field.FieldContract.HEADER,
                    String.valueOf(org.jawata.mcp.field.FieldContract.VERSION));

                // mcp#27: THE LOOP GUARD'S SERVING HALF. A request carrying this header came
                // from another resident's miss path; answering it must not start a peek of
                // our own, or two residents pointed at each other recurse until something
                // gives. Cleared in the finally below because the worker thread is pooled
                // and outlives the request.
                boolean fromASibling = exchange.getRequestHeaders()
                    .getFirst(org.jawata.mcp.models.SiblingPeek.PEEK_HEADER) != null;
                if (fromASibling) {
                    org.jawata.mcp.models.SiblingPeek.servingAPeek(true);
                }
                String response;
                try {
                    response = handler.handle(body, caller);
                } finally {
                    if (fromASibling) {
                        org.jawata.mcp.models.SiblingPeek.servingAPeek(false);
                    }
                }
                if (response != null) {
                    byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, respBytes.length);
                    exchange.getResponseBody().write(respBytes);
                } else {
                    // Notification — no response body per JSON-RPC.
                    exchange.sendResponseHeaders(204, -1);
                }
            } catch (Exception e) {
                log.error("HTTP dispatch error", e);
                try {
                    sendEmpty(exchange, 500);
                } catch (IOException ignored) {
                    // already failed; drop
                }
            } finally {
                exchange.close();
            }
        }
    }

    /**
     * GET {@code /mcp/events}: opens a long-lived SSE stream, sends an
     * initial {@code ready} event, then {@code heartbeat} every
     * {@code heartbeatInterval} until the client disconnects or the
     * transport is {@link #close closed}. Registered subscribers also
     * receive any event broadcast via {@link #sendEvent}.
     */
    private static final class EventsHandler implements HttpHandler {

        private final String expectedAuthHeader;
        private final Duration heartbeatInterval;
        private final List<SseSubscriber> subscribers;

        EventsHandler(String token, Duration heartbeatInterval, List<SseSubscriber> subscribers) {
            this.expectedAuthHeader = "Bearer " + token;
            this.heartbeatInterval = heartbeatInterval;
            this.subscribers = subscribers;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendEmpty(exchange, 405);
                exchange.close();
                return;
            }
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.equals(expectedAuthHeader)) {
                sendEmpty(exchange, 401);
                exchange.close();
                return;
            }

            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
            // 0 = chunked transfer encoding (response length not known in advance).
            exchange.sendResponseHeaders(200, 0);

            SseSubscriber sub = new SseSubscriber(exchange.getResponseBody());
            subscribers.add(sub);

            try {
                sub.send("ready", "{}");
                while (!sub.isClosed()) {
                    sub.waitForCloseOrTimeout(heartbeatInterval.toMillis());
                    if (sub.isClosed()) break;
                    sub.send("heartbeat", "{}");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                subscribers.remove(sub);
                sub.close();
                try {
                    exchange.close();
                } catch (Exception ignored) {
                    // already closing
                }
            }
        }
    }

    /**
     * Per-connection SSE writer. Frame format is
     * {@code event: <type>\ndata: <data>\n\n}. Write failures (client
     * disconnect) mark the subscriber as closed so the heartbeat loop
     * exits cleanly. {@link #close} can be invoked from another thread
     * (by {@link HttpTransport#close} on shutdown) to interrupt the
     * heartbeat wait.
     */
    static final class SseSubscriber {

        private final OutputStream out;
        private volatile boolean closed = false;
        private final Object writeLock = new Object();
        private final Object sleepLock = new Object();

        SseSubscriber(OutputStream out) {
            this.out = out;
        }

        /**
         * Write one SSE frame. Silently drops if the subscriber is closed
         * or the wire-write throws (treats as client disconnect).
         */
        void send(String type, String dataJson) {
            if (closed) return;
            byte[] frame = ("event: " + type + "\ndata: " + dataJson + "\n\n")
                .getBytes(StandardCharsets.UTF_8);
            synchronized (writeLock) {
                if (closed) return;
                try {
                    out.write(frame);
                    out.flush();
                } catch (IOException e) {
                    closed = true;
                    // Wake the heartbeat loop so it can clean up.
                    synchronized (sleepLock) {
                        sleepLock.notifyAll();
                    }
                }
            }
        }

        /** Wait up to {@code millis} ms, returning early on {@link #close}. */
        void waitForCloseOrTimeout(long millis) throws InterruptedException {
            synchronized (sleepLock) {
                if (closed) return;
                sleepLock.wait(millis);
            }
        }

        boolean isClosed() {
            return closed;
        }

        void close() {
            closed = true;
            synchronized (sleepLock) {
                sleepLock.notifyAll();
            }
        }
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }
}
