package org.jawata.mcp.engine;

import java.util.Optional;

import org.jawata.spi.host.EndpointPolicy;
import org.jawata.spi.host.EngineDirectories;
import org.jawata.spi.host.EngineHost;
import org.jawata.spi.host.EngineLifecycle;
import org.jawata.spi.host.EngineSettings;
import org.jawata.spi.host.HostWorkspace;
import org.jawata.spi.host.LanguageBinding;

/**
 * The host of an engine that runs alone (Sprint 28k, E18; contract §5): its system properties, the folders worked out from them, the
 * workspace its Java model holds, the status the process ends with, the address it listens on, and the Java binding it supplies.
 */
public final class ResidentHost implements EngineHost {

    private final HostWorkspace workspace;
    private final EngineLifecycle lifecycle;
    private final EndpointPolicy endpoint;
    private final EngineSettings settings;
    private final EngineDirectories directories;
    private final LanguageBinding javaBinding;

    /**
     * @param workspace   the projects the Java model holds, which the engine may change
     * @param lifecycle   what a listener failure means for the process
     * @param endpoint    where to listen
     * @param settings    the system properties
     * @param directories the folders worked out from them
     * @param javaBinding what the host supplies to the Java provider
     */
    public ResidentHost(HostWorkspace workspace, EngineLifecycle lifecycle, EndpointPolicy endpoint, EngineSettings settings,
                        EngineDirectories directories, LanguageBinding javaBinding) {
        this.workspace = workspace;
        this.lifecycle = lifecycle;
        this.endpoint = endpoint;
        this.settings = settings;
        this.directories = directories;
        this.javaBinding = javaBinding;
    }

    @Override
    public HostWorkspace workspace() {
        return workspace;
    }

    @Override
    public EngineLifecycle lifecycle() {
        return lifecycle;
    }

    @Override
    public EndpointPolicy endpoint() {
        return endpoint;
    }

    @Override
    public EngineSettings settings() {
        return settings;
    }

    @Override
    public EngineDirectories directories() {
        return directories;
    }

    @Override
    public <B extends LanguageBinding> Optional<B> binding(Class<B> kind) {
        return kind.isInstance(javaBinding) ? Optional.of(kind.cast(javaBinding)) : Optional.empty();
    }
}
