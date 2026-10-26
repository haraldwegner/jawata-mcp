package org.jawata.spi.host;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Where the engine keeps and finds its folders (Sprint 28k, E17; contract §4.2). The host decides; the engine asks.
 *
 * <p>Since E18 the engine takes the store's folder, the field recording's folder and the memory roots from the host instead of the
 * application deriving them from the launcher's workspace layout.</p>
 */
public interface EngineDirectories {

    /** Where debug and profile artifacts (heap dumps, recordings) are kept. */
    Path runtimeDir();

    /** Where coverage evidence is kept. */
    Path coverageDir();

    /**
     * Where the jars the forked test runner needs (the JUnit launcher, the runner, the coverage agent) are found.
     *
     * @throws IllegalStateException when the host cannot say, which names what the host was missing
     */
    Path toolsDir();

    /** Where the knowledge store shared by every resident of the user is kept; the host's own answer, whatever its settings say. */
    Path sharedStoreDir();

    /** The folder the host gave the engine to work in (for the standalone engine, the OSGi {@code -data} area), when it gave one. */
    Optional<Path> dataDir();

    /** The folder the workspace's own files live in, which holds {@code workspace.json}, when the host has one. */
    Optional<Path> workspaceRoot();

    /**
     * What the host says about its workspace before anything loads: its name and the project folders it was started with (Stage 8; the
     * engine stamps the name and the first folder on the knowledge it records and introduces itself to the other residents by the name).
     * Empty when the host declares none, which is the answer of a host whose workspace is whatever is open at the moment.
     */
    default Optional<DeclaredWorkspace> declared() {
        return Optional.empty();
    }

    /** Where the field recording's pile and state files live. */
    Path fieldDir();

    /** The places the memory the person keeps for the agents is read from; empty when none exists. */
    List<Path> memoryRoots();
}
