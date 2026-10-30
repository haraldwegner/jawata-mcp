package org.jawata.boot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Sprint 28h, S5 (D1, D6): the program a host's agent setting names ({@code java -jar jawata.jar --acp}). It carries ACP messages between
 * the host's stdio and the resident's {@code /acp} route and does nothing else: it holds no agent logic, starts no framework and opens
 * no workspace, so a host spawning it under any environment (an editor's {@code ELECTRON_RUN_AS_NODE}, {@code VSCODE_*}) gets only a pipe.
 *
 * <p>One line read from stdin is one JSON-RPC message, POSTed to {@code /acp}; every server-sent event on {@code /acp/events} is one
 * message written as one line to stdout. The resident is named by {@code --url} (or {@code JAWATA_URL}), its key by {@code --key} (or
 * {@code JAWATA_KEY}). A refused key ends the program with status 3 and a line on stderr, never a silent hang.</p>
 */
final class AcpBridge {

    static final int USAGE = 2;
    static final int REFUSED = 3;
    static final int STREAM_ENDED = 4;
    private static final long DRAIN_MILLIS = 60_000;

    private final String base;
    private final String key;
    private final String conn = UUID.randomUUID().toString();
    private final HttpClient http = HttpClient.newHttpClient();
    private final PrintStream out;
    private final PrintStream err;
    private volatile boolean closing;

    AcpBridge(String base, String key, PrintStream out, PrintStream err) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.key = key;
        this.out = out;
        this.err = err;
    }

    /** Runs the bridge for {@code args} and the process environment; returns the exit status. */
    static int run(String[] args, InputStream in, PrintStream out, PrintStream err) throws IOException {
        String url = System.getenv("JAWATA_URL");
        String key = System.getenv("JAWATA_KEY");
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--url".equals(args[i])) {
                url = args[i + 1];
            } else if ("--key".equals(args[i])) {
                key = args[i + 1];
            }
        }
        if (url == null || url.isBlank()) {
            err.println("jawata --acp: no resident named; pass --url <base url> or set JAWATA_URL");
            return USAGE;
        }
        return new AcpBridge(url, key, out, err).pipe(in);
    }

    private int pipe(InputStream in) throws IOException {
        Thread events = new Thread(this::stream, "acp-events");
        events.setDaemon(true);
        events.start();
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                if (line.isBlank()) {
                    continue;
                }
                int status = send("POST", "/acp", HttpRequest.BodyPublishers.ofString(line));
                if (status != 202) {
                    err.println("jawata --acp: the resident answered " + status + " to a message" + (status == 401 ? " (the key was refused)" : ""));
                    return REFUSED;
                }
            }
        }
        closing = true;
        send("DELETE", "/acp", HttpRequest.BodyPublishers.noBody());
        try {
            events.join(DRAIN_MILLIS); // the resident ends the stream after it has delivered what was queued
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    private int send(String method, String path, HttpRequest.BodyPublisher body) throws IOException {
        try {
            return http.send(request(path).method(method, body).header("Content-Type", "application/json").build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private void stream() {
        try {
            HttpResponse<java.util.stream.Stream<String>> response = http.send(request("/acp/events").GET().build(),
                HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                err.println("jawata --acp: the resident answered " + response.statusCode() + " to the event stream"
                    + (response.statusCode() == 401 ? " (the key was refused)" : ""));
                Runtime.getRuntime().halt(REFUSED);
            }
            response.body().forEach(line -> {
                if (line.startsWith("data: ")) {
                    out.println(line.substring("data: ".length()));
                    out.flush();
                }
            });
        } catch (IOException | UncheckedIOException e) {
            err.println("jawata --acp: the event stream ended: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!closing) {
            // The agent can no longer reach the host. Staying up would let the host write into a pipe nobody answers, so end loudly.
            err.println("jawata --acp: the resident closed the event stream; ending so the host sees it");
            err.flush();
            Runtime.getRuntime().halt(STREAM_ENDED);
        }
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path + "?conn=" + URLEncoder.encode(conn, StandardCharsets.UTF_8)));
        if (key != null && !key.isBlank()) {
            builder.header("Authorization", "Bearer " + key);
        }
        return builder;
    }
}
