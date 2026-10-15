package org.jawata.mcp.runtime.profile;

import java.io.IOException;
import java.nio.file.Path;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * C15-3 — how many stack-bearing execution samples a recording holds, counted WITHOUT
 * {@link JfrParser}.
 *
 * <p>The call-tree tests skip when the sampler caught nothing, and they used to read that from
 * the parser's own {@code executionSamplesInRecording}. That figure is filled by the parser's
 * event-collection loop, so a defect in the loop — the event type tested the wrong way round, the
 * stack guard inverted, the sample never added — set it to zero and turned every one of those
 * tests from a failure into a skip. A gate must not be satisfiable by the defect behind it.</p>
 *
 * <p>This reads the same file with the JDK's own reader and nothing of ours, so no change to the
 * parser can move it. Its definition matches the parser's on purpose — a
 * {@code jdk.ExecutionSample} whose stack has at least one frame — so a test can gate on
 * {@code count > 0} and then require the parser to report the same number.</p>
 */
public final class ExecutionSampleCount {

    private ExecutionSampleCount() {
    }

    /** The stack-bearing {@code jdk.ExecutionSample} events in {@code jfr}. */
    public static long of(Path jfr) throws IOException {
        long count = 0;
        try (RecordingFile file = new RecordingFile(jfr)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (!"jdk.ExecutionSample".equals(event.getEventType().getName())) {
                    continue;
                }
                RecordedStackTrace stack = event.getStackTrace();
                if (stack != null && !stack.getFrames().isEmpty()) {
                    count++;
                }
            }
        }
        return count;
    }
}
