package org.jawata.mcp.execution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

/**
 * What the machine can still give a new process (28h Stage 27, z29): the one reading the engine takes before it starts a process of its own, a
 * hired agent or a test run, so a machine out of memory is not made to swap and die. Both the hire tool and the forked test runner ask it, so
 * there is one place that says how much is available.
 */
public final class MachineMemory {

    private MachineMemory() {
    }

    /**
     * The memory the machine can still give a new process, in MB: /proc/meminfo's MemAvailable; none where there is no such file, so off Linux
     * nothing is checked and the process is admitted. The JVM's own free-memory figure is not used as a stand-in: on macOS it counts only the
     * truly free pages and leaves out the cache the system gives back, so it would refuse a process on a machine with plenty to give.
     */
    public static OptionalLong availableMb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    return OptionalLong.of(Long.parseLong(line.replaceAll("[^0-9]", "")) / 1024);
                }
            }
        } catch (IOException | RuntimeException notLinux) {
            // no figure: the check is skipped
        }
        return OptionalLong.empty();
    }
}
