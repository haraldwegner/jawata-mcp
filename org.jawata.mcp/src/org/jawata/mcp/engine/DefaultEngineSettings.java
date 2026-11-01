package org.jawata.mcp.engine;

import java.util.Map;
import java.util.Optional;

import org.jawata.spi.host.EngineSettings;

/**
 * The settings of the engine that runs alone (Sprint 28k, E17; contract §5): the system properties the process was started with,
 * read at the moment they are asked for, so a property changed while the process runs is followed.
 */
public final class DefaultEngineSettings implements EngineSettings {

    /** The settings that may also come from an environment variable, which applies when no system property says otherwise. */
    private static final Map<String, String> ENVIRONMENT = Map.ofEntries(
        Map.entry("jawata.transcript.enabled", "JAWATA_TRANSCRIPT"),
        Map.entry("jawata.data.home", "XDG_DATA_HOME"),
        Map.entry("jawata.project.path", "JAVA_PROJECT_PATH"),
        Map.entry("jawata.timeout.seconds", "JAWATA_TIMEOUT_SECONDS"),
        Map.entry("jawata.absolute.paths", "JAWATA_ABSOLUTE_PATHS"),
        Map.entry("jawata.agent.brain", "JAWATA_AGENT_BRAIN"),
        Map.entry("jawata.agent.recording", "JAWATA_AGENT_RECORDING"),
        Map.entry("jawata.agent.reviewer", "JAWATA_AGENT_REVIEWER"),
        Map.entry("jawata.agent.reviewerRules", "JAWATA_AGENT_REVIEWER_RULES"),
        Map.entry("jawata.agent.hireBrains", "JAWATA_AGENT_HIRE_BRAINS"),
        // Sprint 28h S12: the folder of seat files whose schedules the engine fires (ScheduledHires); the studio writes it for the engine it starts.
        Map.entry("jawata.agent.seatsDir", "JAWATA_AGENT_SEATS_DIR"));

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
