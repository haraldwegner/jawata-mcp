package org.jawata.mcp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jawata.spi.host.EngineSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28k, E17: the order the standalone engine works its folders out in, which the runtime store and the coverage store each had
 * a copy of: the folder that was named, else one under the workspace the launcher published, else one in the temporary folder.
 */
class ResidentDirectoriesTest {

    private static ResidentDirectories over(Map<String, String> values) {
        EngineSettings settings = key -> Optional.ofNullable(values.get(key));
        return new ResidentDirectories(settings);
    }

    @Test
    @DisplayName("a named folder wins over the workspace, which wins over the temporary folder")
    void theOrderOfTheFallbacks() {
        assertEquals(Path.of("/named/runtime"), over(Map.of("jawata.runtime.dir", "/named/runtime",
            "jawata.workspace.root", "/ws")).runtimeDir());
        assertEquals(Path.of("/ws", ".jawata", "runtime"), over(Map.of("jawata.workspace.root", "/ws")).runtimeDir());
        assertEquals(Path.of(System.getProperty("java.io.tmpdir"), "jawata-runtime"), over(Map.of()).runtimeDir());
    }

    @Test
    @DisplayName("the coverage folder follows the same order under its own names")
    void coverageFollowsTheSameOrder() {
        assertEquals(Path.of("/named/coverage"), over(Map.of("jawata.coverage.dir", "/named/coverage")).coverageDir());
        assertEquals(Path.of("/ws", ".jawata", "coverage"), over(Map.of("jawata.workspace.root", "/ws")).coverageDir());
        assertEquals(Path.of(System.getProperty("java.io.tmpdir"), "jawata-coverage"), over(Map.of()).coverageDir());
    }

    @Test
    @DisplayName("a blank name is no name")
    void aBlankNameIsNoName() {
        assertEquals(Path.of("/ws", ".jawata", "coverage"), over(Map.of("jawata.coverage.dir", "  ",
            "jawata.workspace.root", "/ws")).coverageDir());
    }

    @Test
    @DisplayName("the tools folder is the named one, else tools/ in the distribution folder, else a refusal that names both settings")
    void theToolsFolder() {
        assertEquals(Path.of("/named/tools"), over(Map.of("jawata.tools.dir", "/named/tools",
            "jawata.dist.root", "/dist")).toolsDir());
        assertEquals(Path.of("/dist", "tools"), over(Map.of("jawata.dist.root", "/dist")).toolsDir());

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> over(Map.of()).toolsDir());
        assertTrue(refused.getMessage().contains("jawata.tools.dir") && refused.getMessage().contains("jawata.dist.root"),
            "it names what the host was missing: " + refused.getMessage());
    }

    @Test
    @DisplayName("the registry lists each setting once, with a sentence saying what it does")
    void theRegistryIsWellFormed() {
        Set<String> names = new HashSet<>();
        for (SettingKey key : SettingKeys.all()) {
            assertTrue(names.add(key.name()), "listed twice: " + key.name());
            assertTrue(key.name().startsWith("jawata."), key.name());
            assertTrue(!key.fallback().isBlank() && key.meaning().length() > 20, "says nothing: " + key);
        }
    }
}
