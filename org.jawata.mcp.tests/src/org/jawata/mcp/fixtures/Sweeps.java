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

    /** How long a fixture sweep may take before the test fails rather than hangs. */
    private static final long DEADLINE_MILLIS = 120_000;

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
