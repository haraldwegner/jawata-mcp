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

    /**
     * Types that state something about code — and only then, with an anchor.
     *
     * <p><b>Sprint 28f Stage 7 added {@code job}, and its absence was the same shape the
     * stage has now met three times.</b> A job says what ONE MEMBER at ONE ADDRESS is for,
     * and rewriting that member rewrites the row — which is this enum's own definition of a
     * fact, word for word. Classified as experience it would have been offered as a capped
     * ANALOGY for unrelated code (a job about {@code Foo#bar} is noise beside
     * {@code Other#baz}), and worse: {@code ExperienceRetrieval.present} strips the
     * {@code stale} flag from an experience and replaces it with "learned here; the symbol
     * no longer exists", so a job whose member had been deleted would have read as
     * provenance that no longer resolves rather than as a job pointing at nothing. The
     * location this stage renders would have been dressed as history.</p>
     *
     * <p><b>{@code area} is deliberately NOT here, and the reason is the border case below
     * rather than an oversight.</b> An area is scoped to a PACKAGE and carries no symbol
     * anchor, so there is no address to check it against; by the same rule that keeps a
     * business {@code domain_fact} on the experience side, it belongs there. This set and
     * {@link KnowledgeLane#CODE_TYPES} therefore differ by one entry ON PURPOSE — they
     * answer different questions, which is the distinction {@code KnowledgeLane}'s own
     * javadoc establishes.</p>
     */
    private static final Set<String> ADDRESS_BOUND_TYPES =
        Set.of("domain_fact", "api_contract", "job");

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
        return hasJavaAnchor(e) ? FACT : EXPERIENCE;
    }

    /**
     * Is there an anchor here, and may JDT judge it?
     *
     * <p><b>Sprint 28f Stage 7 — this used to be {@code isJavaResolvable()} alone, which
     * does not ask the first half.</b> That method reads the LANGUAGE and nothing else, and
     * its own javadoc says so: null or blank counts as Java, because a row carrying
     * {@code com.example.Type#member} has a Java anchor whoever wrote it. So an
     * address-bound row with NO anchor at all — the business {@code domain_fact} the
     * paragraph above names as the border case, "the opening range is the first fifteen
     * minutes" — was classified FACT, which is the outcome that paragraph exists to
     * prevent. The comment described the check; the check asked half of it.</p>
     *
     * <p>What it cost while it stood: such a row is hard-gated, so a cue it does not fit
     * DROPS it instead of offering it as an analogy — it was excluded from exactly the
     * ranked path an anchorless entry can only ever be reached by. Fixed here rather than
     * left because Stage 7 adds {@code job} to the set above, and adding a type to a set
     * whose gate is documented-but-unimplemented inherits the defect rather than meeting
     * it.</p>
     */
    private static boolean hasJavaAnchor(StoredEntry e) {
        String fqn = e.symbolFqn();
        return fqn != null && !fqn.isBlank() && e.isJavaResolvable();
    }

    public boolean isFact() {
        return this == FACT;
    }

    public boolean isExperience() {
        return this == EXPERIENCE;
    }
}
