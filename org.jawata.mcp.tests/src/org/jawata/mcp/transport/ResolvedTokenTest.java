package org.jawata.mcp.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.jawata.core.host.HostFs;

import static org.junit.jupiter.api.Assertions.*;

/**
 * studio#14 — the token must not travel on argv, and must not be printed into
 * a log the manager keeps.
 *
 * <p>The defect these tests close was found in anger: on 2026-08-17 a foreign
 * agent on the same machine ran {@code ps aux}, read both residents' URLs and
 * bearer tokens out of their command lines, and started calling the endpoints.
 * The cure is that {@code -token-file} is the token's HOME — read from it when
 * it exists, generated into it {@code 0600} when it does not — and that the
 * READY line names the file rather than the secret.
 */
class ResolvedTokenTest {

    private static TransportConfig withTokenFile(Path file) {
        return TransportConfig.fromArgs(new String[] { "-token-file", file.toString() });
    }

    @Test
    @DisplayName("an existing token file IS the token — the resident reads it, nothing lands on argv")
    void existingFileIsRead(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("resident.token");
        Files.writeString(file, "cafebabe1234\n");

        ResolvedToken resolved = ResolvedToken.resolve(withTokenFile(file));

        assertEquals("cafebabe1234", resolved.token(), "the file's content is the token, trimmed");
        assertTrue(resolved.supplied(), "a token from the file came from outside this JVM");
    }

    @Test
    @DisplayName("an absent token file is CREATED 0600 with a fresh token — the standalone flow")
    void absentFileIsWrittenOwnerOnly(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nested").resolve("resident.token");

        ResolvedToken resolved = ResolvedToken.resolve(withTokenFile(file));

        assertTrue(Files.exists(file), "the token's home is created, parents included");
        assertEquals(resolved.token(), Files.readString(file).trim(),
            "what the server uses is what the launcher can read");
        assertEquals(64, resolved.token().length(), "a generated token is TokenGenerator's shape");
        assertFalse(resolved.supplied(), "generated here, not handed in");

        if (HostFs.supportsPosixPermissions()) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                perms,
                "a credential file readable by anyone else is the argv defect in a new place");
        }
    }

    @Test
    @DisplayName("-token still wins, and is mirrored into the file so either side can read it")
    void explicitTokenWinsAndIsMirrored(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("resident.token");
        TransportConfig cfg = TransportConfig.fromArgs(new String[] {
            "-token", "handedin", "-token-file", file.toString()
        });

        ResolvedToken resolved = ResolvedToken.resolve(cfg);

        assertEquals("handedin", resolved.token());
        assertEquals("handedin", Files.readString(file).trim());
        assertTrue(resolved.supplied());
    }

    @Test
    @DisplayName("no token file at all: generated in memory, exactly as before")
    void noFileGeneratesInMemory() {
        ResolvedToken resolved = ResolvedToken.resolve(TransportConfig.fromArgs(new String[0]));

        assertNull(resolved.tokenFile());
        assertEquals(64, resolved.token().length());
        assertFalse(resolved.supplied());
    }

    @Test
    @DisplayName("a named-but-EMPTY token file refuses the start rather than inventing a token")
    void emptyFileIsFatal(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("resident.token");
        Files.writeString(file, "   \n");

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> ResolvedToken.resolve(withTokenFile(file)));
        assertTrue(e.getMessage().contains(file.toString()),
            "the refusal must name the path — silently generating a different token would"
                + " leave every client with a 401 and no explanation: " + e.getMessage());
    }

    @Test
    @DisplayName("the READY line names the FILE when there is one — the manager logs that line")
    void readyFieldNamesTheFile(@TempDir Path dir) {
        Path file = dir.resolve("resident.token");

        ResolvedToken resolved = ResolvedToken.resolve(withTokenFile(file));

        assertEquals("token-file=" + file, resolved.readyTokenField());
        assertFalse(resolved.readyTokenField().contains(resolved.token()),
            "the token itself must not reach the READY line when a file holds it —"
                + " the manager tees stdout into a workspace log that outlives the process");
    }

    @Test
    @DisplayName("a handed-in token is acknowledged, never echoed")
    void readyFieldHidesASuppliedToken() {
        TransportConfig cfg = TransportConfig.fromArgs(new String[] { "-token", "handedin" });

        ResolvedToken resolved = ResolvedToken.resolve(cfg);

        assertEquals("token=provided", resolved.readyTokenField());
    }

    @Test
    @DisplayName("a bare manual launch still prints the token — it has no other channel")
    void readyFieldPrintsAGeneratedTokenWithNoFile() {
        ResolvedToken resolved = ResolvedToken.resolve(TransportConfig.fromArgs(new String[0]));

        assertEquals("token=" + resolved.token(), resolved.readyTokenField(),
            "with neither a flag nor a file, stdout is the only way to learn the token");
    }
}
