package org.jawata.mcp.knowledge;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where somebody has actually been working — Sprint 28g D31, <i>coverage follows use</i>.
 *
 * <h2>The problem it exists for</h2>
 *
 * <p>The cataloguer's queue is the workspace in file order. That is a route nobody's attention
 * takes: a package opened this morning and a package nobody has touched in a year are the same
 * row to it, so the describing effort spreads evenly over code whose value to a reader is not
 * even at all. This is the other input — where the work has been — so the next batch can start
 * where somebody has just been.</p>
 *
 * <h2>It records the USE, never the verdict</h2>
 *
 * <p>The obvious design is a flag meaning <i>worked in and still undescribed</i>, and it is
 * wrong within a day: the moment that package IS described the flag is stale, and nothing in
 * the describing path knows to clear it. So a row holds only what was OBSERVED — this package
 * was opened, this many times, most recently then — and whether it still needs describing is
 * derived at read time against the described rows. A derived verdict cannot go stale because
 * it is not stored.</p>
 *
 * <h2>Every write is loud-drop, and that is deliberate</h2>
 *
 * <p>{@link #noted} is called on a path a PERSON is waiting on — opening a file — and its value
 * is a better queue tomorrow. Failing that call because a bookkeeping insert failed would trade
 * something that matters for something that does not. A failure is counted in
 * {@link #failedWrites} and logged, so a store that has silently stopped recording is still
 * answerable rather than merely quiet, which is this project's own recorded deepest bug class.</p>
 *
 * <p>It takes a SUPPLIER rather than a store, for {@link UsageLedger}'s reason: the store can be
 * replaced underneath it when {@code RecoveringExperienceStore} swaps its delegate after a
 * recovery, and a reference captured at construction would go on writing to a connection that
 * has already died.</p>
 */
public final class WorkedPackages {

    private static final Logger log = LoggerFactory.getLogger(WorkedPackages.class);

    private final Supplier<H2ExperienceStore> stores;
    private final AtomicLong failedWrites = new AtomicLong();

    public WorkedPackages(Supplier<H2ExperienceStore> stores) {
        this.stores = stores;
    }

    /**
     * Record that somebody is working in this package, now.
     *
     * <p>An upsert: the first visit writes the row, every later one bumps the count and moves
     * the timestamp. Both halves are kept because they answer different questions — how much of
     * somebody's attention this package has had, and whether that attention is current.</p>
     */
    public void noted(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return;
        }
        try {
            H2ExperienceStore store = stores.get();
            if (store == null) {
                return;
            }
            // The SHARED connection under the store's own monitor, which is how every other
            // writer here reaches it: the connection is the store's, not this object's, so
            // closing it would take the store's own down with it.
            Connection c = store.sharedConnection();
            synchronized (store) {
                try (PreparedStatement ps = c.prepareStatement(
                        "MERGE INTO worked_package (package, times, last_seen) KEY(package)"
                            + " SELECT ?, COALESCE((SELECT times FROM worked_package"
                            + " WHERE package = ?), 0) + 1, CURRENT_TIMESTAMP")) {
                    ps.setString(1, packageName);
                    ps.setString(2, packageName);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException | RuntimeException e) {
            drop("note a worked package", e);
        }
    }

    /**
     * The worked-in packages that STILL need describing, most recently worked first.
     *
     * <p>"Still needs describing" is derived here rather than stored: a package counts when the
     * store holds no AREA row governing it. That is the same question {@code describe area}
     * answers for a reader, asked of the whole set at once.</p>
     *
     * <p>Ordered by recency and not by count, deliberately. The count says how much attention a
     * package has had and the timestamp says whether that attention is CURRENT, and a queue
     * meant to follow the work wants the second: a package opened forty times last spring has
     * had its turn, and the one opened twice this morning is where somebody is.</p>
     *
     * <p>Returns an empty list — never throws — when the store cannot be read. The caller is
     * assembling a describing batch, and a batch that fails because the preference could not be
     * computed is worse than one that falls back to the ordinary order. The drop is counted.</p>
     */
    public List<String> stillUndescribed(int limit) {
        List<String> found = new ArrayList<>();
        if (limit <= 0) {
            return found;
        }
        String sql =
            "SELECT w.package FROM worked_package w WHERE NOT EXISTS ("
                + "  SELECT 1 FROM experience_entry e"
                + "  WHERE e.type = 'area' AND e.package_name = w.package AND e.retired_at IS NULL)"
                + " ORDER BY w.last_seen DESC LIMIT ?";
        try {
            H2ExperienceStore store = stores.get();
            if (store == null) {
                return found;
            }
            // Not closed, and not a leak: the connection belongs to the store and is shared by
            // every reader and writer here. Closing it would take the store's own down. A
            // resource-leak check reads this as unclosed because from inside this method it is
            // indistinguishable from one — the difference is ownership, which only this sentence
            // carries.
            Connection c = store.sharedConnection();
            synchronized (store) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setInt(1, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            found.add(rs.getString(1));
                        }
                    }
                }
            }
        } catch (SQLException | RuntimeException e) {
            drop("read the worked packages", e);
            return List.of();
        }
        return found;
    }

    /** How many writes were dropped — so a store that stopped recording is answerable. */
    public long failedWrites() {
        return failedWrites.get();
    }

    private void drop(String what, Exception e) {
        failedWrites.incrementAndGet();
        log.warn("could not {}: {}", what, e.toString());
    }
}
