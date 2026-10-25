package org.jawata.mcp.engine;

import org.jawata.spi.host.EngineDirectories;
import org.jawata.spi.host.EngineSettings;

/**
 * Where the engine's readers get the host's settings and folders (Sprint 28k, E17), one pair per JVM because one engine runs per JVM.
 *
 * <p>Until a host says otherwise this is the engine that runs alone: the process's system properties and the folders worked out from
 * them. That is what the readers did on their own before there was a host, so a process, or a test, that never names a host sees no
 * change. A host that has settings of its own (an IDE with preference pages) names itself once, before the engine starts.</p>
 */
public final class EngineEnvironment {

    private static volatile EngineSettings settings = new DefaultEngineSettings();
    private static volatile EngineDirectories directories = new DefaultEngineDirectories(settings);

    private EngineEnvironment() {
    }

    /** The settings the readers ask. */
    public static EngineSettings settings() {
        return settings;
    }

    /** The folders the readers ask. */
    public static EngineDirectories directories() {
        return directories;
    }

    /**
     * Name the host whose settings and folders the engine uses.
     *
     * @param hostSettings    the host's settings; null puts the engine back to running alone
     * @param hostDirectories the host's folders; null puts the engine back to running alone
     */
    public static void hostedBy(EngineSettings hostSettings, EngineDirectories hostDirectories) {
        EngineSettings s = hostSettings != null ? hostSettings : new DefaultEngineSettings();
        settings = s;
        directories = hostDirectories != null ? hostDirectories : new DefaultEngineDirectories(s);
    }
}
