package org.jawata.mcp.engine;

import java.util.Map;
import java.util.Optional;

import org.jawata.spi.host.EngineSettings;

/**
 * The settings of the engine that runs alone (Sprint 28k, E17; contract §5): the system properties the process was started with,
 * read at the moment they are asked for, so a property changed while the process runs is followed.
 */
public final class DefaultEngineSettings implements EngineSettings {

    /**
     * The settings that may also come from an environment variable and are not in the registry ({@link SettingKeys}), which applies when
     * no system property says otherwise. The registered keys name their own variable ({@link SettingKey#environment()}).
     */
    private static final Map<String, String> ENVIRONMENT = Map.ofEntries(
        Map.entry("jawata.agent.brain", "JAWATA_AGENT_BRAIN"),
        Map.entry("jawata.agent.skillsDir", "JAWATA_AGENT_SKILLS_DIR"),
        Map.entry("jawata.agent.recording", "JAWATA_AGENT_RECORDING"),
        Map.entry("jawata.agent.hireBrains", "JAWATA_AGENT_HIRE_BRAINS"),
        // Sprint 28h S12: the folder of seat files whose schedules the engine fires (ScheduledHires). The studio does NOT write it yet: that
        // is owed to S15 (the plan's Stage 15 carries it), and until then only a person or a test that sets it makes seats run on a schedule.
        Map.entry("jawata.agent.seatsDir", "JAWATA_AGENT_SEATS_DIR"));

    /** What an environment variable holds: the process's own, or what a test says. */
    private final java.util.function.UnaryOperator<String> environment;

    /** Settings that read the process's own environment. */
    public DefaultEngineSettings() {
        this(System::getenv);
    }

    /** Settings that read the environment through {@code environment}, which answers null for a variable that is not set. */
    DefaultEngineSettings(java.util.function.UnaryOperator<String> environment) {
        this.environment = environment;
    }

    @Override
    public Optional<String> get(String key) {
        String property = System.getProperty(key);
        if (property != null) {
            return Optional.of(property);
        }
        String variable = SettingKeys.environmentOf(key).orElse(ENVIRONMENT.get(key));
        return variable == null ? Optional.empty() : Optional.ofNullable(environment.apply(variable));
    }
}
