package org.jawata.mcp.knowledge;

import java.util.Locale;
import java.util.Set;

/**
 * Sprint 27 D2 — the retrieval ontology, as code.
 *
 * <p>The store holds two different things, and retrieving them the same way is
 * what made recall brittle:</p>
 *
 * <dl>
 *   <dt><b>FACT</b> — a statement about code at an address</dt>
 *   <dd>"this method's contract is X", "this API returns null when Y". A fact
 *       can be <em>wrong</em>: the code it describes may have changed or gone.
 *       So it passes a hard, address-bound gate and is TERMINAL — one node or
 *       an honest nothing. A plausible guess would be worse than silence,
 *       because the agent cannot check it.</dd>
 *
 *   <dt><b>EXPERIENCE</b> — what happened in a situation</dt>
 *   <dd>a lesson, a failure mode, what a tool did last time. Experience cannot
 *       be "wrong" the way a fact can; it can only be more or less
 *       TRANSFERABLE. Crucially, experience made <em>elsewhere</em> is the
 *       normal case — a refactoring lesson stays valuable after the code it was
 *       learned on is deleted. So it is retrieved by MEANING, ranked (never
 *       gated) with equality only as a boost, capped, and rendered as ANALOGY
 *       with its basis and provenance in words. The agent judges transfer.</dd>
 * </dl>
 *
 * <p>The consequence that decides the split: <b>the symbol anchor is a
 * CRITERION for a fact and mere PROVENANCE for experience.</b> Requiring an
 * experience entry's anchor to resolve would delete exactly the elsewhere-made
 * experience the store exists to carry.</p>
 */
public enum KnowledgeKind {

    /** About code at an address: hard-gated, terminal, may go stale. */
    FACT,

    /** About a situation: meaning-retrieved, ranked, capped, advisory. */
    EXPERIENCE;

    /** Types that state something about code — and only then, with an anchor. */
    private static final Set<String> ADDRESS_BOUND_TYPES =
        Set.of("domain_fact", "api_contract");

    /**
     * Classify a stored entry.
     *
     * <p>An entry is a FACT only when its type is address-bound AND it actually
     * carries a resolvable code anchor. That second condition is what handles
     * the border case Harald named: a {@code domain_fact} may be business
     * knowledge rather than a statement about code ("the opening range is the
     * first fifteen minutes"), and such an entry has no address to check. It is
     * situation knowledge and belongs on the experience side — gating it on a
     * symbol that does not exist would silently delete it from recall.</p>
     */
    public static KnowledgeKind of(StoredEntry e) {
        if (e == null) {
            return EXPERIENCE;
        }
        String type = e.type() == null ? "" : e.type().toLowerCase(Locale.ROOT);
        if (!ADDRESS_BOUND_TYPES.contains(type)) {
            return EXPERIENCE;
        }
        // Address-bound TYPE, but is there an address? Only a Java-resolvable
        // anchor can be checked against current code; anything else has no
        // address to be wrong about.
        return e.isJavaResolvable() ? FACT : EXPERIENCE;
    }

    public boolean isFact() {
        return this == FACT;
    }

    public boolean isExperience() {
        return this == EXPERIENCE;
    }
}
