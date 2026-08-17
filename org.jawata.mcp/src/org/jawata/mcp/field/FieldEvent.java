package org.jawata.mcp.field;

/**
 * One sanitized tool outcome (Sprint 28b, D1) — the sanitizer AS A TYPE. Every
 * field is an enum-like {@link Token}, a boolean, or a number; there is no
 * free-text field, so a path, message or symbol cannot be stored. Shapes,
 * never content (the redaction allowlist in the signed 28b spec).
 *
 * @param epochMillis   when the call completed
 * @param tool          the tool name (bounded registry vocabulary)
 * @param kind          the tool's kind/action argument, or {@code unknown}
 * @param ok            whether the call succeeded
 * @param errorCode     the structured error CODE (never the message);
 *                      {@code unknown} for a success
 * @param latencyBucket log-scale duration bucket, see {@link #bucket(long)}
 * @param client        the connected client, from the closed vocabulary
 * @param version       the jawata version, parse-or-unknown ints only
 */
public record FieldEvent(
    long epochMillis,
    Token tool,
    Token kind,
    boolean ok,
    Token errorCode,
    int latencyBucket,
    Token client,
    Version version
) {

    /** Log-scale duration buckets: 0 &lt;10ms · 1 &lt;100ms · 2 &lt;1s · 3 &lt;10s ·
     *  4 &lt;60s · 5 &lt;10min · 6 longer. */
    public static int bucket(long durationMs) {
        if (durationMs < 10) return 0;
        if (durationMs < 100) return 1;
        if (durationMs < 1_000) return 2;
        if (durationMs < 10_000) return 3;
        if (durationMs < 60_000) return 4;
        if (durationMs < 600_000) return 5;
        return 6;
    }

    /** The dedupe key for recurring-error grouping (D4's nudge, D3's ranking).
     *  Canonical by construction — every part is an allowlisted token, so no
     *  further normalization is needed or possible. */
    public String shapeKey() {
        return tool.value() + "/" + kind.value() + "/" + errorCode.value();
    }

    /** One pile line. Hand-rolled on purpose: every value is a token, digit or
     *  boolean, so no JSON escaping can ever be needed — a value that would
     *  need escaping cannot exist in this record. */
    public String toJsonLine() {
        return "{\"t\":" + epochMillis
            + ",\"tool\":\"" + tool.value() + '"'
            + ",\"kind\":\"" + kind.value() + '"'
            + ",\"ok\":" + ok
            + ",\"code\":\"" + errorCode.value() + '"'
            + ",\"lat\":" + latencyBucket
            + ",\"client\":\"" + client.value() + '"'
            + ",\"ver\":\"" + version.token() + "\"}";
    }
}
