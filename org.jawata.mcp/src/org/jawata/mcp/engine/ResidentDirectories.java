package org.jawata.mcp.engine;

import java.nio.file.Path;
import java.util.Optional;

import org.jawata.spi.host.EngineDirectories;
import org.jawata.spi.host.EngineSettings;

/**
 * The folders of the engine that runs alone (Sprint 28k, E17; contract §5): a folder named in the settings, else one under the
 * workspace the launcher published, else one in the temporary folder. The three stores each worked this out for themselves, with the
 * same order and different words.
 */
public final class ResidentDirectories implements EngineDirectories {

    private final EngineSettings settings;

    /** @param settings where the named folders and the workspace root are read from */
    public ResidentDirectories(EngineSettings settings) {
        this.settings = settings;
    }

    @Override
    public Path runtimeDir() {
        return folder(SettingKeys.RUNTIME_DIR, "runtime");
    }

    @Override
    public Path coverageDir() {
        return folder(SettingKeys.COVERAGE_DIR, "coverage");
    }

    @Override
    public Path toolsDir() {
        Optional<String> explicit = present(SettingKeys.TOOLS_DIR);
        if (explicit.isPresent()) {
            return Path.of(explicit.get());
        }
        // Blank is unset for the override above, and it was for the distribution folder only when absent altogether.
        Optional<String> distRoot = settings.get(SettingKeys.DIST_ROOT.name());
        if (distRoot.isEmpty()) {
            throw new IllegalStateException("Neither " + SettingKeys.TOOLS_DIR.name() + " nor " + SettingKeys.DIST_ROOT.name()
                + " is set — the distribution's tools/ folder cannot be located. "
                + "(The boot sets " + SettingKeys.DIST_ROOT.name() + "; a dev embedding must set " + SettingKeys.TOOLS_DIR.name() + ".)");
        }
        return Path.of(distRoot.get()).resolve("tools");
    }

    /** The named folder; else {@code <workspace>/.jawata/<leaf>}; else {@code jawata-<leaf>} in the temporary folder. */
    private Path folder(SettingKey key, String leaf) {
        Optional<String> explicit = present(key);
        if (explicit.isPresent()) {
            return Path.of(explicit.get());
        }
        Optional<String> workspace = present(SettingKeys.WORKSPACE_ROOT);
        if (workspace.isPresent()) {
            return Path.of(workspace.get(), ".jawata", leaf);
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "jawata-" + leaf);
    }

    private Optional<String> present(SettingKey key) {
        return settings.get(key.name()).filter(value -> !value.isBlank());
    }
}
