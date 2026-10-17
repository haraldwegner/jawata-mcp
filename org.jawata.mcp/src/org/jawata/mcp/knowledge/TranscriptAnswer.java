package org.jawata.mcp.knowledge;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sprint 28g S21 — what the transcript lane says, in the shape every other lane already answers
 * in, so a caller reads a quote exactly as it reads a lesson: a row with a type, a line of words,
 * and a pointer to where it came from.
 *
 * <p>A quote becomes an {@code entries} row of type {@code transcript}: the summary is the words,
 * the situation says who said them and when, and {@code session} / {@code turn} point at the turn.
 * The lane's size travels as {@code scanned.transcript}, the same figure the other lanes report.</p>
 *
 * <p>An absence is always SAID. A lane that is switched off, or not installed on this resident,
 * answers {@code transcript_note} in words and a searched count of zero; it never answers as if it
 * had looked and found nothing.</p>
 */
public final class TranscriptAnswer {

    /** The row type of a quote. */
    public static final String TYPE = "transcript";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneOffset.UTC);

    private TranscriptAnswer() {
    }

    /** One quote as an answer row. */
    public static Map<String, Object> row(TranscriptStore.Hit hit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "transcript:" + shortSession(hit.sessionId()) + "#" + hit.turnNo());
        row.put("type", TYPE);
        row.put("lane", KnowledgeLane.TRANSCRIPT.wire());
        row.put("summary", hit.quote());
        row.put("situation", hit.who().phrase() + " on " + DAY.format(hit.ts()) + " UTC, turn "
            + hit.turnNo() + " of session " + shortSession(hit.sessionId()));
        row.put("session", hit.sessionId());
        row.put("turn", hit.turnNo());
        row.put("ts", hit.ts().toString());
        row.put("who", hit.who().name().toLowerCase(java.util.Locale.ROOT));
        return row;
    }

    /**
     * {@code answer} with the transcript's quotes for {@code cue} added to its entries and its row
     * count added to {@code scanned}.
     *
     * @param answer what the other lanes of the call answered
     * @param transcript the lane, or {@code null} when none is installed on this resident
     * @param cue the words to look for
     * @param cap the most quotes to add
     */
    public static Map<String, Object> merge(Map<String, Object> answer, TranscriptStore transcript,
            String cue, int cap) {
        Map<String, Object> out = new LinkedHashMap<>(answer);
        List<Map<String, Object>> entries = entriesOf(out);
        Map<String, Object> scanned = scannedOf(out);
        if (transcript == null) {
            out.put("transcript_note", "The transcript lane is NOT INSTALLED on this resident,"
                + " so it was not searched.");
            scanned.put(KnowledgeLane.TRANSCRIPT.wire(), 0);
        } else if (!TranscriptStore.enabled()) {
            out.put("transcript_note", "The transcript lane is switched OFF on this deployment:"
                + " nothing is captured and nothing was searched.");
            scanned.put(KnowledgeLane.TRANSCRIPT.wire(), 0);
        } else {
            TranscriptStore.Found found = transcript.search(cue, cap);
            scanned.put(KnowledgeLane.TRANSCRIPT.wire(), Math.max(0, found.rowsSearched()));
            if (found.rowsSearched() < 0) {
                out.put("transcript_note", "The transcript lane could not be read; this is NOT"
                    + " an absence.");
            }
            for (TranscriptStore.Hit h : found.hits()) {
                entries.add(row(h));
            }
        }
        return finish(out, entries, scanned);
    }

    /**
     * The thread to pick up again after a compaction: what he typed most recently, newest first.
     *
     * @param base the answer skeleton (its {@code cue}), or an empty map
     * @param transcript the lane, or {@code null} when none is installed
     * @param sessionId the session to take it from, or {@code null} for any
     * @param limit how many of his messages
     */
    public static Map<String, Object> recent(Map<String, Object> base, TranscriptStore transcript,
            String sessionId, int limit) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.put("result", ExperienceRetrieval.RESULT_ABSENCE);
        out.put("message", "No earlier discussion is held.");
        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Object> scanned = new LinkedHashMap<>();
        if (transcript == null) {
            out.put("transcript_note", "The transcript lane is NOT INSTALLED on this resident.");
            scanned.put(KnowledgeLane.TRANSCRIPT.wire(), 0);
        } else if (!TranscriptStore.enabled()) {
            out.put("transcript_note", "The transcript lane is switched OFF on this deployment.");
            scanned.put(KnowledgeLane.TRANSCRIPT.wire(), 0);
        } else {
            TranscriptStore.Found found = transcript.recent(sessionId, limit);
            scanned.put(KnowledgeLane.TRANSCRIPT.wire(), Math.max(0, found.rowsSearched()));
            for (TranscriptStore.Hit h : found.hits()) {
                entries.add(row(h));
            }
        }
        return finish(out, entries, scanned);
    }

    /**
     * Whether he said these words, with the turn that holds them.
     *
     * @return a map with {@code found} and, when found, the row; {@code checked} is false when the
     *     lane could not check at all (off, or not installed), which is never the same as not found
     */
    public static Map<String, Object> verify(TranscriptStore transcript, String quote) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quote", quote);
        if (transcript == null || !TranscriptStore.enabled()) {
            out.put("checked", false);
            out.put("found", false);
            out.put("note", transcript == null
                ? "The transcript lane is not installed on this resident, so nothing was checked."
                : "The transcript lane is switched off, so nothing was checked.");
            return out;
        }
        Optional<TranscriptStore.Hit> hit = transcript.findHisWords(quote);
        out.put("checked", true);
        out.put("found", hit.isPresent());
        hit.ifPresent(h -> out.put("entry", row(h)));
        return out;
    }

    private static Map<String, Object> finish(Map<String, Object> out,
            List<Map<String, Object>> entries, Map<String, Object> scanned) {
        out.put("entries", entries);
        out.put("scanned", scanned);
        boolean quotes = entries.stream().anyMatch(e -> TYPE.equals(e.get("type")));
        if (quotes && ExperienceRetrieval.RESULT_ABSENCE.equals(out.get("result"))) {
            out.put("result", ExperienceRetrieval.RESULT_MATCH);
            out.remove("message");
            out.remove("reason");
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entriesOf(Map<String, Object> out) {
        Object existing = out.get("entries");
        return existing instanceof List<?> list
            ? new ArrayList<>((List<Map<String, Object>>) list) : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> scannedOf(Map<String, Object> out) {
        Object existing = out.get("scanned");
        return existing instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private static String shortSession(String sessionId) {
        return sessionId == null ? "?" : sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId;
    }

    /** The one line a text answer ends with when the lane could not search; empty otherwise. */
    public static String noteLine(Map<String, Object> answer) {
        Object note = answer.get("transcript_note");
        return note == null ? "" : "\n" + note;
    }
}
