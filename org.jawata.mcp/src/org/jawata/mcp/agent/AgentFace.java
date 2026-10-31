package org.jawata.mcp.agent;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jawata.mcp.engine.EngineApi;
import org.jawata.mcp.engine.RoutedFace;
import org.jawata.mcp.protocol.JsonRpcMessage;

import com.sun.net.httpserver.HttpHandler;

/**
 * jawata as an ACP agent (Sprint 28h, D1, D3): a second face of the engine beside MCP, plugged in through the engine's face slot and
 * registered as an OSGi service so the engine's whiteboard attaches it after the engine starts. It uses only what the face contract
 * allows (contract section 8) and touches no host, no provider and no Java model.
 *
 * <p>Each host connection gets its own {@link AcpServer}; the face holds the engine they all stand on.</p>
 */
public final class AgentFace implements RoutedFace {

    static final String NAME = "agent";

    /** What a prompt is answered with until a brain is attached to the agent (plan S7a); it says so rather than staying silent. */
    static final String NO_BRAIN = "jawata's agent has no brain attached yet.";

    private final Supplier<List<AdvertisedCommand>> commands;
    private final Supplier<AgentBrain> brains;
    private volatile EngineApi engine;

    /** The face as the framework builds it: it advertises no command until the seats are served (plan S14). */
    public AgentFace() {
        this(List::of);
    }

    /** A face that advertises what {@code commands} supplies at the moment a session opens. */
    public AgentFace(Supplier<List<AdvertisedCommand>> commands) {
        this(commands, null);
    }

    /**
     * The brain a connection that asked for none is answered by (S7a): the principal seat in the brain the host setting names, once the
     * engine is attached, and the no-brain sentence before that or when anything about the brain cannot be set up.
     */
    private AgentBrain principal() {
        EngineApi attached = engine;
        if (attached == null) {
            return (prompt, chunks) -> chunks.accept(NO_BRAIN);
        }
        try {
            return PrincipalBrain.over(attached, java.nio.file.Path.of(System.getProperty("user.dir")));
        } catch (RuntimeException e) {
            return (prompt, chunks) -> chunks.accept(NO_BRAIN);
        }
    }

    /** A face whose every connection is answered by the brain {@code brains} supplies when the connection opens. */
    public AgentFace(Supplier<List<AdvertisedCommand>> commands, Supplier<AgentBrain> brains) {
        this.commands = commands;
        this.brains = brains;
    }

    @Override
    public String path() {
        return AcpRoute.PATH;
    }

    @Override
    public HttpHandler handler() {
        return new AcpRoute(this, brains != null ? brains : this::principal);
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
