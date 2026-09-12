package org.jawata.mcp.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Sprint 28c D14 — what was shown, what was chosen, and what was asked for and
 * not found.
 *
 * <p><b>This never touches ranking, and that is a rule rather than a
 * convention.</b> Relevance stays the only ranking key; usage decides
 * DELETION. An entry shown a hundred times and chosen never is a candidate for
 * removal, not a demoted result — the moment usage feeds the order, the store
 * starts answering with what has been popular instead of what fits, and the two
 * are least alike exactly where it matters. Nothing here is readable from the
 * merge: the counters live in their own table, and the only readers are the
 * review sweep's two lists.</p>
 *
 * <p><b>The two lists it exists to produce:</b></p>
 * <ul>
 *   <li>the <b>deletion list</b> — entries shown often and chosen never, for a
 *       human to rule on. Evidence plus their own eyes, never automatic.</li>
 *   <li>the <b>writing backlog</b> — questions asked repeatedly that nothing
 *       answered. Demand without supply is the only signal here that says what
 *       to WRITE next, and it is the reason the demand rows survive a wipe
 *       while the counters do not.</li>
 * </ul>
 *
 * <p><b>Writes are loud-drop, and the drop is counted.</b> A ledger write must
 * never break a recall: this rides the read path, and a failed bookkeeping
 * insert is not a reason to deny the caller their answer. But a rate computed
 * over silently lost rows reads as "nobody engaged with the advice" when the
 * truth is "we failed to write it down", so {@link #failedWrites()} is reported
 * beside the counts rather than swallowed.</p>
 */
public final class UsageLedger {

    private static final Logger log = LoggerFactory.getLogger(UsageLedger.class);

    private final Supplier<H2ExperienceStore> stores;
    private final AtomicLong failedWrites = new AtomicLong();

    /**
     * Takes a SUPPLIER rather than a store, because the store it writes to can be
     * replaced underneath it: {@code RecoveringExperienceStore} swaps its delegate
     * after a recovery, and a reference captured at construction would go on
     * writing to the connection that just died — silently, since every write here
     * is loud-drop by design.
     */
    public UsageLedger(Supplier<H2ExperienceStore> stores) {
        this.stores = stores;
    }

    /**
     * Candidates were shown for a question. Opens the demand row and bumps the
     * shown-count of every entry the caller was given.
     *
     * <p>A nomination that shows NOTHING is recorded too, and it is the most
     * valuable row in the table: a question with zero candidates is demand with
     * no supply, which is precisely the writing backlog. Skipping it because
     * "there is nothing to count" would delete the signal.</p>
     */
    public void nominated(String queryId, String cueKind, String question,
                          List<String> shownIds) {
        if (queryId == null || question == null) {
            return;
        }
        List<String> ids = shownIds == null ? List.of() : shownIds;
        try {
            H2ExperienceStore store = stores.get();
            if (store == null) {
                return;
            }
            Connection c = store.sharedConnection();
            synchronized (store) {
                try (PreparedStatement ps = c.prepareStatement(
                        "MERGE INTO usage_query (query_id, asked_at, cue_kind, question,"
                            + " shown_count, chosen) KEY(query_id)"
                            + " VALUES (?, CURRENT_TIMESTAMP, ?, ?, ?, FALSE)")) {
                    ps.setString(1, queryId);
                    ps.setString(2, cueKind);
                    ps.setString(3, clip(question));
                    ps.setInt(4, ids.size());
                    ps.executeUpdate();
                }
                bumpShown(c, ids);
            }
        } catch (SQLException e) {
            drop("nominated", e);
        }
    }

    /**
     * The caller judged the candidates. Closes the demand row and bumps the
     * chosen-count of every entry they kept.
     *
     * <p>Choosing NONE closes the row with {@code chosen} false, deliberately:
     * an honest absence and an unanswered question are the same fact from the
     * backlog's point of view — the store was asked and had nothing that
     * applied.</p>
     *
     * <p><b>That is true of the backlog and FALSE of the deletion list, which is
     * why {@code decided_at} exists (v16).</b> {@code chosen} alone cannot tell
     * <i>answered with none</i> from <i>never answered</i> — both are FALSE — so a
     * reader deciding what to DELETE could not tell a judgement from an absence of
     * one. The timestamp records that a disposition ARRIVED, separately from what
     * it contained, and {@link #conformance()} is what reports the two apart.</p>
     */
    public void decided(String queryId, List<String> chosenIds) {
        if (queryId == null) {
            return;
        }
        List<String> ids = chosenIds == null ? List.of() : chosenIds;
        try {
            H2ExperienceStore store = stores.get();
            if (store == null) {
                return;
            }
            Connection c = store.sharedConnection();
            synchronized (store) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE usage_query SET chosen = ?, decided_at = CURRENT_TIMESTAMP"
                            + " WHERE query_id = ?")) {
                    ps.setBoolean(1, !ids.isEmpty());
                    ps.setString(2, queryId);
                    ps.executeUpdate();
                }
                for (String id : ids) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "MERGE INTO usage_entry (entry_id, shown, chosen, last_chosen)"
                                + " KEY(entry_id) VALUES (?,"
                                + " COALESCE((SELECT shown FROM usage_entry WHERE entry_id = ?), 0),"
                                + " COALESCE((SELECT chosen FROM usage_entry WHERE entry_id = ?), 0) + 1,"
                                + " CURRENT_TIMESTAMP)")) {
                        ps.setString(1, id);
                        ps.setString(2, id);
                        ps.setString(3, id);
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            drop("decided", e);
        }
    }

    private void bumpShown(Connection c, List<String> ids) throws SQLException {
        for (String id : ids) {
            try (PreparedStatement ps = c.prepareStatement(
                    "MERGE INTO usage_entry (entry_id, shown, chosen, last_shown)"
                        + " KEY(entry_id) VALUES (?,"
                        + " COALESCE((SELECT shown FROM usage_entry WHERE entry_id = ?), 0) + 1,"
                        + " COALESCE((SELECT chosen FROM usage_entry WHERE entry_id = ?), 0),"
                        + " CURRENT_TIMESTAMP)")) {
                ps.setString(1, id);
                ps.setString(2, id);
                ps.setString(3, id);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Entries shown at least {@code minShown} times and never chosen, worst
     * first. The review seat presents this for a human ruling; nothing here
     * deletes anything.
     */
    public List<Map<String, Object>> deletionList(long minShown, int limit) {
        return read("deletionList",
            "SELECT u.entry_id, u.shown, u.chosen, e.summary FROM usage_entry u"
                + " JOIN experience_entry e ON e.id = u.entry_id"
                + " WHERE u.chosen = 0 AND u.shown >= ?"
                + " ORDER BY u.shown DESC LIMIT ?",
            minShown, limit,
            rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getString(1));
                row.put("shown", rs.getLong(2));
                row.put("chosen", rs.getLong(3));
                row.put("summary", rs.getString(4));
                return row;
            });
    }

    /**
     * Questions asked at least {@code minTimes} times that nothing answered,
     * most-asked first. This is the writing backlog: what the corpus is missing,
     * stated by the people who went looking for it.
     */
    public List<Map<String, Object>> writingBacklog(int minTimes, int limit) {
        return read("writingBacklog",
            "SELECT question, COUNT(*) AS times, MAX(asked_at) AS last_asked"
                + " FROM usage_query WHERE chosen = FALSE"
                + " GROUP BY question HAVING COUNT(*) >= ?"
                + " ORDER BY times DESC LIMIT ?",
            minTimes, limit,
            rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("question", rs.getString(1));
                row.put("timesUnanswered", rs.getLong(2));
                row.put("lastAsked", String.valueOf(rs.getTimestamp(3)));
                return row;
            });
    }

    /**
     * The sentence that must travel WITH the figure, never only in a comment.
     *
     * <p>D4's measure is that the conformance figure is <i>"shown as observed or
     * labelled as un-observed at the point of display, not only in a field
     * comment"</i>. A caveat a caller has to go and read the source for is a
     * caveat nobody reads, so it rides in the response.</p>
     */
    public static final String CONFORMANCE_CAVEAT =
        "OBSERVED counts nominations a disposition actually arrived for; DERIVED counts"
            + " nominations nobody ever answered, where a zero chosen-count means NOT"
            + " OBSERVED rather than judged-and-rejected. Rows written before schema v16"
            + " are counted DERIVED unless their chosen flag proves a disposition — the"
            + " old column could not tell the two apart, so the count under-claims"
            + " observation rather than over-claiming it.";

    /**
     * How much of this ledger was WITNESSED, reported apart from what is inferred.
     *
     * <p><b>Why both numbers rather than one ratio.</b> The deletion list ranks
     * entries by shown-often-chosen-never, and that ranking is only evidence about
     * a human's judgement where a human judged. Over nominations nobody ever
     * dispositioned the same rows look identical and mean nothing. A single
     * percentage hides which population it was taken over; two counts under
     * separate keys cannot.</p>
     *
     * <p><b>And the percentage is absent rather than 100 when nothing was
     * nominated</b> — borrowed knowingly from studio's own utilization figure,
     * whose comment says an empty denominator is not 100 % and that printing one
     * would be the exact lie this work exists to end.</p>
     */
    public Map<String, Object> conformance() {
        H2ExperienceStore store = stores.get();
        if (store == null) {
            throw new IllegalStateException(
                "usage ledger read failed (conformance): no H2 store behind this resident");
        }
        Connection c = store.borrowRead();
        boolean healthy = true;
        // COUNT(decided_at) counts the NON-NULL ones, which is the whole question.
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*), COUNT(decided_at) FROM usage_query")) {
            long nominations = 0;
            long observed = 0;
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    nominations = rs.getLong(1);
                    observed = rs.getLong(2);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("nominations", nominations);
            out.put("observed", observed);
            out.put("derived", nominations - observed);
            out.put("percentObserved", nominations == 0
                ? null
                : Math.round((observed * 1000.0) / nominations) / 10.0);
            out.put("caveat", CONFORMANCE_CAVEAT);
            return out;
        } catch (SQLException e) {
            healthy = false;
            throw new IllegalStateException("usage ledger read failed (conformance)", e);
        } finally {
            store.releaseRead(c, healthy);
        }
    }

    /** How many ledger writes were dropped. Reported, never swallowed. */
    public long failedWrites() {
        return failedWrites.get();
    }

    private interface RowReader {
        Map<String, Object> read(ResultSet rs) throws SQLException;
    }

    /**
     * A read here THROWS rather than returning an empty list. The two answers
     * "nothing has been shown yet" and "the ledger could not be read" are
     * opposite, and a sweep that renders the second as the first tells a human
     * their store is clean when the instrument is broken.
     */
    private List<Map<String, Object>> read(String what, String sql, long a, int b,
                                           RowReader reader) {
        H2ExperienceStore store = stores.get();
        if (store == null) {
            throw new IllegalStateException(
                "usage ledger read failed (" + what + "): no H2 store behind this resident");
        }
        Connection c = store.borrowRead();
        boolean healthy = true;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, a);
            ps.setInt(2, b);
            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(reader.read(rs));
                }
            }
            return out;
        } catch (SQLException e) {
            healthy = false;
            throw new IllegalStateException("usage ledger read failed (" + what + ")", e);
        } finally {
            store.releaseRead(c, healthy);
        }
    }

    private static String clip(String q) {
        return q.length() <= 4096 ? q : q.substring(0, 4096);
    }

    private void drop(String what, SQLException e) {
        failedWrites.incrementAndGet();
        log.warn("usage ledger write dropped ({}): {}", what, e.toString());
    }
}
