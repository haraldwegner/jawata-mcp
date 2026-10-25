package org.jawata.spi.host;

import java.util.Optional;

/**
 * Everything one host gives the engine, in one object (Sprint 28k, E18; contract §4.2): the host assembles the engine from it, and
 * the engine asks it, never a global, for what only the host knows.
 *
 * <p>As built, the ports the engine reads today: the workspace, the folders, the settings, the process's end, where to listen, and
 * the binding of each language the host supports. The contract's other members arrive with the stages that use them: the
 * reconciler and the writes with the Eclipse host's write side (Stage 18), the person's channel, the instruments and the
 * capabilities with Stages 19 to 21.</p>
 */
public interface EngineHost {

    /** Which folders the workspace holds and whether the engine may change them. */
    HostWorkspace workspace();

    /** What the process does when its listener fails. */
    EngineLifecycle lifecycle();

    /** Where the engine listens and who is told when it does. */
    EndpointPolicy endpoint();

    /** The settings the host offers. */
    EngineSettings settings();

    /** The folders the host puts the engine's files in. */
    EngineDirectories directories();

    /** How this host brings the workspace's files up to date with the disk and builds them, before a call and around a change. */
    Reconciler reconciler();

    /** How this host lands a change's text edits in its workspace. */
    WorkspaceWrites writes();

    /**
     * What this host supplies to one language provider.
     *
     * @param kind the binding's type, such as the Java binding
     * @param <B>  that type
     * @return the binding, or empty when the host does not support the language
     */
    <B extends LanguageBinding> Optional<B> binding(Class<B> kind);
}
