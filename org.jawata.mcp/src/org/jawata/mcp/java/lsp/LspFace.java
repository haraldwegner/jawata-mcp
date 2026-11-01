package org.jawata.mcp.java.lsp;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jawata.core.IJdtService;
import org.jawata.core.JavaBinding;
import org.jawata.mcp.engine.EngineApi;
import org.jawata.mcp.engine.RoutedFace;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.mcp.protocol.MessageRoute;
import org.jawata.spi.host.EngineHost;
import org.jawata.spi.host.HostCapabilities;

import com.sun.net.httpserver.HttpHandler;

/**
 * The Java language server as a face of the engine (Sprint 28h, S16a): a second routed face beside the agent's, answering on
 * {@code /lsp} of the engine's own listener under the listener's key, so every editor that speaks the language protocol is a client of
 * the one resident that also serves the agent's tools. Each editor connection gets its own {@link JavaLanguageServer}; the unsaved text
 * of the files they open lives in the engine's model, shared by all of them.
 *
 * <p>It is a declarative service of the engine's face slot, like the agent's face. It asks for the engine host the way the Java
 * provider does, to reach the Java model, and answers 503 on a host that does not own the workspace: in Eclipse the editor's own
 * language server already holds the buffers.</p>
 */
public final class LspFace implements RoutedFace {

    static final String NAME = "lsp";
    static final String PATH = "/lsp";

    private static final long KEEPALIVE_SECONDS = 15;

    /** How long a file's text must stay as it is before its problems are asked for. */
    private static final long DEBOUNCE_MILLIS = 300;

    /** Runs the problem checks of every editor connection: one thread, because a check holds the model for as long as it takes. */
    private static final ScheduledExecutorService DIAGNOSTICS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lsp-diagnostics");
        t.setDaemon(true);
        return t;
    });

    private final Supplier<IJdtService> model;
    private final long debounceMillis;
    private final AtomicInteger connections = new AtomicInteger();
    private volatile EngineHost host;
    private volatile EngineApi engine;

    /** The face as the component runtime builds it: the model is whatever the host's Java binding holds at the moment of each ask. */
    public LspFace() {
        this.model = () -> host.binding(JavaBinding.class).orElseThrow().model().current();
        this.debounceMillis = DEBOUNCE_MILLIS;
    }

    /** A face over the model {@code model} supplies, for a caller that has no host, asking for problems {@code debounceMillis} after a change. */
    LspFace(Supplier<IJdtService> model, long debounceMillis) {
        this.model = model;
        this.debounceMillis = debounceMillis;
    }

    /** The component runtime hands in the host the engine was built from. */
    void bindHost(EngineHost host) {
        this.host = host;
    }

    @Override
    public String path() {
        return PATH;
    }

    @Override
    public HttpHandler handler() {
        return route(KEEPALIVE_SECONDS);
    }

    /** The language server's door on the listener: a connection per editor, each with its own server over the shared model. */
    MessageRoute route(long keepaliveSeconds) {
        return new MessageRoute(PATH, this::open, method -> "$/cancelRequest".equals(method), keepaliveSeconds);
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

    private MessageRoute.Session open(Consumer<JsonRpcMessage> out) {
        EngineApi attached = engine;
        if (attached == null) {
            throw new IllegalStateException("the language server face is not attached to an engine");
        }
        HostCapabilities capabilities = attached.host();
        if (capabilities == null || !capabilities.ownsWorkspace()) {
            throw new IllegalStateException("this host keeps the editor's buffers itself");
        }
        CallerIdentity caller = CallerIdentity.editor("lsp-" + connections.incrementAndGet());
        JavaLanguageServer server = new JavaLanguageServer(model, attached.tools(), caller, out, DIAGNOSTICS, debounceMillis);
        return new MessageRoute.Session() {
            @Override
            public void receive(JsonRpcMessage message) {
                server.receive(message);
            }

            @Override
            public void closed() {
                server.closed();
            }
        };
    }
}
