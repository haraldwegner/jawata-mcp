package org.jawata.mcp.learn;

/**
 * Sprint 27 D3 — the deterministic identity check, EXTRACTED from what was
 * implicit in {@code ToolExperienceStore.recentMatching}'s SQL (situation LIKE
 * %target%): a past capture is about THIS target iff its situation contains the
 * target key.
 *
 * <p>Made explicit because it is the ONLY authority that may arm the
 * {@link PrecedentLedger}: the justification-cost is charged for repeating a
 * warned mistake on the SAME target. A merely similar case must never charge —
 * similarity advises, identity warns. Keeping the check in one named place is
 * what lets the semantic retriever widen what the agent SEES without widening
 * what the ledger CHARGES.</p>
 */
public final class IdentityMatch {

    private IdentityMatch() {
    }

    /** True iff this capture is about the given target (not merely similar to it). */
    public static boolean matches(ToolExperience e, String target) {
        if (e == null || target == null || target.isBlank()) {
            return false;
        }
        String situation = e.situation();
        return situation != null && situation.contains(target);
    }
}
