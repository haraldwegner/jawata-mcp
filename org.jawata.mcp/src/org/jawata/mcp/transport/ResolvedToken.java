package org.jawata.mcp.transport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;

import org.jawata.core.host.HostFs;

/**
 * The HTTP transport's Bearer token, resolved from the CLI — and, when a token
 * file is named, FROM that file, which is the token's home.
 *
 * <p><b>Why the file exists (studio#14).</b> A token passed as {@code -token T}
 * is visible to every process on the machine: on Linux {@code /proc/<pid>/cmdline}
 * is world-readable, and a foreign agent harvested both residents' URLs and
 * tokens from {@code ps aux} on 2026-08-17. Command-line arguments are not a
 * place for a credential. A file is: it carries owner-only permissions.
 *
 * <p><b>One flag, both directions.</b> {@code -token-file PATH} means "the
 * token lives here":
 * <ul>
 *   <li>the file exists and is non-empty → the token is READ from it (the
 *       manager pre-writes it, so nothing sensitive reaches argv);</li>
 *   <li>the file is absent → the token is generated and WRITTEN there
 *       {@code 0600} (the standalone flow: a launcher reads the file instead of
 *       scraping stdout).</li>
 * </ul>
 *
 * <p><b>A named-but-unusable file FAILS the start, loudly.</b> Quietly
 * generating a different token would leave a server nobody holds a credential
 * for: every client would get 401 and the fault would look like a transport
 * bug. Refusing to start names the path instead.
 */
public final class ResolvedToken {

    private static final String OWNER_ONLY = "rw-------";

    private final String token;
    private final Path tokenFile;
    private final boolean supplied;

    private ResolvedToken(String token, Path tokenFile, boolean supplied) {
        this.token = Objects.requireNonNull(token, "token");
        this.tokenFile = tokenFile;
        this.supplied = supplied;
    }

    /**
     * Resolve the token for this configuration, performing the token file's
     * read or write as the rules above describe.
     *
     * @throws IllegalStateException if a token file was named and can be
     *         neither read nor written — see the class comment for why this is
     *         fatal rather than a fallback.
     */
    public static ResolvedToken resolve(TransportConfig config) {
        Objects.requireNonNull(config, "config");
        Path file = config.getTokenFile();

        if (config.getToken() != null && !config.getToken().isBlank()) {
            // Explicit -token wins. Still mirror it into the file when one is
            // named, so a launcher that prefers the file finds the same value.
            if (file != null) {
                write(file, config.getToken());
            }
            return new ResolvedToken(config.getToken(), file, true);
        }

        if (file != null) {
            String existing = readIfPresent(file);
            if (existing != null) {
                return new ResolvedToken(existing, file, true);
            }
            String generated = TokenGenerator.generate();
            write(file, generated);
            return new ResolvedToken(generated, file, false);
        }

        return new ResolvedToken(TokenGenerator.generate(), null, false);
    }

    /** The token itself. */
    public String token() {
        return token;
    }

    /** The file the token lives in, or {@code null} when none was named. */
    public Path tokenFile() {
        return tokenFile;
    }

    /** Whether the token came from outside this JVM (a flag or an existing file). */
    public boolean supplied() {
        return supplied;
    }

    /**
     * How the token is disclosed on the READY line.
     *
     * <p>The READY line is echoed into the manager's workspace log file, so a
     * token printed here persists on disk long after the process is gone. When
     * a file holds the token the line names the FILE; when the token was handed
     * in, the holder already has it and the line says so.
     *
     * <p>A bare manual launch — generated token, no file — still prints the
     * token, because that is the only channel such a launcher has. Anything
     * automated should name a token file.
     */
    public String readyTokenField() {
        if (tokenFile != null) {
            return "token-file=" + tokenFile;
        }
        if (supplied) {
            return "token=provided";
        }
        return "token=" + token;
    }

    private static String readIfPresent(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        String body;
        try {
            body = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("token file exists but cannot be read: " + file
                + " — fix the file or its permissions; refusing to start with a token"
                + " no client holds", e);
        }
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalStateException("token file is empty: " + file
                + " — delete it to have one generated, or write the token into it");
        }
        return trimmed;
    }

    private static void write(Path file, String token) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Create with owner-only permissions BEFORE the secret is in it —
            // a write-then-chmod leaves a window where the file is readable.
            Files.deleteIfExists(file);
            if (HostFs.supportsPosixPermissions()) {
                Files.createFile(file,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY)));
            } else {
                Files.createFile(file);
                java.io.File f = file.toFile();
                f.setReadable(false, false);
                f.setWritable(false, false);
                f.setReadable(true, true);
                f.setWritable(true, true);
            }
            Files.writeString(file, token + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("token file cannot be written: " + file
                + " — the caller asked for the token to live there, so starting without"
                + " it would leave that caller unable to authenticate", e);
        }
    }
}
