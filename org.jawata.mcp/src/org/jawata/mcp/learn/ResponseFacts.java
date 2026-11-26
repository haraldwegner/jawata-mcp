package org.jawata.mcp.learn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.step.GateOutcome;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What a tool's own response reported about what it did — read in ONE place (Sprint 28g S16).
 *
 * <p>Two shipped classes carried a byte-identical private copy of both readers below:
 * {@code EventTap} and {@code ToolExperienceRecorder}. Measured 2026-10-08, the duplication was
 * exact, down to the key order and the fallbacks. The architect seat's standing rule refuses a
 * second implementation of a job the system already does, and a hire's gate needed a THIRD — which
 * is the moment a shared reader pays for itself rather than one more copy.</p>
 *
 * <h2>Why {@link #errorCount} answers ABSENT where both copies answered ZERO</h2>
 *
 * <p>The copies returned {@code 0} when a response carried no count in any shape they knew, and for
 * their own job that is right: they are telemetry, and a tool that reported no errors is the common
 * case. <b>For a GATE it is the defect {@link GateOutcome}'s fourth component exists to prevent</b>
 * — an unrecognised response shape read as zero errors is a PASS, and a step nothing checked would
 * advance a plan. So the shared reader reports what it knows and the CALLER decides what silence
 * means: the two telemetry callers map absent to zero, preserving their behaviour exactly, and a
 * gate maps it to <i>did not answer</i>.</p>
 *
 * <p>The two keys are the measured ones, in the copies' own order: {@code errorCount}, which the
 * compile, diagnostics, refresh, syntax and test-run tools publish, then a top-level {@code failed}.
 * A response carrying neither is not a response this reader can speak for. (28h z51: the test runner
 * keeps its {@code failed} inside {@code summary}, so it states {@code errorCount} for a run it can
 * judge.)</p>
 */
public final class ResponseFacts {

    private ResponseFacts() {
    }

    /**
     * How many errors the tool said it found, or ABSENT when it reported none in a shape known
     * here — which is NOT the same as reporting zero. See the type javadoc.
     */
    public static OptionalLong errorCount(ToolResponse response) {
        if (response != null && response.getData() instanceof Map<?, ?> map) {
            Object errors = map.get("errorCount");
            if (errors instanceof Number n) {
                return OptionalLong.of(n.longValue());
            }
            Object failed = map.get("failed");
            if (failed instanceof Number n) {
                return OptionalLong.of(n.longValue());
            }
        }
        return OptionalLong.empty();
    }

    /**
     * The files a call changed on disk, as its answer names them (28h z120): the two lists a modifying tool publishes, and the document the
     * {@code files} tool wrote or edited. A staged change and a dry run name files they did not change, so they give none.
     */
    public static List<String> filesWritten(String tool, JsonNode arguments, ToolResponse response) {
        if (response == null || !response.isSuccess() || !(response.getData() instanceof Map<?, ?> map)
                || Boolean.FALSE.equals(map.get("applied")) || Boolean.TRUE.equals(map.get("dryRun"))) {
            return List.of();
        }
        List<String> written = new ArrayList<>();
        for (String key : List.of("filesModified", "modifiedFiles")) {
            if (map.get(key) instanceof List<?> files) {
                files.forEach(file -> written.add(String.valueOf(file)));
            }
        }
        String action = arguments == null ? "" : arguments.path("action").asText("");
        if ("files".equals(tool) && ("write".equals(action) || "edit".equals(action)) && map.get("path") instanceof String path) {
            written.add(path);
        }
        return written;
    }

    /**
     * How many files the tool said it modified.
     *
     * <p>Zero for a response that does not carry the list at all, which is the behaviour both
     * original copies had and is kept deliberately: every tool that MODIFIES files publishes the
     * list, so its absence means this was not one of them rather than that one touched nothing
     * silently.</p>
     */
    public static int filesModified(ToolResponse response) {
        if (response != null && response.getData() instanceof Map<?, ?> map
                && map.get("filesModified") instanceof List<?> files) {
            return files.size();
        }
        return 0;
    }
}
