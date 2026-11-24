package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, z67: what a brain that cannot attach an HTTP tool server (Antigravity: mcpCapabilities.http false) is given instead. Measured against
 * agy 1.5.15 on 2026-11-24: the stdio server is started by the brain, and its calls then reach this engine under the chat's session. The name is not
 * the engine's own, {@code jawata}, because a person's own configuration of that brain may name a server so, and the brain then answers for the
 * configured one.
 */
class StdioServerForTest {

    @Test
    void theEngineIsOfferedAsAProgramCarryingItsAddressAndKeyUnderAnotherName() throws Exception {
        Path dist = Files.createTempDirectory("jawata-stdio-dist-");
        String before = System.getProperty("jawata.dist.root");
        try {
            Files.writeString(dist.resolve("jawata.jar"), "not a jar: the entry names it, it does not start it");
            System.setProperty("jawata.dist.root", dist.toString());

            ObjectNode server = AcpBrainSession.stdioServerFor(new SelfEndpoint("http://127.0.0.1:9/mcp", "the-key", "the-session")).orElseThrow();

            assertEquals(SelfEndpoint.SERVER_NAME + "-engine", server.path("name").asText());
            assertEquals("--mcp", server.path("args").get(2).asText());
            assertTrue(server.path("args").get(1).asText().endsWith("jawata.jar"));
            String env = server.path("env").toString();
            assertTrue(env.contains("JAWATA_MCP_URL") && env.contains("the-session") && env.contains("\"JAWATA_KEY\"") && env.contains("the-key"), env);
        } finally {
            restore(before);
            Files.deleteIfExists(dist.resolve("jawata.jar"));
            Files.deleteIfExists(dist);
        }
    }

    @Test
    void noProgramIsOfferedWhenTheRunningInstallHasNoBootJar() throws Exception {
        Path dist = Files.createTempDirectory("jawata-stdio-empty-");
        String before = System.getProperty("jawata.dist.root");
        try {
            System.setProperty("jawata.dist.root", dist.toString());

            assertTrue(AcpBrainSession.stdioServerFor(new SelfEndpoint("http://127.0.0.1:9/mcp", "k")).isEmpty());
        } finally {
            restore(before);
            Files.deleteIfExists(dist);
        }
    }

    private static void restore(String before) {
        if (before == null) {
            System.clearProperty("jawata.dist.root");
        } else {
            System.setProperty("jawata.dist.root", before);
        }
    }
}
