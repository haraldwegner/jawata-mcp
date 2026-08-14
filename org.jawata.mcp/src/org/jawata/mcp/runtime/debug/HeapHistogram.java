package org.jawata.mcp.runtime.debug;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostProcessOutcome;
import org.jawata.core.host.HostProcesses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Sprint 24 (D6) — the exact live count of a class's instances, from the JVM's own
 * heap histogram.
 *
 * <p>Why this exists: JDI's {@code instances(max)} needs a bound, and a bound that
 * bites turns a count into a floor. When the debugger's live enumeration hits its
 * cap, we ask the JVM for the real number instead of reporting the cap and calling
 * it a count.</p>
 *
 * <p>{@code jcmd GC.class_histogram} walks the heap after a full GC, so the number
 * is live objects — which is exactly the question. It is not free (it stops the
 * world briefly), which is why it is the fallback and not the default path.</p>
 */
final class HeapHistogram {

    private static final Logger log = LoggerFactory.getLogger(HeapHistogram.class);

    private HeapHistogram() {
    }

    /** The live instance count of one class, or empty when the histogram is unavailable. */
    static Optional<Long> countOf(long pid, String className) {
        if (pid <= 0) {
            return Optional.empty();
        }
        Map<String, Long> histogram = of(pid);
        return Optional.ofNullable(histogram.get(className));
    }

    /** class name → live instance count. Empty map when jcmd is unavailable or fails. */
    static Map<String, Long> of(long pid) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Path jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd");
        if (!Files.isExecutable(jcmd)) {
            log.debug("no jcmd at {} — the exact heap count is unavailable", jcmd);
            return counts;
        }
        try {
            // Through the boundary. The previous shape read the stream to its
            // end and only THEN waited 30s — and a stream ends when the child
            // exits, so a jcmd wedged against an unresponsive target held this
            // call open indefinitely. Jcmd (the sibling in runtime/profile) had
            // already been fixed for exactly this in the Sprint-24 audit; the
            // fix never reached here, which is the argument for one chokepoint
            // rather than four careful copies.
            HostProcessOutcome outcome = HostProcesses.system().run(
                HostCommand.of(jcmd.toString(), String.valueOf(pid), "GC.class_histogram")
                    .waitingAtMost(Duration.ofSeconds(30)));
            if (!(outcome instanceof HostProcessOutcome.Completed)) {
                log.debug("heap histogram for pid {}: {}", pid, outcome.describe());
                return Map.of();
            }
            outcome.output().lines().forEach(line -> parseRow(line, counts));
            return counts;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        }
    }

    /** Rows look like: {@code   1:            25            600  com.example.Widget}. */
    private static void parseRow(String line, Map<String, Long> counts) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 4 || !parts[0].endsWith(":")) {
            return;
        }
        try {
            long instances = Long.parseLong(parts[1]);
            String name = parts[3];
            counts.put(name, instances);
        } catch (NumberFormatException e) {
            // A header or a total row — not a class row. Skipping is correct.
        }
    }
}
