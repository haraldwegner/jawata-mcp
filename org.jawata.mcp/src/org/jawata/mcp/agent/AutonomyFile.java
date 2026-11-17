package org.jawata.mcp.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The chat's one autocontinue state where the jawata hook inside the principal's brain can read it (D57 (m), his words 2026-11-17: "The button is
 * not wired"). The engine holds the state and switches it on the button and on a typed /autocontinue or /autocontinue-off; the hook runs inside the
 * brain's own process and cannot ask the engine, so the engine names this file to that process in {@link #VARIABLE} and writes {@code on} or
 * {@code off} into it on every change. The brain that created the file removes it when it closes.
 */
final class AutonomyFile implements AutoCloseable {

    /** The environment variable that names this file to the brain's process, and so to the hook inside it. */
    static final String VARIABLE = "JAWATA_AUTONOMY_FILE";

    private final Path path;

    private AutonomyFile(Path path) {
        this.path = path;
    }

    /** A new file in the temporary folder, holding {@code off}: nothing is granted until the person grants it. */
    static AutonomyFile create() throws IOException {
        AutonomyFile file = new AutonomyFile(Files.createTempFile("jawata-autonomy-", ".state"));
        file.write(false);
        return file;
    }

    /** The file's path, as the brain's process is told it. */
    Path path() {
        return path;
    }

    /** Writes the state; a write that fails leaves the last state written, which the hook then keeps reading. */
    void write(boolean on) {
        try {
            Files.writeString(path, on ? "on" : "off");
        } catch (IOException ignored) {
            // the hook keeps the last state it could read
        }
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // the temporary folder's own clean-up takes it
        }
    }
}
