package org.jawata.spi.host;

import java.net.URI;

/**
 * Where a second process reaches this engine's MCP face (Sprint 28k, E16; contract §4.2): the address, and the key that must
 * accompany every request. Handed to the host when the listener is up, so the host can announce it its own way: the standalone
 * engine prints the start line, an IDE writes a note for the tools that look for engines.
 *
 * @param url the {@code /mcp} URL a process on this machine reaches the listener at
 * @param key the bearer token every request must carry
 */
public record McpEndpoint(URI url, String key) {
}
