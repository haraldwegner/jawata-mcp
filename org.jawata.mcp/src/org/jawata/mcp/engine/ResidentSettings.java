package org.jawata.mcp.engine;

import java.util.Map;
import java.util.Optional;

import org.jawata.spi.host.EngineSettings;

/**
 * The settings of the engine that runs alone (Sprint 28k, E17; contract §5): the system properties the process was started with,
 * read at the moment they are asked for, so a property changed while the process runs is followed.
 */
public final class ResidentSettings implements EngineSettings {

    /** The settings that may also come from an environment variable, which applies when no system property says otherwise. */
    private static final Map<String, String> ENVIRONMENT = Map.of(
        "jawata.transcript.enabled", "JAWATA_TRANSCRIPT",
        "xdg.data.home", "XDG_DATA_HOME",
        "jawata.project.path", "JAVA_PROJECT_PATH",
        "jawata.timeout.seconds", "JAWATA_TIMEOUT_SECONDS",
        "jawata.absolute.paths", "JAWATA_ABSOLUTE_PATHS");

    @Override
    public Optional<String> get(String key) {
        String property = System.getProperty(key);
        if (property != null) {
            return Optional.of(property);
        }
        String variable = ENVIRONMENT.get(key);
        return variable == null ? Optional.empty() : Optional.ofNullable(System.getenv(variable));
    }
}
