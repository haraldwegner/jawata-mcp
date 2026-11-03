package org.jawata.mcp.agent;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jawata.mcp.engine.EngineApi;
import org.jawata.mcp.engine.RoutedFace;
import org.jawata.mcp.execution.Hirer;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.mcp.protocol.MessageRoute;

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

    /** The route the agent answers on; the host program's {@code --acp} posts to it and streams from {@code /acp/events}. */
    static final String PATH = "/acp";

    private static final long KEEPALIVE_SECONDS = 15;

    /** What a prompt is answered with until a brain is attached to the agent (plan S7a); it says so rather than staying silent. */
    static final String NO_BRAIN = "jawata's agent has no brain attached yet.";

    /** How many times the loop resumes a brain on one pending step before it needs the person's word (S8, D12). */
    static final int RESUMPTIONS_PER_STEP = 3;

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
        return PATH;
    }

    @Override
    public HttpHandler handler() {
        return route(brains != null ? brains : this::principal, KEEPALIVE_SECONDS);
    }

    /** The agent's door on the listener: a connection per host program, each answered by a server over the brain {@code brains} supplies. */
    MessageRoute route(Supplier<AgentBrain> brains, long keepaliveSeconds) {
        return new MessageRoute(PATH, out -> open(brains.get(), out, brains)::receive, AgentFace::runsAtOnce, keepaliveSeconds);
    }

    /**
     * What must act while a prompt is running, because queued behind it the message would arrive after the turn it was for: the person's
     * switch and cancel, the Agent Control panel's stop, brain pick, transcript and brains list (a hire runs INSIDE the principal's prompt, so a
     * stop queued behind the prompt would act only when the hire it was meant to end had ended), and the host's answer to a question the agent
     * asked, which has no method at all.
     */
    static boolean runsAtOnce(String method) {
        if (method == null) {
            return true;
        }
        return switch (method) {
            case "session/set_config_option", "session/cancel", "_jawata/hire/stop", "_jawata/hire/brain", "_jawata/hire/transcript", "_jawata/hire/figures", "_jawata/brains" -> true;
            default -> false;
        };
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
     * @param sideBrains supplies a fresh brain for each side thread the host opens (S9); {@code null} answers a side thread with {@code brain}
     * @throws IllegalStateException when the engine has not attached the face, or has detached it
     */
    public AcpServer open(AgentBrain brain, Consumer<JsonRpcMessage> out, Supplier<AgentBrain> sideBrains) {
        EngineApi attached = engine;
        if (attached == null) {
            throw new IllegalStateException("the agent face is not attached to an engine");
        }
        TurnBoundary boundary = new TurnBoundary(new ToolStepLedger(attached.tools(), brain.toolSessionKey()), new AttemptLedger(RESUMPTIONS_PER_STEP));
        String recording = attached.settings() == null ? null : attached.settings().get(AgentRecorder.SETTING).orElse(null);
        AgentRecorder recorder = new AgentRecorder(attached.tools(), AgentRecorder.Depth.of(recording));
        ReviewBeforeSend.Reviewer reviewer = BrainReviewer.over(attached.settings(), java.nio.file.Path.of(System.getProperty("user.dir"))).orElse(null);
        AcpServer server = new AcpServer(brain, commands, out, boundary, recorder, sideBrains, reviewer);
        server.offerBrains(() -> attached.settings() == null ? "" : attached.settings().get(Hirer.BRAINS_SETTING).orElse(""));
        return server;
    }
}
