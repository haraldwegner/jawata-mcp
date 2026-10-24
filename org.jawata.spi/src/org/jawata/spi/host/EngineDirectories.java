package org.jawata.spi.host;

import java.nio.file.Path;

/**
 * Where the engine keeps and finds its folders (Sprint 28k, E17; contract §4.2). The host decides; the engine asks.
 *
 * <p>Contract §4.2 also lists {@code dataDir()}, {@code fieldDir()} and {@code memoryRoots()}. They join at E18, when the engine takes
 * the store, the field recording and the memory roots from the host instead of the application deriving them from the launcher's
 * workspace layout: nothing in the engine asks the host for them until then.</p>
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
}
