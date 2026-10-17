package org.jawata.mcp.execution;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The tokens a brain reported spending, read from its own final event (the sprint spec, 2026-10-11:
 * "read from each brain's own final event. Where a brain does not report tokens, the record says so;
 * it never shows zero").
 *
 * <p>ABSENT IS NOT ZERO, for the same reason a missing dollar cost is not zero: a brain that reported
 * nothing is not a brain that spent nothing, and a figure of 0 would be read as an efficient run.
 * So a dialect that does not read a usage block, or whose final event carried none, yields no
 * {@code TokenUsage} at all, and the record says "NOT REPORTED BY THIS BRAIN".</p>
 *
 * <p>The fields are normalised so that {@link #spent()} means the same thing for every brain: {@code
 * input} is ALL the input tokens, with any cached part included, and {@code cached} is how much of
 * that the brain said it served from cache. Vendors differ in whether their own input figure already
 * includes the cached part (codex's does, claude's does not), so each dialect adds the cached part
 * where the vendor leaves it out. {@code reasoning} is the part of {@code output} spent thinking and is
 * informational: it is already inside {@code output}.</p>
 *
 * @param input     all input tokens, cached part included
 * @param cached    the part of {@code input} served from cache; informational
 * @param output    all output tokens, reasoning included
 * @param reasoning the part of {@code output} that was reasoning; informational
 * @param basis     one line saying how the figures were read, so a reader sees what is vendor-reported
 *                  and what this class normalised
 */
public record TokenUsage(long input, long cached, long output, long reasoning, String basis) {

    /** What the run spent: input plus output. The one figure a per-instruction score divides. */
    public long spent() {
        return input + output;
    }

    /**
     * Reads a usage block whose keys the vendor names, summing every key given for the cached part
     * and adding that part to the input when the vendor leaves it out.
     *
     * @return empty when the node is missing, is not an object, or carries neither an input nor an
     *         output count: a block with neither is not a report
     */
    static Optional<TokenUsage> read(JsonNode usage, String inputKey, boolean inputIncludesCached,
            String[] cachedKeys, String outputKey, String reasoningKey, String basis) {
        if (usage == null || !usage.isObject()) {
            return Optional.empty();
        }
        boolean hasInput = usage.path(inputKey).isNumber();
        boolean hasOutput = usage.path(outputKey).isNumber();
        if (!hasInput && !hasOutput) {
            return Optional.empty();
        }
        long input = usage.path(inputKey).asLong(0);
        long cached = 0;
        for (String key : cachedKeys) {
            cached += usage.path(key).asLong(0);
        }
        long output = usage.path(outputKey).asLong(0);
        long reasoning = reasoningKey == null ? 0 : usage.path(reasoningKey).asLong(0);
        return Optional.of(new TokenUsage(inputIncludesCached ? input : input + cached, cached, output,
            reasoning, basis));
    }

    /** The figures as an object for a record or an answer, with the one that is divided and the basis. */
    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("input", input);
        m.put("cached", cached);
        m.put("output", output);
        m.put("reasoning", reasoning);
        m.put("spent", spent());
        m.put("basis", basis);
        return m;
    }

    /** Two reports of one run added together, for a brain that reports per turn rather than once. */
    TokenUsage plus(TokenUsage other) {
        return new TokenUsage(input + other.input, cached + other.cached, output + other.output,
            reasoning + other.reasoning, basis);
    }
}
