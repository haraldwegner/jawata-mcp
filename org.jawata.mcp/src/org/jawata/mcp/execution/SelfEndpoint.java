package org.jawata.mcp.execution;

import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The hiring resident's own MCP endpoint — what a hired brain is given as its ONE tool server.
 *
 * <p><b>Why a hired brain gets only this.</b> Started in print mode with nothing else said, a
 * brain loads its user's whole setup: every MCP server the user configured, the user's settings
 * and hooks, the user's instruction files. Measured in Sprint 28g (M12): a one-word probe through
 * Claude Code cost $2.26 with zero tool steps, almost all of it that context. And the seat's tool
 * allowlist named jawata tools by their bare names ({@code inspect}), which the CLI does not
 * recognise, so it bounded nothing. Given exactly this server under a fixed name, the brain's
 * tools have the predictable names {@code mcp__jawata__<tool>}, the allowlist can say them, and
 * the brain talks to the resident that hired it rather than to whichever servers the user has.</p>
 *
 * @param url   the {@code /mcp} URL this resident listens on
 * @param token the bearer token this resident requires
 */
public record SelfEndpoint(String url, String token) {

    /** The server name a hired brain sees; its tools are {@code mcp__jawata__<tool>}. */
    public static final String SERVER_NAME = "jawata";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public SelfEndpoint {
        Objects.requireNonNull(url, "an endpoint with no URL reaches nothing");
        Objects.requireNonNull(token, "this resident refuses a request with no bearer token");
    }

    /**
     * Claude Code's {@code --mcp-config} JSON naming this one server — the same entry shape
     * jawata-studio's deploy writes for a client: {@code type}, {@code url} and an
     * {@code Authorization: Bearer} header.
     */
    public String claudeMcpConfig() {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode server = root.putObject("mcpServers").putObject(SERVER_NAME);
        server.put("type", "http");
        server.put("url", url);
        server.putObject("headers").put("Authorization", "Bearer " + token);
        return root.toString();
    }

    /** A jawata tool's name as a brain given this server sees it. */
    public static String claudeToolName(String jawataTool) {
        return "mcp__" + SERVER_NAME + "__" + jawataTool;
    }
}
