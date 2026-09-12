package org.jawata.mcp.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 28f Stage 7 deliverable 3 — which source units have been described, and at what text.
 *
 * <p>Describing a codebase is not one act. It is a queue walked over many sessions by an agent
 * with a token budget, and the only thing that makes it RESUMABLE is a record of what has
 * already been done. Without one, every run starts at the first file — which turns a bounded
 * job into an unbounded one and makes the second half of any large bundle unreachable.</p>
 *
 * <h2>The hash is the row's point, not bookkeeping beside it</h2>
 *
 * <p>A unit that was described and has since been EDITED is not described any more: the jobs
 * derived from it are about text that has changed. Keyed on the unit alone the ledger would
 * answer "done" forever. Keyed on the unit AND its content hash, one question answers both:
 * <b>a unit is outstanding when it has no row, or when its row disagrees with the file in
 * front of you.</b> A changed unit therefore re-queues BY CONSTRUCTION rather than by a sweep
 * somebody has to remember to run.</p>
 *
 * <h2>What this class does not do, and why the split is where it is</h2>
 *
 * <p>It does not know what a unit IS. Enumerating the source files of a scope is JDT's job and
 * belongs to the caller; this answers only which of the units it is HANDED are outstanding.
 * That keeps a ledger a ledger — it can be tested against a list of strings, with no project
 * loaded, which is what {@code DescribedUnitsTest} does.</p>
 *
 * <h2>Writes are loud-drop, and the drop is counted</h2>
 *
 * <p>The same rule {@link UsageLedger} states: a bookkeeping failure must not break the work
 * above it, and a resume computed over silently lost rows reads as "these units were never
 * described" when the truth is "we failed to write it down". {@link #failedWrites()} is
 * reported beside the counts rather than swallowed.</p>
 *
 * <h2>Why it is NOT called CatalogueLedger</h2>
 *
 * <p>The plan names it that, and the word is taken on this very store: five {@code Catalogue*}
 * classes, a {@code CATALOGUE_TYPE}, a {@code provenance_kind} of {@code catalogue} and a
 * {@code catalogueBlock} in the tool's own {@code stats} response, all meaning the IMPORTED
 * PATTERN CATALOGUE — somebody else's designs seeded as {@code reference} rows. This is about
 * describing OUR code, and {@code stats} would otherwise have carried two catalogue sections
 * meaning different things. A declared deviation from the plan's naming, recorded there.</p>
 */
public final class DescribedUnits {

    private static final Logger log = LoggerFactory.getLogger(DescribedUnits.class);

    private final Supplier<H2ExperienceStore> stores;
    private final java.util.concurrent.atomic.AtomicLong failedWrites =
        new java.util.concurrent.atomic.AtomicLong();

    /**
     * Takes a SUPPLIER rather than a store, for {@link UsageLedger}'s reason: the store can be
     * replaced underneath it when {@code RecoveringExperienceStore} swaps its delegate after a
     * recovery, and a reference captured at construction would go on writing to a connection
     * that has already died — silently, since every write here is loud-drop by design.
     */
    public DescribedUnits(Supplier<H2ExperienceStore> stores) {
        this.stores = stores;
    }

    /** One source unit as the caller enumerated it: where it is, what it says, whose it is. */
    public record Unit(String path, String contentHash, String bundle) {

        /** A unit whose hash is taken from its text — the ordinary construction. */
        public static Unit of(String path, String content, String bundle) {
            return new Unit(path, hash(content), bundle);
        }
    }

    /**
     * The units among {@code candidates} that still need describing, in the caller's own order,
     * at most {@code limit} of them.
     *
     * <p>Outstanding means NO ROW or a row whose hash disagrees. Those two are deliberately one
     * answer: to the agent walking the queue they are the same instruction — read this unit and
     * describe it — and separating them would make the caller decide something that changes
     * nothing about what it does next.</p>
     *
     * <p><b>Order is the CALLER'S, not the database's.</b> The caller enumerated the units and
     * knows what a sensible reading order is (a package at a time, say); a ledger that re-sorted
     * them would be deciding the agent's route from a table that knows only what is finished.</p>
     *
     * <p>A store that cannot be read answers with the candidates UNFILTERED rather than with
     * nothing. That direction is deliberate: describing a unit twice costs tokens, and answering
     * "everything is done" when the ledger is unreadable costs the whole remaining corpus.</p>
     */
    public List<Unit> outstanding(List<Unit> candidates, int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        Map<String, String> done = describedHashes();
        List<Unit> out = new ArrayList<>();
        for (Unit u : candidates) {
            if (u == null || u.path() == null) {
                continue;
            }
            String seen = done.get(u.path());
            if (seen == null || !seen.equals(u.contentHash())) {
                out.add(u);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Record that {@code unit} has been described at the text {@code contentHash} names.
     *
     * <p>MERGE rather than INSERT: describing a unit again after an edit REPLACES the row, so
     * the ledger holds one fact per unit — the text it was last described at — rather than a
     * history nobody reads. Returns whether the row was written, so a caller that must not
     * report progress it did not record can tell.</p>
     */
    public boolean done(String unit, String contentHash, String bundle) {
        if (unit == null || unit.isBlank() || contentHash == null || contentHash.isBlank()) {
            return false;
        }
        try {
            H2ExperienceStore store = stores.get();
            if (store == null) {
                return false;
            }
            Connection c = store.sharedConnection();
            synchronized (store) {
                try (PreparedStatement ps = c.prepareStatement(
                        "MERGE INTO described_unit (unit, content_hash, bundle, done_at)"
                            + " KEY(unit) VALUES (?, ?, ?, CURRENT_TIMESTAMP)")) {
                    ps.setString(1, unit);
                    ps.setString(2, contentHash);
                    ps.setString(3, bundle);
                    ps.executeUpdate();
                }
            }
            return true;
        } catch (SQLException e) {
            drop("done", e);
            return false;
        }
    }

    /** How many units are described, per bundle — the numerator of any coverage figure. */
    public Map<String, Long> describedPerBundle() {
        Map<String, Long> out = new LinkedHashMap<>();
        try {
            H2ExperienceStore store = stores.get();
            if (store == null) {
                return out;
            }
            Connection c = store.sharedConnection();
            synchronized (store) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT bundle, COUNT(*) FROM described_unit GROUP BY bundle");
                        ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String bundle = rs.getString(1);
                        // A null bundle is a real state — a unit described before anyone
                        // said whose it was — and it is NAMED rather than folded into
                        // whichever group happens to sort first.
                        out.put(bundle == null ? "unattributed" : bundle, rs.getLong(2));
                    }
                }
            }
        } catch (SQLException e) {
            drop("describedPerBundle", e);
        }
        return out;
    }

    /** Bookkeeping writes this ledger LOST. Reported beside any count taken from it. */
    public long failedWrites() {
        return failedWrites.get();
    }

    /** unit path → the content hash it was last described at. */
    private Map<String, String> describedHashes() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            H2ExperienceStore store = stores.get();
            if (store == null) {
                return out;
            }
            Connection c = store.sharedConnection();
            synchronized (store) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT unit, content_hash FROM described_unit");
                        ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
        } catch (SQLException e) {
            drop("describedHashes", e);
        }
        return out;
    }

    /**
     * The content hash of a unit's text.
     *
     * <p>SHA-256 over the bytes, the same function {@code ExperienceMaintenance.sourceHash}
     * uses for a memory file, because both answer the identical question — has this text
     * changed since we last looked at it — and two hashes of one question drift the first time
     * either moves.</p>
     */
    public static String hash(String content) {
        if (content == null) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 is mandated by the platform; if it is genuinely absent, an empty hash
            // makes every unit outstanding, which re-describes rather than skipping.
            log.warn("no SHA-256 available; every unit will read as outstanding: {}",
                e.getMessage());
            return "";
        }
    }

    private void drop(String what, SQLException e) {
        failedWrites.incrementAndGet();
        log.warn("described_unit {} failed (dropped, counted): {}", what, e.getMessage());
    }
}
