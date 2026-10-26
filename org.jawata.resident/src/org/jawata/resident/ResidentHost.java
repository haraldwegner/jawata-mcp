package org.jawata.resident;

import java.util.List;
import java.util.Optional;

import org.jawata.spi.host.EndpointPolicy;
import org.jawata.spi.host.EngineDirectories;
import org.jawata.spi.host.EngineHost;
import org.jawata.spi.host.EngineLifecycle;
import org.jawata.spi.host.EngineSettings;
import org.jawata.spi.host.HostCapabilities;
import org.jawata.spi.host.HostWorkspace;
import org.jawata.spi.host.Instruments;
import org.jawata.spi.host.LanguageBinding;
import org.jawata.spi.host.PersonChannel;
import org.jawata.spi.host.Reconciler;
import org.jawata.spi.host.WorkspaceWrites;

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
    private final Reconciler reconciler;
    private final WorkspaceWrites writes;
    private final PersonChannel person;
    private final HostCapabilities capabilities;

    /**
     * @param workspace   the projects the Java model holds, which the engine may change
     * @param lifecycle   what a listener failure means for the process
     * @param endpoint    where to listen
     * @param settings    the system properties
     * @param directories the folders worked out from them
     * @param javaBinding what the host supplies to the Java provider
     * @param reconciler  how the workspace's files are brought up to date with the disk and built
     * @param writes      how a change's text edits land in the workspace
     * @param person      how the engine tells the person who runs it something
     */
    public ResidentHost(HostWorkspace workspace, EngineLifecycle lifecycle, EndpointPolicy endpoint, EngineSettings settings,
                        EngineDirectories directories, LanguageBinding javaBinding, Reconciler reconciler, WorkspaceWrites writes, PersonChannel person) {
        this.workspace = workspace;
        this.lifecycle = lifecycle;
        this.endpoint = endpoint;
        this.settings = settings;
        this.directories = directories;
        this.javaBinding = javaBinding;
        this.reconciler = reconciler;
        this.writes = writes;
        this.person = person;
        // This host serves the one language it was handed a binding for, and it runs nothing on the engine's behalf and checks no
        // unsaved text: it owns its workspace, and only the agent ever debugs here.
        this.capabilities = new HostCapabilities("headless", true, false, false,
            javaBinding == null ? List.of() : List.of("java"));
    }

    @Override
    public PersonChannel person() {
        return person;
    }

    @Override
    public Instruments instruments() {
        return Instruments.NONE;
    }

    @Override
    public HostCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public Reconciler reconciler() {
        return reconciler;
    }

    @Override
    public WorkspaceWrites writes() {
        return writes;
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
