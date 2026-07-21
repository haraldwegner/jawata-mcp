package org.jawata.mcp.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 27 D4 — dispatch recall: what a past seat run decided, read off the
 * carrier it already rides.
 *
 * <p>The studio runner ends every seat run by recording it through
 * {@code experience(kind=record)} — type {@code seat_run}, operation
 * {@code seat:<name>}, and the whole journal entry (the problem statement, the
 * gates, the human's verdict) in {@code details}. So the corpus already exists
 * and is already embedded: the problem statement is part of the entry's
 * embedded text, which is what lets a cue phrased as prose find a run whose
 * SHAPE resembles it. Nothing here fetches, indexes, or re-journals anything —
 * this class only reads a nominated entry and states the three facts a
 * dispatch question is actually asking for.</p>
 *
 * <p>Two honesty rules, both load-bearing:</p>
 * <ol>
 *   <li><b>An unjudged run says so.</b> A run whose human verdict is still null
 *       renders "not yet judged" — never an inferred verdict, and never a
 *       silent omission that would read like acceptance.</li>
 *   <li><b>A past run is never a rule.</b> It reaches the agent through the
 *       analogy path, with the analogy's framing intact; the coded
 *       seat-workflow rules are what say what SHOULD happen, and this only
 *       says what once DID.</li>
 * </ol>
 */
public final class DispatchRecall {

    /** The entry type the studio runner records every seat run as. */
    public static final String SEAT_RUN_TYPE = "seat_run";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DispatchRecall() {
    }

    /** The three facts a dispatch question asks for, plus the work that occasioned them. */
    public record PastRun(String seat, String target, String humanVerdict, String outcome,
                          String work) {

        /** True when a human has judged this run; false is stated, never hidden. */
        public boolean judged() {
            return humanVerdict != null && !humanVerdict.isBlank();
        }
    }

    /** Is this entry a recorded seat run? */
    public static boolean isSeatRun(StoredEntry e) {
        return e != null && SEAT_RUN_TYPE.equalsIgnoreCase(e.type());
    }

    /**
     * Read the run facts out of an entry, or {@code null} when this is not a
     * seat run — an unparseable journal is NOT silently treated as an empty
     * run: it falls back to the entry's own operation/summary, which are the
     * facts the store holds independently of the journal blob.
     */
    public static PastRun of(StoredEntry e) {
        if (!isSeatRun(e)) {
            return null;
        }
        String seat = seatOf(e.operation());
        String target = null;
        String verdict = null;
        String outcome = null;
        String work = null;

        Object details = e.body() == null ? null : e.body().get("details");
        if (details != null) {
            try {
                JsonNode n = MAPPER.readTree(details.toString());
                seat = text(n, "seat", seat);
                target = text(n, "target", null);
                verdict = text(n, "humanVerdict", null);
                outcome = text(n, "outcome", null);
                work = text(n, "work", null);
            } catch (Exception ex) {
                // A journal we cannot parse still leaves the store's own columns
                // true; we report those and claim nothing about the rest.
                work = null;
            }
        }
        return new PastRun(seat, target, verdict, outcome, work);
    }

    /**
     * One line of dispatch facts, appended to whatever carrier line the entry
     * already renders on. Advisory by construction: it reports one past run,
     * it does not prescribe.
     */
    public static String renderLine(PastRun r) {
        if (r == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("past run: seat ");
        sb.append(r.seat() == null || r.seat().isBlank() ? "unnamed" : r.seat());
        if (r.target() != null && !r.target().isBlank()) {
            sb.append(" on ").append(r.target());
        }
        sb.append(" — human verdict: ")
            .append(r.judged() ? r.humanVerdict() : "not yet judged");
        if (r.outcome() != null && !r.outcome().isBlank()) {
            sb.append(" — outcome: ").append(r.outcome());
        }
        return sb.toString();
    }

    /** Structured form for the MCP answer, mirroring {@link #renderLine}. */
    public static Map<String, Object> toMap(PastRun r) {
        if (r == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seat", r.seat());
        if (r.target() != null) {
            m.put("target", r.target());
        }
        // Always present, even unjudged: an absent key would read as "no verdict
        // needed" rather than "nobody has judged this yet".
        m.put("human_verdict", r.judged() ? r.humanVerdict() : "not yet judged");
        if (r.outcome() != null) {
            m.put("outcome", r.outcome());
        }
        if (r.work() != null) {
            m.put("work", r.work());
        }
        return m;
    }

    /** {@code seat:debugger} → {@code debugger}; anything else passes through. */
    private static String seatOf(String operation) {
        if (operation == null || operation.isBlank()) {
            return null;
        }
        int colon = operation.indexOf(':');
        return colon >= 0 ? operation.substring(colon + 1).trim() : operation.trim();
    }

    private static String text(JsonNode n, String field, String fallback) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return fallback;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? fallback : s;
    }
}
