package org.jawata.mcp.engine;

import java.util.Optional;

import org.jawata.spi.host.EngineSettings;
import org.jawata.spi.host.HostCapabilities;
import org.jawata.spi.host.McpEndpoint;
import org.jawata.spi.host.PersonChannel;

/**
 * Everything a face may hold of the engine (Sprint 28k, E19; contract §4.3 and §8): the one entry into the tools, the state the
 * engine reports, the way to tell the person something, the settings it was given, what the host is and which languages it serves,
 * and the address a second process reaches its MCP face at. A face that keeps only this cannot reach a tool class, a provider, the
 * store or the workspace, so it cannot call a tool by any other route than the gates.
 */
public interface EngineApi {

    /** The one way to run a tool: every call passes the same gates in the same order. */
    ToolCalls tools();

    /** Whether the engine is loading, ready or failed. */
    EngineState state();

    /** How to tell the person using the host something, and how to show them work in progress. */
    PersonChannel person();

    /** The named settings the host gave the engine, asked per call. */
    EngineSettings settings();

    /** What the host is and what it can do, including the languages it serves. */
    HostCapabilities host();

    /** Where a process on this machine reaches the engine's MCP face, or nothing when it has none (stdio, or not listening yet). */
    Optional<McpEndpoint> mcp();
}
