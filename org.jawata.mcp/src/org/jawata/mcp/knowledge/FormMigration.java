package org.jawata.mcp.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sprint 28c D4 — give every legacy entry the 28c form, or say honestly why it
 * cannot have one.
 *
 * <p><b>There is no disposal state.</b> Every source entry ends as
 * {@code migrated} or {@code legacy_kept}; nothing is deleted, nothing is
 * merged, nothing is silently dropped. A migration that can discard is a
 * migration whose report has to be trusted rather than checked, and this store
 * holds the only copy of what its owner learned.</p>
 *
 * <p><b>Derivation is MECHANICAL, and deliberately timid.</b> A situation is
 * derived only where the row already carries a cue that IS one — a recorded
 * symptom, or an operation. Everything else is {@code legacy_kept} with the
 * reason stated. The alternative — inventing a situation from a summary —
 * would produce ~2,500 sentences that describe what the entry says rather than
 * when it applies, every one of which would pass a count-based check while
 * making retrieval worse. The plan says it outright: a derived situation that
 * describes how the system works is a finding, not a pass.</p>
 *
 * <p><b>Dry run first, always.</b> {@link #plan} mutates nothing and returns
 * the disposition of every entry; only {@link #apply} writes, and only on an
 * explicit confirm from a human who has read that report.</p>
 */
public final class FormMigration {

    /** What a source entry became, or why it could not become it. */
    public record Disposition(String id, String outcome, String situation,
                              String verdict, String reason) {
        public static final String MIGRATED = "migrated";
        public static final String LEGACY_KEPT = "legacy_kept";
    }

    /**
     * The kept-reasons that mean NOTHING NEEDS REPAIR — shared with
     * {@link StoreQuality}, which excludes exactly these from its findings, so
     * the two surfaces cannot drift on what counts as healthy.
     *
     * <p>{@link #REASON_FACT_WITH_SITUATION} exists because of a measured
     * misclassification (2026-08-27): all 187 catalogue patterns — perfectly
     * formed, every one carrying an authored situation — were reported among
     * the "type 'reference' implies no outcome" defects, because the
     * classification stopped at the type before looking at the situation. A
     * fact that carries a situation owes nothing more; listing it as a defect
     * buries the ~36 real reference problems under 187 healthy rows.</p>
     */
    public static final String REASON_ALREADY_FORM_1 = "already form 1";
    public static final String REASON_FACT_WITH_SITUATION =
        "already carries a situation; a fact owes nothing more";

    /**
     * v15 (ruled 2026-08-27): a situated entry whose CAUSE is empty. The Minto
     * triad is situation → complication → solution, and this row has the first
     * and third with no middle — the solution's problem is unstated, so a
     * reader cannot tell whether it transfers. NOT healthy, NOT mechanically
     * derivable (a derived cause would be an invented sentence): it is the
     * repair-work class the review seat fills, and after the corpus is filled
     * this count is expected to sit at zero.
     */
    public static final String REASON_SITUATED_NO_CAUSE =
        "has a situation, no cause; the triad's middle is missing";

    /**
     * mcp#60: an INGESTED row, whose situation is not mechanically derivable and whose
     * durable fix is not in the store at all.
     *
     * <p>Distinct from "symptoms too short" on purpose: an ingested row's symptoms are
     * frequently long and still unusable, because they are harvested cues rather than
     * observations. Naming that separately is what stops a reader concluding the corpus
     * is thin when it is merely the wrong provenance for a mechanical rule.</p>
     */
    public static final String REASON_HARVESTED_NOT_DERIVABLE =
        "ingested from a file; its situation belongs in that file, not derived here";

    /**
     * The whole run: every source id exactly once, and the counts that
     * reconcile against that list.
     *
     * <p>{@code sourceEntries == migrated + legacyKept} is checkable from the
     * report alone, which is the point — a report whose totals can only be
     * taken on trust is not evidence.</p>
     *
     * <p>mcp#59: {@code retired} counts the rows the walk SKIPPED — superseded or
     * rejected, and so not repair work. It is deliberately OUTSIDE the invariant above
     * rather than folded into it: that equation was the report's one self-check, and
     * redefining a number consumers already reconcile would have broken the check while
     * looking like a fix. The store's own total is {@code sourceEntries + retired}, so a
     * reader can still reconcile against {@code stats} — and a retired row is now VISIBLE
     * as a number rather than vanishing from a shrinking count with no explanation.</p>
     */
    public record Report(int sourceEntries, int migrated, int legacyKept, int retired,
                         List<Disposition> dispositions,
                         Map<String, Integer> keptReasons,
                         Map<String, Integer> provenanceKinds,
                         boolean applied) {
    }

    private final ExperienceStore store;

    public FormMigration(ExperienceStore store) {
        this.store = store;
    }

    /**
     * The verdict a type mechanically implies.
     *
     * <p>Only two types imply one. A {@code failure_mode} is by definition
     * something that went wrong and is to be avoided; a {@code lesson} is
     * something that worked. Every other type — {@code domain_fact},
     * {@code api_contract}, {@code naming_convention}, {@code reference} — never
     * "turned out" any way at all, and attaching a verdict to one would be the
     * invented value this sprint already refused once.</p>
     */
    static String verdictFor(String type) {
        if (type == null) {
            return null;
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "failure_mode" -> "failed_avoid";
            case "lesson" -> "worked";
            default -> null;
        };
    }

    /**
     * A situation derived from a cue the row ALREADY carries, or null.
     *
     * <p>A symptom is how the problem looked, which is a condition; an operation
     * is what was being done, which is also one. A summary is the principle, and
     * a principle restated as a condition is a sentence about the system rather
     * than about when to apply it — so no summary is ever used here.</p>
     */
    /**
     * mcp#60: NOTHING IS DERIVED FOR AN INGESTED ROW, and that is two refusals in one.
     *
     * <p><b>Its symptoms are not observations.</b> The derivation takes the first symptom
     * long enough to read as a condition, which holds for a RECORDED row — a symptom there
     * is how the problem looked. An ingested row's symptoms are HARVESTED cues: headings,
     * bold phrases, prosified filename slugs. Measured on the pre-rebuild corpus, the dry
     * run proposed 71 migrations whose derived situations included "when by construction",
     * "when 107 seconds stale", "when $8 on one day" and "when race condition" — none of
     * which tells a later reader whether the entry is for them, which is the entire job of
     * a situation.</p>
     *
     * <p><b>And stamping one would not survive anyway.</b> An ingested row is DERIVED from
     * a file, so the store is not where it is authored. A reseed rebuilds from those files
     * and the stamped situation is gone — silently, because the count afterwards still
     * matches. Its durable fix is to edit the FILE, which {@code sourceRef} names.</p>
     *
     * <p>So a confirm:true could once have written 71 junk situations that a routine
     * reseed would then erase. Both halves point the same way: leave the row alone and
     * send the reader to the file.</p>
     */
    private static boolean isHarvested(StoredEntry e) {
        return e.facets() != null && "ingested".equals(e.facets().provenanceKind());
    }

    static String situationFor(StoredEntry e) {
        if (isHarvested(e)) {
            return null;
        }
        if (e.symptoms() != null) {
            for (String s : e.symptoms()) {
                if (s != null && s.strip().length() > 12) {
                    String t = s.strip().replaceAll("\\s+", " ").replaceAll("[.;]$", "");
                    return t.toLowerCase(Locale.ROOT).startsWith("when ") ? t : "when " + t;
                }
            }
        }
        String op = e.operation();
        if (op != null && !op.isBlank()) {
            return "when performing " + op.strip();
        }
        return null;
    }

    /** Plan the migration without writing anything. */
    public Report plan() {
        return run(false);
    }

    /**
     * Perform it — every migrated row updated, every kept row untouched.
     *
     * <p>Called only after a human has read {@link #plan}'s report. The caller
     * is expected to be pointed at a COPY: this sprint's sequence is backup,
     * restore, dry-run, approve, migrate side by side, leaving the original
     * byte-identical.</p>
     */
    public Report apply() {
        return run(true);
    }

    private Report run(boolean write) {
        List<Disposition> out = new ArrayList<>();
        Map<String, Integer> keptReasons = new LinkedHashMap<>();
        Map<String, Integer> provenance = new LinkedHashMap<>();
        int migrated = 0;
        int kept = 0;
        int retired = 0;

        for (StoredEntry e : store.all()) {
            // mcp#59: A RETIRED ROW IS NOT REPAIR WORK. store.all() applies no status
            // filter, so a superseded or rejected row arrives here and is dispositioned
            // like a live one — and lands in a keptReasons bucket that StoreQuality reads
            // as a repair class. Measured on v3.15.0: a catalogue update retired 187
            // pattern rows and the next dry run reported "has a situation, no cause" = 188.
            //
            // Skipped BEFORE the provenance tally, deliberately, so that every map in the
            // report describes ONE population — the live rows. Counting provenance over
            // 378 while the reasons describe 191 would make two numbers that cannot be
            // reconciled by anyone reading them.
            if (!e.isLive()) {
                retired++;
                continue;
            }
            // provenanceKind is READ here, not merely carried: this report groups
            // on it, and it is the accessor's named consumer.
            String pk = e.facets() == null || e.facets().provenanceKind() == null
                ? "(unset)" : e.facets().provenanceKind();
            provenance.merge(pk, 1, Integer::sum);

            // v15: a situated row without its cause is the SAME disposition
            // (kept, nothing mechanical to do) under a REASON StoreQuality does
            // NOT exclude — so the gap surfaces as repair work on every review
            // sweep instead of hiding inside a healthy class.
            boolean noCause = e.facets() == null || e.facets().cause() == null
                || e.facets().cause().isBlank();
            if (e.facets() != null && e.facets().isForm1()) {
                kept++;
                String reason = noCause ? REASON_SITUATED_NO_CAUSE : REASON_ALREADY_FORM_1;
                keptReasons.merge(reason, 1, Integer::sum);
                out.add(new Disposition(e.id(), Disposition.LEGACY_KEPT, null, null, reason));
                continue;
            }
            // A NON-EXPERIENCE row that already declares its situation is
            // healthy, whatever its form stamp says — the 187 catalogue
            // patterns sat exactly here, listed as defects for owing an
            // outcome no fact ever owes. Checked BEFORE the derivation,
            // because a declared situation beats a derived one.
            if (verdictFor(e.type()) == null && e.facets() != null
                    && e.facets().situation() != null
                    && !e.facets().situation().isBlank()) {
                kept++;
                String reason = noCause ? REASON_SITUATED_NO_CAUSE : REASON_FACT_WITH_SITUATION;
                keptReasons.merge(reason, 1, Integer::sum);
                out.add(new Disposition(e.id(), Disposition.LEGACY_KEPT, null, null, reason));
                continue;
            }
            String situation = situationFor(e);
            String verdict = verdictFor(e.type());
            if (situation == null) {
                kept++;
                // mcp#60: an ingested row is kept for a DIFFERENT reason from a thin one,
                // and saying "symptoms too short" here would be false about a row whose
                // symptoms are often long — they are simply harvested cues rather than
                // observations. The reason names the file instead, because that is where
                // the fix survives a reseed.
                String why = isHarvested(e)
                    ? REASON_HARVESTED_NOT_DERIVABLE
                    : e.symptoms() == null || e.symptoms().isEmpty()
                        ? "no symptom and no operation to derive a situation from"
                        : "symptoms too short to be a condition";
                keptReasons.merge(why, 1, Integer::sum);
                out.add(new Disposition(e.id(), Disposition.LEGACY_KEPT, null, null, why));
                continue;
            }
            if (verdict == null) {
                kept++;
                String why = "type '" + e.type() + "' implies no outcome; a fact never turned out";
                keptReasons.merge(why, 1, Integer::sum);
                out.add(new Disposition(e.id(), Disposition.LEGACY_KEPT, null, null, why));
                continue;
            }
            migrated++;
            out.add(new Disposition(e.id(), Disposition.MIGRATED, situation, verdict, null));
            if (write) {
                store.setForm(e.id(), situation, verdict);
            }
        }
        return new Report(out.size(), migrated, kept, retired, out, keptReasons, provenance,
            write);
    }
}
