package org.jawata.mcp.execution;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the ended hires under one lead used together (28h z136): how many there were, their money, time and tokens, and how many of them
 * published no money or no token figure, so a total never reads a missing figure as nothing.
 */
public record HireTotals(int hires, double costUsd, int withoutCost, long elapsedMs, long tokens, int withoutTokens) {

    public static final HireTotals NONE = new HireTotals(0, 0, 0, 0, 0, 0);

    /** The two totals added up. */
    public HireTotals plus(HireTotals other) {
        return new HireTotals(hires + other.hires, costUsd + other.costUsd, withoutCost + other.withoutCost, elapsedMs + other.elapsedMs,
            tokens + other.tokens, withoutTokens + other.withoutTokens);
    }

    /** What one ended hire used itself and under it, read from its answer: its own figures and the totals it carries from below. */
    public static HireTotals of(JsonNode answer) {
        JsonNode cost = answer.path("costUsd");
        JsonNode spent = answer.path("tokens").path("spent");
        HireTotals own = new HireTotals(1, cost.isNumber() ? cost.asDouble() : 0, cost.isNumber() ? 0 : 1,
            answer.path("journalRow").path("detail").path("elapsedMs").asLong(0), spent.isNumber() ? spent.asLong() : 0,
            spent.isNumber() ? 0 : 1);
        JsonNode below = answer.path("below");
        if (!below.isObject()) {
            return own;
        }
        return own.plus(new HireTotals(below.path("hires").asInt(0), below.path("costUsd").asDouble(0), below.path("withoutCost").asInt(0),
            below.path("elapsedMs").asLong(0), below.path("tokens").asLong(0), below.path("withoutTokens").asInt(0)));
    }

    /** The totals as the hire's answer carries them. */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("hires", hires);
        map.put("costUsd", costUsd);
        map.put("withoutCost", withoutCost);
        map.put("elapsedMs", elapsedMs);
        map.put("tokens", tokens);
        map.put("withoutTokens", withoutTokens);
        return map;
    }
}
