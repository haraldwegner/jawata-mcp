package org.jawata.mcp.knowledge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.jawata.mcp.engine.EngineEnvironment;
import org.jawata.mcp.engine.SettingKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sprint 28g S21 — <b>the transcript lane</b>: the discussion, kept so that nothing said is lost
 * when a session is compacted.
 *
 * <p>What it holds is what he typed, what the agent said to him, and one line per tool call (the
 * tool, its key argument, ok or failed). It holds no tool output, none of the host's injected
 * context and none of the agent's reasoning. A turn is one row, its text compressed; a second
 * table maps each word of the turn to the turn, so a search is a lookup in the database and never
 * a scan of the discussion held in memory.</p>
 *
 * <p><b>Its own tables, beside {@code experience_entry}, on purpose.</b> Every verb that walks
 * {@code experience_entry} (export, import, review, prune, describe, wipe and import) would
 * otherwise need one more "leave the transcript alone" filter to remember. Here no such filter
 * exists because the rows are not where those verbs look. Retrieval vocabulary, the database file
 * and its backup are shared; the storage is not.</p>
 *
 * <p><b>The switch.</b> Capture is on unless the deployment turns it off with the system property
 * {@value #ENABLED_PROPERTY} (or the environment variable {@value #ENABLED_ENV}) set to
 * {@code false} / {@code off}. Off is a deployment setting and not a user one: nothing is
 * captured, nothing is searched, and any rows already present are removed the next time this
 * store is touched.</p>
 *
 * <p>Words are tokenised by {@link LexicalIndex#tokenize} and a word carried by most turns is
 * ignored by {@link LexicalIndex#discriminates}; this class adds no tokeniser and no rarity rule
 * of its own.</p>
 */
public final class TranscriptStore {

    private static final Logger log = LoggerFactory.getLogger(TranscriptStore.class);

    /** System property: {@code false} or {@code off} turns the lane off as a whole. */
    public static final String ENABLED_PROPERTY = SettingKeys.TRANSCRIPT_ENABLED.name();

    /** Environment variable with the same meaning, for a deployment that sets no properties. */
    public static final String ENABLED_ENV = "JAWATA_TRANSCRIPT";

    /** Words of one turn that are indexed; a turn is a discussion, not a corpus. */
    static final int MAX_TERMS_PER_TURN = 400;

    /** Words of a cue that are looked up. */
    static final int MAX_CUE_TERMS = 12;

    /** Shortest word that is indexed or looked up. */
    static final int MIN_TERM_CHARS = 3;

    /** Longest word that is indexed; the column holds 48. */
    static final int MAX_TERM_CHARS = 48;

    /** Characters of a quote returned with a hit. */
    static final int QUOTE_CHARS = 280;

    /** Below this many turns the common-word rule has no distribution to judge by. */
    static final int RARITY_NEEDS_TURNS = 20;

    private static final char SEGMENT_END = '\u0000';

    /** Who said a segment of a turn. */
    public enum Who {
        /** Typed by him. */
        HE('H', "he said"),
        /** Said by the agent to him. */
        AGENT('A', "the agent said"),
        /** One line per tool call. */
        TOOL('T', "the agent ran");

        private final char code;
        private final String phrase;

        Who(char code, String phrase) {
            this.code = code;
            this.phrase = phrase;
        }

        /** The one letter stored in the compressed text. */
        public char code() {
            return code;
        }

        /** How the answer says who it was. */
        public String phrase() {
            return phrase;
        }

        static Who of(char code) {
            for (Who w : values()) {
                if (w.code == code) {
                    return w;
                }
            }
            return null;
        }
    }

    /**
     * One captured turn.
     *
     * @param sessionId the client's session
     * @param turnKey the client's own id for the record that opened the turn; stable across
     *     captures of the same turn, which is what makes a second capture complete the first
     * @param ts when the turn ended
     * @param client the client that held the session
     * @param kind {@code turn} for the capture at a turn's end, {@code compaction} for the catch-up
     *     taken before a compaction
     * @param his what he typed, verbatim; may be empty
     * @param agent what the agent said to him, verbatim; may be empty
     * @param tools one line per tool call
     */
    public record Turn(String sessionId, String turnKey, Instant ts, String client, String kind,
            String his, String agent, List<String> tools) {
        public Turn {
            tools = tools == null ? List.of() : List.copyOf(tools);
            his = his == null ? "" : his;
            agent = agent == null ? "" : agent;
            kind = kind == null || kind.isBlank() ? "turn" : kind;
        }
    }

    /** What {@link #append} did. */
    public enum Appended {
        /** A new turn was stored. */
        STORED,
        /** The turn was already held in full. */
        DUPLICATE,
        /** The turn was held in part and has been completed. */
        COMPLETED,
        /** The lane is off; nothing was stored. */
        DISABLED,
        /** The write failed after its retries; counted, never silent. */
        FAILED
    }

    /**
     * One found quote with the turn it came from.
     *
     * @param sessionId the session
     * @param turnNo the turn within the session
     * @param ts when the turn ended
     * @param who who said it
     * @param quote the words, clipped around the match
     * @param matchedTerms how many of the cue's words the turn carries
     */
    public record Hit(String sessionId, int turnNo, Instant ts, Who who, String quote,
            int matchedTerms) {
    }

    /**
     * What a search found and how many turns it looked through.
     *
     * @param hits the quotes, best first
     * @param rowsSearched the number of turns held, which is what the answer reports as scanned
     */
    public record Found(List<Hit> hits, long rowsSearched) {
    }

    /** What a check of his words came to. */
    public enum Verdict {
        /** A turn holds the words among his. */
        FOUND,
        /** The lane was read in full and no turn holds them among his words. */
        NOT_FOUND,
        /** The lane could not say: off, empty, unreadable, or too many turns to read them all. */
        UNCHECKED
    }

    /**
     * The answer to "did he say this": a verdict, and the turn when it was found. Only
     * {@link Verdict#NOT_FOUND} may be said to him as an accusation; an unchecked answer is a
     * failure to look and is never worded as an absence.
     *
     * @param verdict what the check came to
     * @param hit the turn that holds the words, present only for {@link Verdict#FOUND}
     */
    public record Said(Verdict verdict, Hit hit) {
        static Said found(Hit hit) {
            return new Said(Verdict.FOUND, hit);
        }

        static Said notFound() {
            return new Said(Verdict.NOT_FOUND, null);
        }

        static Said unchecked() {
            return new Said(Verdict.UNCHECKED, null);
        }
    }

    /** Words of a quoted sentence that must all be present in a turn of his. */
    static final int MAX_QUOTE_TERMS = 40;

    /** Turns of his that hold every word of a quote, read in full before the check gives up. */
    static final int MAX_QUOTE_CANDIDATES = 500;

    private final H2ExperienceStore store;
    private long failedWrites;

    public TranscriptStore(H2ExperienceStore store) {
        this.store = store;
        if (!enabled()) {
            purge();
        } else {
            ensureHisTerms();
        }
    }

    /** Whether the lane captures and answers; read at each call so a deployment can change it. */
    public static boolean enabled() {
        String value = EngineEnvironment.settings().get(ENABLED_PROPERTY).orElse(null);
        if (value == null) {
            return true;
        }
        String v = value.strip().toLowerCase(Locale.ROOT);
        return !(v.equals("false") || v.equals("off") || v.equals("0") || v.equals("no"));
    }

    /** Writes that failed every retry; surfaced, never hidden. */
    public synchronized long failedWrites() {
        return failedWrites;
    }

    /**
     * Stores one turn, once. A turn already held in full is left alone, and one held in part (a
     * capture taken while the turn was still running) is completed.
     */
    public Appended append(Turn turn) {
        if (!enabled()) {
            purge();
            return Appended.DISABLED;
        }
        synchronized (store) {
            try {
                Appended[] result = new Appended[1];
                LearnerEventStore.withRetry(LearnerEventStore.WRITE_ATTEMPTS,
                    () -> result[0] = write(store.sharedConnection(), turn),
                    store::invalidateSharedConnection);
                return result[0];
            } catch (Exception e) {
                failedWrites++;
                log.error("TRANSCRIPT TURN DROPPED after {} attempts — session={} turn={}: the"
                    + " lane is incomplete until this is fixed",
                    LearnerEventStore.WRITE_ATTEMPTS, turn.sessionId(), turn.turnKey(), e);
                return Appended.FAILED;
            }
        }
    }

    private Appended write(Connection conn, Turn turn) throws Exception {
        byte[] body = compress(encode(turn));
        int chars = charsOf(turn);
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            Long existingId = null;
            int existingChars = -1;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, chars FROM transcript_turn WHERE session_id = ? AND turn_key = ?")) {
                ps.setString(1, turn.sessionId());
                ps.setString(2, turn.turnKey());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        existingId = rs.getLong(1);
                        existingChars = rs.getInt(2);
                    }
                }
            }
            if (existingId != null && existingChars >= chars) {
                conn.rollback();
                return Appended.DUPLICATE;
            }
            long id;
            if (existingId != null) {
                for (String table : new String[] {"transcript_term", "transcript_his_term"}) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM " + table + " WHERE turn_id = ?")) {
                        ps.setLong(1, existingId);
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE transcript_turn SET ts = ?, kind = ?, chars = ?, body = ?"
                            + " WHERE id = ?")) {
                    ps.setTimestamp(1, Timestamp.from(turn.ts()));
                    ps.setString(2, turn.kind());
                    ps.setInt(3, chars);
                    ps.setBytes(4, body);
                    ps.setLong(5, existingId);
                    ps.executeUpdate();
                }
                id = existingId;
            } else {
                int nextNo = 1;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COALESCE(MAX(turn_no), 0) + 1 FROM transcript_turn"
                            + " WHERE session_id = ?")) {
                    ps.setString(1, turn.sessionId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            nextNo = rs.getInt(1);
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO transcript_turn (session_id, turn_key, turn_no, ts, client,"
                            + " kind, chars, body, workspace_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, turn.sessionId());
                    ps.setString(2, turn.turnKey());
                    ps.setInt(3, nextNo);
                    ps.setTimestamp(4, Timestamp.from(turn.ts()));
                    ps.setString(5, turn.client());
                    ps.setString(6, turn.kind());
                    ps.setInt(7, chars);
                    ps.setBytes(8, body);
                    ps.setString(9, store.provenanceWorkspaceId());
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        id = keys.getLong(1);
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO transcript_term (term, turn_id) VALUES (?, ?)")) {
                for (String term : termsOf(turn)) {
                    ps.setString(1, term);
                    ps.setLong(2, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            insertHisTerms(conn, id, turn.his());
            conn.commit();
            return existingId == null ? Appended.STORED : Appended.COMPLETED;
        } catch (Exception e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    /**
     * Posts the words he typed in a turn on their own, so the check of a quote asks the database for
     * his turns and the speaker is decided before anything is cut.
     */
    private void insertHisTerms(Connection conn, long turnId, String his) throws Exception {
        // Every word, with no cap: a pasted passage is his too, and a cut here would answer "not
        // found" for a sentence from the part that was cut.
        List<String> terms = tokensOf(his, Integer.MAX_VALUE);
        if (terms.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO transcript_his_term (term, turn_id) VALUES (?, ?)")) {
            for (String term : terms) {
                ps.setString(1, term);
                ps.setLong(2, turnId);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Creates the postings of his words if this store predates them, and fills them from the turns
     * already held. A store migrated by an earlier build of version 25 has the turns and not the
     * postings; without this its quotes could not be checked until every old turn was stored again.
     */
    private void ensureHisTerms() {
        synchronized (store) {
            try {
                Connection conn = store.sharedConnection();
                try (Statement s = conn.createStatement()) {
                    SchemaMigrations.createHisTerms(s);
                }
                // Every turn with no posting of his words, not only a wholly empty table: a fill that
                // stopped part way, or a turn written by an older resident sharing this store, would
                // otherwise answer "not found" for words he did say. A turn he typed nothing in has
                // none and is read again each time, which costs one decompression apiece.
                List<Long> missing = new ArrayList<>();
                try (Statement s = conn.createStatement();
                        ResultSet rs = s.executeQuery("SELECT t.id FROM transcript_turn t WHERE NOT"
                            + " EXISTS (SELECT 1 FROM transcript_his_term h WHERE h.turn_id = t.id)")) {
                    while (rs.next()) {
                        missing.add(rs.getLong(1));
                    }
                }
                int filled = 0;
                for (Long id : missing) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT body FROM transcript_turn WHERE id = ?")) {
                        ps.setLong(1, id);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                String his = String.join("\n", decode(decompress(rs.getBytes(1)))
                                    .getOrDefault(Who.HE, List.of()));
                                if (!his.isBlank()) {
                                    // One transaction per turn, so a failure part way leaves the
                                    // turn with none, which the next open fills again, and never
                                    // with some, which would read as complete.
                                    boolean auto = conn.getAutoCommit();
                                    conn.setAutoCommit(false);
                                    try {
                                        insertHisTerms(conn, id, his);
                                        conn.commit();
                                        filled++;
                                    } catch (Exception e) {
                                        conn.rollback();
                                        throw e;
                                    } finally {
                                        conn.setAutoCommit(auto);
                                    }
                                }
                            }
                        }
                    }
                }
                if (filled > 0) {
                    log.info("transcript lane: postings of his words built for {} turn(s) that had none",
                        filled);
                }
            } catch (Exception e) {
                log.error("transcript lane: the postings of his words could not be prepared; quote"
                    + " checks answer 'could not check' until they can", e);
            }
        }
    }

    /**
     * The quotes that best match {@code cue}, best first.
     *
     * @param cue the words to look for
     * @param limit the most quotes to return
     * @return the quotes and how many turns were searched; empty hits when the lane is off
     */
    public Found search(String cue, int limit) {
        if (!enabled()) {
            purge();
            return new Found(List.of(), 0);
        }
        synchronized (store) {
            try {
                Connection conn = store.sharedConnection();
                long turns = countTurns(conn);
                List<String> terms = cueTerms(cue);
                if (terms.isEmpty() || turns == 0) {
                    return new Found(List.of(), turns);
                }
                return new Found(rank(conn, terms, turns, Math.max(1,limit)), turns);
            } catch (Exception e) {
                log.error("transcript search failed for '{}'", cue, e);
                return new Found(List.of(), -1);
            }
        }
    }

    /**
     * Whether he said these words, and where. Used before something is attributed to him: a quote
     * found here is his; a quote not found is named back to whoever attributed it.
     *
     * @param quote the words attributed to him
     * @return the turn that holds them among his words, or empty when none does
     */
    public TranscriptStore.Said findHisWords(String quote) {
        if (!enabled() || quote == null || quote.isBlank()) {
            return Said.unchecked();
        }
        synchronized (store) {
            try {
                Connection conn = store.sharedConnection();
                List<String> terms = tokensOf(quote, MAX_QUOTE_TERMS);
                if (terms.isEmpty() || countTurns(conn) == 0) {
                    // Nothing to look for, or nothing held to look in: the lane cannot vouch either way.
                    return Said.unchecked();
                }
                List<Long> candidates = hisTurnsHoldingEvery(conn, terms);
                String wanted = normalise(quote);
                for (Long id : candidates) {
                    Hit hit = hisSentenceContaining(conn, id, wanted);
                    if (hit != null) {
                        return Said.found(hit);
                    }
                }
                // Read in full, so a miss is an absence; cut at the cap, it is only a failure to look.
                return candidates.size() >= MAX_QUOTE_CANDIDATES ? Said.unchecked() : Said.notFound();
            } catch (Exception e) {
                log.error("transcript quote check failed", e);
                return Said.unchecked();
            }
        }
    }

    /**
     * The turns, newest first, whose own typed words contain every word of a quote. The speaker is
     * part of the posting, so the cut to {@link #MAX_QUOTE_CANDIDATES} is taken among turns of his
     * and never among everything that mentions the words, where the agent's later echoes of a
     * ruling would push the ruling out.
     */
    private List<Long> hisTurnsHoldingEvery(Connection conn, List<String> terms) throws Exception {
        String in = String.join(",", Collections.nCopies(terms.size(), "?"));
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT turn_id FROM transcript_his_term WHERE term IN (" + in
                    + ") GROUP BY turn_id HAVING COUNT(*) = ? ORDER BY turn_id DESC LIMIT ?")) {
            int p = 1;
            for (String term : terms) {
                ps.setString(p++, term);
            }
            ps.setInt(p++, terms.size());
            ps.setInt(p, MAX_QUOTE_CANDIDATES);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    /** The turn {@code id} as a hit when one of his sentences in it contains {@code wanted}. */
    private Hit hisSentenceContaining(Connection conn, long id, String wanted) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT session_id, turn_no, ts, body FROM transcript_turn WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                for (String sentence : decode(decompress(rs.getBytes(4))).getOrDefault(Who.HE, List.of())) {
                    if (normalise(sentence).contains(wanted)) {
                        return new Hit(rs.getString(1), rs.getInt(2), rs.getTimestamp(3).toInstant(),
                            Who.HE, clip(sentence, 0), 0);
                    }
                }
            }
        }
        return null;
    }

    /**
     * What he typed most recently, newest first: the thread to pick up after a compaction.
     *
     * @param sessionId the session, or {@code null} for any
     * @param limit how many of his messages
     */
    public Found recent(String sessionId, int limit) {
        if (!enabled()) {
            purge();
            return new Found(List.of(), 0);
        }
        synchronized (store) {
            try {
                Connection conn = store.sharedConnection();
                long turns = countTurns(conn);
                List<Hit> hits = new ArrayList<>();
                String sql = "SELECT session_id, turn_no, ts, body FROM transcript_turn"
                    + (sessionId == null || sessionId.isBlank() ? "" : " WHERE session_id = ?")
                    + " ORDER BY ts DESC, id DESC LIMIT ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    int p = 1;
                    if (sessionId != null && !sessionId.isBlank()) {
                        ps.setString(p++, sessionId);
                    }
                    ps.setInt(p, Math.max(1, limit));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Map<Who, List<String>> segments = decode(decompress(rs.getBytes(4)));
                            List<String> his = segments.getOrDefault(Who.HE, List.of());
                            if (!his.isEmpty()) {
                                hits.add(new Hit(rs.getString(1), rs.getInt(2),
                                    rs.getTimestamp(3).toInstant(), Who.HE,
                                    clip(his.get(0), 0), 0));
                            }
                        }
                    }
                }
                return new Found(hits, turns);
            } catch (Exception e) {
                log.error("transcript recent failed", e);
                return new Found(List.of(), -1);
            }
        }
    }

    /** Turns held; {@code -1} when the count could not be read, which is never a clean zero. */
    public long count() {
        synchronized (store) {
            try {
                return countTurns(store.sharedConnection());
            } catch (Exception e) {
                log.error("transcript count failed", e);
                return -1;
            }
        }
    }

    /** Removes every transcript row, and only those. Returns how many turns went. */
    public int purge() {
        synchronized (store) {
            try {
                Connection conn = store.sharedConnection();
                int turns;
                try (Statement s = conn.createStatement()) {
                    s.executeUpdate("DELETE FROM transcript_term");
                    s.executeUpdate("DELETE FROM transcript_his_term");
                    turns = s.executeUpdate("DELETE FROM transcript_turn");
                }
                if (turns > 0) {
                    log.info("transcript lane is off: {} turn(s) removed", turns);
                }
                return turns;
            } catch (Exception e) {
                log.error("transcript purge failed", e);
                return -1;
            }
        }
    }

    // --- ranking -------------------------------------------------------------------------

    private List<Hit> rank(Connection conn, List<String> terms, long turns, int limit) throws Exception {
        String in = String.join(",", Collections.nCopies(terms.size(), "?"));
        Map<String, Integer> docFreq = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT term, COUNT(*) FROM transcript_term WHERE term IN (" + in
                    + ") GROUP BY term")) {
            for (int i = 0; i < terms.size(); i++) {
                ps.setString(i + 1, terms.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    docFreq.put(rs.getString(1), rs.getInt(2));
                }
            }
        }
        List<String> used = new ArrayList<>();
        Map<String, Double> idf = new HashMap<>();
        for (String t : terms) {
            Integer df = docFreq.get(t);
            if (df == null) {
                continue;
            }
            if (turns >= RARITY_NEEDS_TURNS && !LexicalIndex.discriminates(df, (int) turns)) {
                continue;
            }
            used.add(t);
            idf.put(t, Math.log(1.0 + (double) turns / df));
        }
        if (used.isEmpty()) {
            return List.of();
        }
        String usedIn = String.join(",", Collections.nCopies(used.size(), "?"));
        Map<Long, double[]> scores = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT turn_id, term FROM transcript_term WHERE term IN (" + usedIn + ")")) {
            for (int i = 0; i < used.size(); i++) {
                ps.setString(i + 1, used.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double[] s = scores.computeIfAbsent(rs.getLong(1), k -> new double[2]);
                    s[0] += 1;
                    s[1] += idf.get(rs.getString(2));
                }
            }
        }
        int needed = Math.max(1, (used.size() + 1) / 2);
        List<Map.Entry<Long, double[]>> candidates = new ArrayList<>();
        for (Map.Entry<Long, double[]> e : scores.entrySet()) {
            if (e.getValue()[0] >= needed) {
                candidates.add(e);
            }
        }
        candidates.sort(Comparator
            .<Map.Entry<Long, double[]>>comparingDouble(e -> -e.getValue()[0])
            .thenComparingDouble(e -> -e.getValue()[1])
            .thenComparing(e -> -e.getKey()));
        int take = Math.min(candidates.size(), limit * 3);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < take; i++) {
            ids.add(candidates.get(i).getKey());
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        String idIn = String.join(",", Collections.nCopies(ids.size(), "?"));
        Map<Long, Object[]> rows = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, session_id, turn_no, ts, body FROM transcript_turn WHERE id IN ("
                    + idIn + ")")) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setLong(i + 1, ids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.put(rs.getLong(1), new Object[] {rs.getString(2), rs.getInt(3),
                        rs.getTimestamp(4).toInstant(), rs.getBytes(5)});
                }
            }
        }
        List<Hit> hits = new ArrayList<>();
        for (Long id : ids) {
            Object[] row = rows.get(id);
            if (row == null) {
                continue;
            }
            Map<Who, List<String>> segments = decode(decompress((byte[]) row[3]));
            Hit hit = bestSegment((String) row[0], (int) row[1], (Instant) row[2], segments,
                used, (int) scores.get(id)[0]);
            if (hit != null) {
                hits.add(hit);
            }
            if (hits.size() >= limit) {
                break;
            }
        }
        return hits;
    }

    private static Hit bestSegment(String session, int turnNo, Instant ts,
            Map<Who, List<String>> segments, List<String> terms, int matched) {
        Set<String> wanted = new LinkedHashSet<>(terms);
        Hit best = null;
        int bestOverlap = 0;
        for (Who who : new Who[] {Who.HE, Who.AGENT, Who.TOOL}) {
            for (String segment : segments.getOrDefault(who, List.of())) {
                int overlap = 0;
                Set<String> present = new LinkedHashSet<>(LexicalIndex.tokenize(segment));
                for (String t : wanted) {
                    if (present.contains(t)) {
                        overlap++;
                    }
                }
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = new Hit(session, turnNo, ts, who,
                        clip(segment, firstMatchAt(segment, wanted)), matched);
                }
            }
        }
        return best;
    }

    private static int firstMatchAt(String segment, Set<String> wanted) {
        String lower = segment.toLowerCase(Locale.ROOT);
        int at = Integer.MAX_VALUE;
        for (String t : wanted) {
            int i = lower.indexOf(t);
            if (i >= 0 && i < at) {
                at = i;
            }
        }
        return at == Integer.MAX_VALUE ? 0 : at;
    }

    private static String clip(String text, int around) {
        String t = text.strip().replaceAll("\\s+", " ");
        if (t.length() <= QUOTE_CHARS) {
            return t;
        }
        int from = Math.max(0, Math.min(around, t.length() - QUOTE_CHARS) - QUOTE_CHARS / 4);
        from = Math.max(0, Math.min(from, t.length() - QUOTE_CHARS));
        String cut = t.substring(from, from + QUOTE_CHARS);
        return (from > 0 ? "…" : "") + cut + (from + QUOTE_CHARS < t.length() ? "…" : "");
    }

    private static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s\"'“”‘’`]+", " ").strip();
    }

    // --- terms, text and compression -----------------------------------------------------

    private static List<String> cueTerms(String cue) {
        return tokensOf(cue, MAX_CUE_TERMS);
    }

    private static List<String> tokensOf(String text, int max) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : LexicalIndex.tokenize(text)) {
            if (t.length() >= MIN_TERM_CHARS && t.length() <= MAX_TERM_CHARS) {
                out.add(t);
                if (out.size() >= max) {
                    break;
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static Set<String> termsOf(Turn turn) {
        StringBuilder sb = new StringBuilder(turn.his()).append(' ').append(turn.agent());
        for (String tool : turn.tools()) {
            sb.append(' ').append(tool);
        }
        return new LinkedHashSet<>(tokensOf(sb.toString(), MAX_TERMS_PER_TURN));
    }

    private static int charsOf(Turn turn) {
        int n = turn.his().length() + turn.agent().length();
        for (String t : turn.tools()) {
            n += t.length();
        }
        return n;
    }

    private static String encode(Turn turn) {
        StringBuilder sb = new StringBuilder();
        if (!turn.his().isEmpty()) {
            sb.append(Who.HE.code()).append(turn.his()).append(SEGMENT_END);
        }
        if (!turn.agent().isEmpty()) {
            sb.append(Who.AGENT.code()).append(turn.agent()).append(SEGMENT_END);
        }
        for (String tool : turn.tools()) {
            sb.append(Who.TOOL.code()).append(tool).append(SEGMENT_END);
        }
        return sb.toString();
    }

    private static Map<Who, List<String>> decode(String text) {
        Map<Who, List<String>> out = new LinkedHashMap<>();
        for (String segment : text.split(String.valueOf(SEGMENT_END))) {
            if (segment.isEmpty()) {
                continue;
            }
            Who who = Who.of(segment.charAt(0));
            if (who != null) {
                out.computeIfAbsent(who, k -> new ArrayList<>()).add(segment.substring(1));
            }
        }
        return out;
    }

    static byte[] compress(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    static String decompress(byte[] packed) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static long countTurns(Connection conn) throws Exception {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM transcript_turn")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }
}
