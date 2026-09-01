package org.jawata.mcp.fixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindQualityIssueTool;

/**
 * Drive a whole-family sweep the only way a caller can drive one.
 *
 * <p>{@code jawata-mcp#10}: a synchronous family sweep is REFUSED. It ran a
 * family's every detector inside one request and timed out on any realistic
 * project — measured at 10.3 s for one of eighteen kinds on a 1040-file
 * plug-in, against a 30 s client default — while the async path it should have
 * pointed at worked fine.</p>
 *
 * <p>Five test classes drove family behaviour (summary shaping, excludePaths,
 * baselines, sweep hardening) through the synchronous path. Left alone they
 * would have gone red; ported one by one they would have grown five copies of
 * the same poll loop. More to the point, that behaviour now lives ONLY on the
 * async path, so testing it anywhere else would prove it where nobody can
 * reach it.</p>
 *
 * <p>The status call carries the ORIGINAL arguments, deliberately: shaping
 * (summary / limit / offset) is applied by the RETRIEVING call, not frozen at
 * start time. Passing a bare {@code {action, sweepId}} here would have made
 * every shaping assertion in those five classes pass vacuously against an
 * unshaped result.</p>
 */
public final class Sweeps {

    private Sweeps() {
    }

    /**
     * How long a fixture sweep may take before the test fails rather than hangs.
     *
     * <p><b>This is a HANG BACKSTOP, not a latency budget</b>, and confusing the
     * two is what made it flake. At 120 s it sat close enough to a legitimate
     * slow run that ordinary variation crossed it, so a healthy sweep on a busy
     * or slower machine failed with an assertion that reads exactly like a
     * regression — and a different test each time, which is the signature.</p>
     *
     * <p>Measured 2026-09-01, and the measurements are why this is now 600 s.
     * {@code SweepHardeningTest} runs its four tests in 70 s wall on an idle
     * 20-core box, so one sweep is tens of seconds; four suite shards in
     * parallel pushed one past 120 s. Then CI — <b>serial</b>, one JVM, 1976 s
     * total — blew the same ceiling on a DIFFERENT test. That second run is the
     * important one: it refutes "marginal under parallel load" (mcp#66's title)
     * and shows the ceiling was simply too close to the work on any slower
     * machine. A two-core runner against twenty needs no concurrency to be five
     * times slower.</p>
     *
     * <p>Raising it costs nothing when things are healthy: a passing sweep never
     * waits for the deadline, it returns when the sweep finishes. It costs only
     * on a genuine hang, where the alternative is a suite that never ends — and
     * ten minutes to discover a hang is a fair price for never again reading a
     * slow machine as a broken one.</p>
     *
     * <p>{@code jawata.test.sweep.deadline.ms} overrides it, for a machine
     * slower than any measured here.</p>
     */
    private static final long DEADLINE_MILLIS =
        Long.getLong("jawata.test.sweep.deadline.ms", 600_000L);

    /**
     * Execute {@code args}, routing a whole-family request through
     * start → poll → status so the caller sees one finished response.
     *
     * <p>A request naming a single {@code kind}, or one that already carries an
     * {@code action}, is passed straight through — those are still synchronous
     * and must stay that way.</p>
     */
    public static ToolResponse run(FindQualityIssueTool tool, ObjectNode args) {
        boolean wholeFamily = has(args, "family") && !has(args, "kind") && !has(args, "action");
        if (!wholeFamily) {
            return tool.execute(args);
        }

        ObjectNode start = args.deepCopy();
        start.put("action", "start");
        ToolResponse started = tool.execute(start);
        if (!started.isSuccess()) {
            return started; // an unknown family still refuses at start; hand it back
        }
        Object id = ((java.util.Map<?, ?>) started.getData()).get("sweepId");
        if (!(id instanceof String sweepId) || sweepId.isBlank()) {
            throw new AssertionError("action=start returned no sweepId: " + started.getData());
        }

        long deadline = System.currentTimeMillis() + DEADLINE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            ObjectNode status = args.deepCopy();
            status.put("action", "status");
            status.put("sweepId", sweepId);
            ToolResponse r = tool.execute(status);
            if (!r.isSuccess()) {
                return r;
            }
            Object state = ((java.util.Map<?, ?>) r.getData()).get("state");
            if (!"running".equals(state)) {
                return r;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting sweep " + sweepId, e);
            }
        }
        throw new AssertionError("sweep " + sweepId + " never finished within "
            + DEADLINE_MILLIS + " ms");
    }

    private static boolean has(JsonNode args, String field) {
        JsonNode v = args.get(field);
        return v != null && !v.isNull() && !v.asText().isBlank();
    }
}
