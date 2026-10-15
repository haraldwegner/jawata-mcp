package org.jawata.mcp.tools.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.tools.ToolProvider;

import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * REL44-9 — the CPU burst the wall-time profiler test samples lasts a fixed TIME, whatever the
 * machine's speed.
 *
 * <p>HotspotTest's wall case failed once on a release CI runner: its continuously burning method
 * got zero samples in a five-second recording. A profiler samples in proportion to CPU time, and
 * {@code WallTimeTarget#burnCpu} counted 200,000 iterations, so the time it burned — and with it
 * the sample count — fell as the machine got faster. The fix burns for 5 ms by the clock.</p>
 *
 * <p>One green CI run could not show that: a fixed intermittent and an unlucky pass look alike.
 * This measures the property the fix established, on this machine, after the JIT has warmed the
 * loop — which is the condition the CI failure needed. The old loop finishes a burst in a small
 * fraction of 5 ms here, so this is red against it.</p>
 */
class ACpuBurstLastsByTheClockTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("REL44-9: the profiled target's CPU burst lasts a fixed time, whatever the machine's speed")
    void theBurstLastsByTheClock() throws Exception {
        Path source = helper.getFixturePath("debug-target")
            .resolve("src/main/java/com/example/debug/WallTimeTarget.java");
        Path classes = Files.createDirectories(helper.getTempDirectory().resolve("burst"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-d", classes.toString(), source.toString()),
            "the fixture compiles on its own");

        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, null)) {
            Method burn = loader.loadClass("com.example.debug.WallTimeTarget")
                .getDeclaredMethod("burnCpu");
            burn.setAccessible(true);
            for (int i = 0; i < 50; i++) {
                burn.invoke(null);
            }
            long shortest = Long.MAX_VALUE;
            for (int i = 0; i < 10; i++) {
                long start = System.nanoTime();
                burn.invoke(null);
                shortest = Math.min(shortest, System.nanoTime() - start);
            }
            long shortestMicros = shortest / 1_000;
            assertTrue(shortest >= 5_000_000L, () -> "a warmed burst lasted only " + shortestMicros
                + " µs; it must last 5 ms by the clock, or its sample count falls with the"
                + " machine's speed and a fast runner records none");
        }
    }
}
