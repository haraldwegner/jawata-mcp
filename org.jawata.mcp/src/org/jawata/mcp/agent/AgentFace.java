package org.jawata.mcp.agent;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jawata.mcp.engine.EngineApi;
import org.jawata.mcp.engine.EngineFace;
import org.jawata.mcp.protocol.JsonRpcMessage;

/**
 * jawata as an ACP agent (Sprint 28h, D1, D3): a second face of the engine beside MCP, plugged in through the engine's face slot and
 * registered as an OSGi service so the engine's whiteboard attaches it after the engine starts. It uses only what the face contract
 * allows (contract section 8) and touches no host, no provider and no Java model.
 *
 * <p>Each host connection gets its own {@link AcpServer}; the face holds the engine they all stand on.</p>
 */
public final class AgentFace implements EngineFace {

    static final String NAME = "agent";

    private final Supplier<List<AdvertisedCommand>> commands;
    private volatile EngineApi engine;

    /** The face as the framework builds it: it advertises no command until the seats are served (plan S14). */
    public AgentFace() {
        this(List::of);
    }

    /** A face that advertises what {@code commands} supplies at the moment a session opens. */
    public AgentFace(Supplier<List<AdvertisedCommand>> commands) {
        this.commands = commands;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void attach(EngineApi api) {
        this.engine = api;
    }

    @Override
    public void detach() {
        this.engine = null;
    }

    /**
     * Opens one ACP connection: messages from the host go to the returned server, whose messages come back through {@code out}.
     *
     * @throws IllegalStateException when the engine has not attached the face, or has detached it
     */
    public AcpServer open(AgentBrain brain, Consumer<JsonRpcMessage> out) {
        if (engine == null) {
            throw new IllegalStateException("the agent face is not attached to an engine");
        }
        return new AcpServer(brain, commands, out);
    }
}
