package org.jawata.boot;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
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
import java.util.Locale;
import java.util.UUID;

/**
 * Sprint 28h, S5 and S16a (D1, D6): the program a host's setting names ({@code java -jar jawata.jar --acp} for the agent, {@code --lsp} for
 * the Java language server). It carries the host's messages between its stdio and one route of the resident and does nothing else: it
 * holds no agent logic and no language logic, starts no framework and opens no workspace, so a host spawning it under any environment
 * (an editor's {@code ELECTRON_RUN_AS_NODE}, {@code VSCODE_*}) gets only a pipe.
 *
 * <p>Every message read from stdin is POSTed to the route ({@code /acp} or {@code /lsp}); every server-sent event on {@code <route>/events}
 * is one message written to stdout. The two hosts differ only in how stdio carries a message ({@link Framing}): the agent's host writes one
 * line per message, an editor writes a {@code Content-Length} header and a body of that many bytes. The resident is named by {@code --url}
 * (or {@code JAWATA_URL}), its key by {@code --key} (or {@code JAWATA_KEY}). A refused key ends the program with status 3 and a line on
 * stderr, never a silent hang.</p>
 */
final class Bridge {

    static final int USAGE = 2;
    static final int REFUSED = 3;
    static final int STREAM_ENDED = 4;
    private static final long DRAIN_MILLIS = 60_000;

    /** How a host's stdio carries one message. */
    interface Framing {

        /** Reads the messages the host writes to {@code in}. */
        MessageReader reader(InputStream in);

        /** Writes one message to the host. */
        void write(PrintStream out, String body);
    }

    /** The messages a host has written, one at a time. */
    interface MessageReader {

        /** The next message, or null when the host has closed its input. */
        String next() throws IOException;
    }

    /** One line is one message; a blank line is nothing. */
    static final Framing LINES = new Framing() {
        @Override
        public MessageReader reader(InputStream in) {
            BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return () -> {
                for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                    if (!line.isBlank()) {
                        return line;
                    }
                }
                return null;
            };
        }

        @Override
        public void write(PrintStream out, String body) {
            out.println(body);
            out.flush();
        }
    };

    /**
     * The language protocol's framing: header lines ending in a blank line, one of them {@code Content-Length: n}, then a body of n BYTES.
     * It is kept apart from the engine's own codec on purpose: this module holds no engine class, and reading a header takes a few lines.
     */
    static final Framing CONTENT_LENGTH = new Framing() {
        @Override
        public MessageReader reader(InputStream in) {
            BufferedInputStream bytes = new BufferedInputStream(in);
            return () -> {
                int length = -1;
                while (true) {
                    String header = headerLine(bytes);
                    if (header == null) {
                        return null;
                    }
                    if (header.isEmpty()) {
                        break;
                    }
                    int colon = header.indexOf(':');
                    if (colon > 0 && "content-length".equals(header.substring(0, colon).trim().toLowerCase(Locale.ROOT))) {
                        try {
                            length = Integer.parseInt(header.substring(colon + 1).trim());
                        } catch (NumberFormatException e) {
                            throw new IOException("a Content-Length header that is not a number: " + header, e);
                        }
                    }
                }
                if (length < 0) {
                    throw new IOException("a message with no Content-Length header");
                }
                byte[] body = bytes.readNBytes(length);
                if (body.length < length) {
                    return null; // the host closed in the middle of a message: there is no message to send
                }
                return new String(body, StandardCharsets.UTF_8);
            };
        }

        @Override
        public void write(PrintStream out, String body) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            byte[] header = ("Content-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            out.write(header, 0, header.length);
            out.write(bytes, 0, bytes.length);
            out.flush();
        }

        /** One header line without its line end, or null at the end of the input. */
        private String headerLine(InputStream in) throws IOException {
            StringBuilder line = new StringBuilder();
            for (int b = in.read(); b >= 0; b = in.read()) {
                if (b == '\n') {
                    int end = line.length();
                    return line.substring(0, end > 0 && line.charAt(end - 1) == '\r' ? end - 1 : end);
                }
                line.append((char) b);
            }
            return line.length() == 0 ? null : line.toString();
        }
    };

    private final String flag;
    private final String route;
    private final Framing framing;
    private final String base;
    private final String key;
    private final String conn = UUID.randomUUID().toString();
    private final HttpClient http = HttpClient.newHttpClient();
    private final PrintStream out;
    private final PrintStream err;
    private volatile boolean closing;

    Bridge(String flag, String route, Framing framing, String base, String key, PrintStream out, PrintStream err) {
        this.flag = flag;
        this.route = route;
        this.framing = framing;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.key = key;
        this.out = out;
        this.err = err;
    }

    /** Runs the agent's bridge ({@code --acp}) for {@code args} and the process environment; returns the exit status. */
    static int runAcp(String[] args, InputStream in, PrintStream out, PrintStream err) throws IOException {
        return run("--acp", "/acp", LINES, args, in, out, err);
    }

    /** Runs the language server's bridge ({@code --lsp}) for {@code args} and the process environment; returns the exit status. */
    static int runLsp(String[] args, InputStream in, PrintStream out, PrintStream err) throws IOException {
        return run("--lsp", "/lsp", CONTENT_LENGTH, args, in, out, err);
    }

    private static int run(String flag, String route, Framing framing, String[] args, InputStream in, PrintStream out, PrintStream err)
            throws IOException {
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
            err.println("jawata " + flag + ": no resident named; pass --url <base url> or set JAWATA_URL");
            return USAGE;
        }
        return new Bridge(flag, route, framing, url, key, out, err).pipe(in);
    }

    private int pipe(InputStream in) throws IOException {
        Thread events = new Thread(this::stream, flag.substring(2) + "-events");
        events.setDaemon(true);
        events.start();
        MessageReader messages = framing.reader(in);
        for (String message = messages.next(); message != null; message = messages.next()) {
            int status = send("POST", route, HttpRequest.BodyPublishers.ofString(message));
            if (status != 202) {
                err.println("jawata " + flag + ": the resident answered " + status + " to a message" + (status == 401 ? " (the key was refused)" : ""));
                return REFUSED;
            }
        }
        closing = true;
        send("DELETE", route, HttpRequest.BodyPublishers.noBody());
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
            HttpResponse<java.util.stream.Stream<String>> response = http.send(request(route + "/events").GET().build(),
                HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                err.println("jawata " + flag + ": the resident answered " + response.statusCode() + " to the event stream"
                    + (response.statusCode() == 401 ? " (the key was refused)" : ""));
                Runtime.getRuntime().halt(REFUSED);
            }
            response.body().forEach(line -> {
                if (line.startsWith("data: ")) {
                    framing.write(out, line.substring("data: ".length()));
                }
            });
        } catch (IOException | UncheckedIOException e) {
            err.println("jawata " + flag + ": the event stream ended: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!closing) {
            // The server can no longer reach the host. Staying up would let the host write into a pipe nobody answers, so end loudly.
            err.println("jawata " + flag + ": the resident closed the event stream; ending so the host sees it");
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
