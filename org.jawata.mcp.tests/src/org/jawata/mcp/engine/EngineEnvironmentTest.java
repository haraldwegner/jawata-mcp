package org.jawata.mcp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jawata.mcp.coverage.CoverageStore;
import org.jawata.mcp.java.RunnerClasspath;
import org.jawata.mcp.knowledge.SeatRules;
import org.jawata.mcp.knowledge.StoreBackups;
import org.jawata.mcp.knowledge.StoryWriter;
import org.jawata.mcp.knowledge.TranscriptStore;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
import org.jawata.spi.host.EngineDirectories;
import org.jawata.spi.host.EngineSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28k, E17: the engine's readers take their settings and folders from the host, not from the process. Every test makes the
 * process say something DIFFERENT from the host, so a reader that still asks the process fails it.
 */
class EngineEnvironmentTest {

    private static EngineSettings settings(Map<String, String> values) {
        return key -> Optional.ofNullable(values.get(key));
    }

    private static final EngineDirectories NO_FOLDERS = new DefaultEngineDirectories(key -> Optional.empty());

    @AfterEach
    void backToTheEngineThatRunsAlone() {
        EngineEnvironment.hostedBy(null, null);
        System.clearProperty("jawata.backups.depth");
        System.clearProperty("jawata.backups.scheduled.depth");
        System.clearProperty("jawata.rules.limit");
        System.clearProperty("jawata.stories.dir");
        System.clearProperty("jawata.transcript.enabled");
    }

    @Test
    @DisplayName("the backup depths follow the host, whatever the process was started with")
    void backupDepthsFollowTheHost() {
        System.setProperty("jawata.backups.depth", "7");
        System.setProperty("jawata.backups.scheduled.depth", "9");
        EngineEnvironment.hostedBy(settings(Map.of("jawata.backups.depth", "3", "jawata.backups.scheduled.depth", "5")), null);

        assertEquals(3, StoreBackups.depth());
        assertEquals(5, StoreBackups.scheduledDepth());
    }

    @Test
    @DisplayName("CONTROL: with no host named, the process's own properties answer as they always did")
    void theEngineThatRunsAloneReadsTheProcess() {
        System.setProperty("jawata.backups.depth", "4");

        assertEquals(4, StoreBackups.depth());
    }

    @Test
    @DisplayName("the rule limit, the story folder and the transcript switch follow the host")
    void theKnowledgeReadersFollowTheHost() {
        System.setProperty("jawata.rules.limit", "11");
        System.setProperty("jawata.stories.dir", "/from/the/process");
        System.setProperty("jawata.transcript.enabled", "true");
        EngineEnvironment.hostedBy(settings(Map.of("jawata.rules.limit", "2", "jawata.stories.dir", "/from/the/host",
            "jawata.transcript.enabled", "off")), null);

        assertEquals(2, SeatRules.limit());
        assertEquals(Optional.of(Path.of("/from/the/host")), StoryWriter.directory());
        assertFalse(TranscriptStore.enabled());
    }

    @Test
    @DisplayName("the runtime and coverage stores open where the host says")
    void theStoresOpenWhereTheHostSays() {
        EngineEnvironment.hostedBy(null, new EngineDirectories() {
            @Override
            public Path runtimeDir() {
                return Path.of("/host/runtime");
            }

            @Override
            public Path coverageDir() {
                return Path.of("/host/coverage");
            }

            @Override
            public Path toolsDir() {
                return Path.of("/host/tools");
            }

            @Override
            public Optional<Path> dataDir() {
                return NO_FOLDERS.dataDir();
            }

            @Override
            public Optional<Path> workspaceRoot() {
                return NO_FOLDERS.workspaceRoot();
            }

            @Override
            public Path fieldDir() {
                return Path.of("/host/field");
            }

            @Override
            public Path sharedStoreDir() {
                return NO_FOLDERS.sharedStoreDir();
            }

            @Override
            public java.util.List<Path> memoryRoots() {
                return java.util.List.of();
            }
        });

        assertEquals(Path.of("/host/runtime"), new RuntimeArtifactStore(EngineEnvironment.directories().runtimeDir()).root());
        assertEquals(Path.of("/host/coverage"), new CoverageStore(EngineEnvironment.directories().coverageDir()).root());
    }

    @Test
    @DisplayName("the test runner's jars are looked for where the host says")
    void theToolJarsAreLookedForWhereTheHostSays() throws IOException {
        Path tools = Files.createTempDirectory("engine-env-tools");
        Path jar = Files.createFile(tools.resolve("sample-tool-1.0.jar"));
        try {
            EngineEnvironment.hostedBy(null, new EngineDirectories() {
                @Override
                public Path runtimeDir() {
                    return NO_FOLDERS.runtimeDir();
                }

                @Override
                public Path coverageDir() {
                    return NO_FOLDERS.coverageDir();
                }

                @Override
                public Path toolsDir() {
                    return tools;
                }

                @Override
                public Optional<Path> dataDir() {
                    return NO_FOLDERS.dataDir();
                }

                @Override
                public Optional<Path> workspaceRoot() {
                    return NO_FOLDERS.workspaceRoot();
                }

                @Override
                public Path fieldDir() {
                    return NO_FOLDERS.fieldDir();
                }

                @Override
                public Path sharedStoreDir() {
                    return NO_FOLDERS.sharedStoreDir();
                }

                @Override
                public java.util.List<Path> memoryRoots() {
                    return NO_FOLDERS.memoryRoots();
                }
            });

            assertEquals(jar, RunnerClasspath.toolJar("sample-tool-"));
        } finally {
            Files.deleteIfExists(jar);
            Files.deleteIfExists(tools);
        }
    }

    @Test
    @DisplayName("every key a reader asks the host for is in the registry the host's settings screen is built from")
    void everyKeyAskedIsRegistered() {
        Set<String> asked = new LinkedHashSet<>();
        EngineEnvironment.hostedBy(key -> {
            asked.add(key);
            return Optional.empty();
        }, null);

        StoreBackups.depth();
        StoreBackups.scheduledDepth();
        SeatRules.limit();
        StoryWriter.directory();
        TranscriptStore.enabled();
        new RuntimeArtifactStore(EngineEnvironment.directories().runtimeDir());
        new CoverageStore(EngineEnvironment.directories().coverageDir());

        Set<String> registered = new LinkedHashSet<>();
        SettingKeys.all().forEach(key -> registered.add(key.name()));
        assertFalse(asked.isEmpty(), "the readers asked the host for something");
        assertTrue(registered.containsAll(asked), "asked but not registered: " + asked.stream().filter(k -> !registered.contains(k)).toList());
    }
}
