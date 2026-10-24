package org.jawata.mcp.engine;

import java.util.Optional;

import org.jawata.spi.host.EngineSettings;

/**
 * The settings of the engine that runs alone (Sprint 28k, E17; contract §5): the system properties the process was started with,
 * read at the moment they are asked for, so a property changed while the process runs is followed.
 */
public final class ResidentSettings implements EngineSettings {

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(System.getProperty(key));
    }
}
