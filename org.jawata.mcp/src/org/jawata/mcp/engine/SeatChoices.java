package org.jawata.mcp.engine;

import java.nio.file.Path;

import org.jawata.mcp.execution.SeatBrains;

/**
 * The one stored choice of brain and model per seat, opened by the host that starts the engine.
 * The store stays inside this bundle. A host reaches it through this package, which the bundle exports.
 */
public final class SeatChoices {

    private SeatChoices() {
    }

    /**
     * The file the running engine keeps the choice in.
     *
     * @param dataHome the data folder, used when the property is not set
     * @return the file
     */
    public static Path storeFile(Path dataHome) {
        return SeatBrains.fileIn(dataHome);
    }

    /**
     * Opens {@code file} and makes it the store this process hires from.
     *
     * @param file the store file
     */
    public static void open(Path file) {
        SeatBrains.install(file);
    }
}
