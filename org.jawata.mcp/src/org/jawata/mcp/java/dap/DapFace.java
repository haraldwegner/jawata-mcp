package org.jawata.mcp.java.dap;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.core.resources.IResource;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.jawata.core.IJdtService;
import org.jawata.core.JavaBinding;
import org.jawata.core.LoadedProject;
import org.jawata.mcp.engine.EngineApi;
import org.jawata.mcp.engine.RoutedFace;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.mcp.protocol.MessageRoute;
import org.jawata.spi.host.EngineHost;
import org.jawata.spi.host.Instruments;
import org.jawata.spi.host.PersonDebugTurn;

import com.sun.net.httpserver.HttpHandler;

/**
 * The person's debugger as a face of the engine (Sprint 28i, Stage 25, step A2; ARCHITECTURE-28i-s25.md): it answers on {@code /dap} of the
 * engine's own listener under the listener's key, the way the language server answers on {@code /lsp}, so an editor's debug adapter client is
 * a client of the one resident that also serves the agent's tools. Each editor connection gets its own {@link DapServer}; they share the
 * engine's one {@link PersonDebugTurn}, so while any of them has a launch the agent's own debugging stays out.
 *
 * <p>It is a declarative service of the engine's face slot, like the language server's face. It answers 503 on a host whose debug turn is
 * not the engine's own: in Eclipse the editor's own debugger already runs the person's programs and the engine only attaches to them.</p>
 */
public final class DapFace implements RoutedFace {

    static final String NAME = "dap";
    static final String PATH = "/dap";

    private static final long KEEPALIVE_SECONDS = 15;

    private final Supplier<Instruments> instruments;
    private final Supplier<IJdtService> model;
    private final AtomicInteger connections = new AtomicInteger();
    private volatile EngineHost host;
    private volatile EngineApi engine;

    /** The face as the component runtime builds it: the instruments are whatever the host the engine was built from answers at each ask. */
    public DapFace() {
        this.instruments = () -> host.instruments();
        this.model = () -> host.binding(JavaBinding.class).map(binding -> binding.model().current()).orElse(null);
    }

    /** A face over the instruments {@code instruments} supplies, for a caller that has no host; it knows no source files. */
    DapFace(Supplier<Instruments> instruments) {
        this.instruments = instruments;
        this.model = () -> null;
    }

    /** The source file of the class the running program calls {@code className}, from the engine's Java model; empty when it is not in source of a loaded project. */
    private Optional<Path> sourceOf(String className) {
        IJdtService engine = model.get();
        if (engine == null) {
            return Optional.empty();
        }
        String typeName = className.replace('$', '.');
        for (LoadedProject project : engine.allProjects()) {
            try {
                IType type = project.javaProject().findType(typeName);
                ICompilationUnit unit = type == null ? null : type.getCompilationUnit();
                IResource resource = unit == null ? null : unit.getResource();
                if (resource != null && resource.getLocationURI() != null) {
                    return Optional.of(Path.of(resource.getLocationURI()));
                }
            } catch (JavaModelException e) {
                // this project cannot say: the next one may
            }
        }
        return Optional.empty();
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

    /** The debugger's door on the listener: a connection per editor, each with its own server over the shared turn state. */
    MessageRoute route(long keepaliveSeconds) {
        return new MessageRoute(PATH, this::open, method -> false, keepaliveSeconds);
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
            throw new IllegalStateException("the debugger face is not attached to an engine");
        }
        if (!(instruments.get().debugTurn() instanceof PersonDebugTurn turn)) {
            throw new IllegalStateException("this host debugs the person's programs itself");
        }
        CallerIdentity caller = CallerIdentity.editor("dap-" + connections.incrementAndGet());
        DapServer server = new DapServer(attached.tools(), caller, out, turn, this::sourceOf);
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
