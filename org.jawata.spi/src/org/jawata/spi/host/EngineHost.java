package org.jawata.spi.host;

import java.util.Optional;

/**
 * Everything one host gives the engine, in one object (Sprint 28k, E18; contract §4.2): the host assembles the engine from it, and
 * the engine asks it, never a global, for what only the host knows.
 *
 * <p>As built, every port the contract names for a host the engine reads: the workspace, the folders, the settings, the process's end,
 * where to listen, the reconciler, the writes, the person's channel, the instruments, what the host is and which languages it serves,
 * and the binding of each language it supports. The host that runs programs for the engine adds that to {@link Instruments} with the
 * stage that gives the engine a caller for it.</p>
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

    /** How the engine tells the person using this host something, and shows them work in progress. */
    PersonChannel person();

    /** What this host can tell the engine about debugging that the engine cannot find out for itself. */
    Instruments instruments();

    /** What this host is and what it can do, including the languages it serves, which are the ones the engine waits for. */
    HostCapabilities capabilities();

    /**
     * What this host supplies to one language provider.
     *
     * @param kind the binding's type, such as the Java binding
     * @param <B>  that type
     * @return the binding, or empty when the host does not support the language
     */
    <B extends LanguageBinding> Optional<B> binding(Class<B> kind);
}
