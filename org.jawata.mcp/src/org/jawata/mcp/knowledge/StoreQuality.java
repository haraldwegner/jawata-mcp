package org.jawata.mcp.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 28c Stage 15 — what in the store is badly written, said so an agent can
 * FIX it rather than merely count it.
 *
 * <p><b>This produces no third number.</b> The corpus already has one diagnosis:
 * {@link FormMigration#plan()}, whose report every earlier gate reconciles
 * against. A second classifier over the same rows would drift from the first
 * and then both would be argued with. So this class RUNS that plan and
 * re-projects its dispositions — the counts here are the migration dry-run's
 * counts, by construction, and a mismatch between the two is impossible rather
 * than merely untested.</p>
 *
 * <p><b>What it adds is the {@code source_ref}</b>, per finding — the one fact
 * the dispositions lack and the repair cannot do without. An entry derived from
 * a file is durably fixed IN THE FILE (a store write is erased by the next
 * reseed); an entry with no file behind it can only be fixed in the store. The
 * caller holding a finding must be able to tell which repair is the durable
 * one, and until this class no read surface returned the source path at all.</p>
 */
public final class StoreQuality {

    /**
     * One repairable entry: where it is, what it says now, what is wrong with
     * it, and where its durable fix lives.
     *
     * @param sourceRef the file behind the entry, or null — and null is a FACT
     *                  (recorded directly, no file exists), not a lookup miss
     */
    public record Finding(String id, String type, String summary, String situation,
                          String defect, String sourceRef) {
    }

    /**
     * The scan: the dry-run's totals, findings grouped by defect, and a capped
     * finding list whose cap is DECLARED — a capped list read as complete is
     * this store's oldest lie.
     */
    public record Report(int entries, int mechanicallyMigratable,
                         Map<String, Integer> defects,
                         List<Finding> findings,
                         int findingsTotal, boolean findingsTruncated) {
    }

    private StoreQuality() {
    }

    /**
     * Scan the store. Read-only — the underlying plan writes nothing.
     *
     * @param limit maximum findings LISTED; the counts always cover everything
     */
    public static Report scan(ExperienceStore store, int limit) {
        FormMigration.Report plan = new FormMigration(store).plan();

        // The join key the dispositions lack: id -> the row's own facts.
        Map<String, StoredEntry> byId = new LinkedHashMap<>();
        for (StoredEntry e : store.all()) {
            byId.put(e.id(), e);
        }

        Map<String, Integer> defects = new LinkedHashMap<>();
        List<Finding> findings = new ArrayList<>();
        int total = 0;
        for (FormMigration.Disposition d : plan.dispositions()) {
            if (!FormMigration.Disposition.LEGACY_KEPT.equals(d.outcome())) {
                continue;       // migratable mechanically — the migration's work, not a finding
            }
            String reason = d.reason() == null ? "(unstated)" : d.reason();
            if ("already form 1".equals(reason)) {
                continue;       // formed by its author or a reviewed rewrite — nothing to repair
            }
            defects.merge(reason, 1, Integer::sum);
            total++;
            if (findings.size() < limit) {
                StoredEntry e = byId.get(d.id());
                findings.add(new Finding(
                    d.id(),
                    e == null ? null : e.type(),
                    e == null ? null : e.summary(),
                    e == null || e.facets() == null ? null : e.facets().situation(),
                    reason,
                    e == null ? null : e.sourceRef()));
            }
        }
        return new Report(plan.sourceEntries(), plan.migrated(), defects,
            findings, total, total > findings.size());
    }
}
