package org.jawata.mcp.knowledge;

import java.util.List;

/**
 * WHERE ONE ORIGIN'S MATERIAL LIVES — data, and nothing else.
 *
 * <h2>Why this is a record and not an interface (Sprint 28d Stage 6 / S6)</h2>
 *
 * <p>{@code CatalogueSource} was an interface carrying {@code seed}, so every
 * source wrote its own lifecycle. That is an invitation, and it had already been
 * accepted wrongly: one source's {@code seed} skipped supersession entirely and
 * nothing in the type system noticed, shipping both the duplicate-on-edit defect
 * and the orphan defect the other source had already fixed.</p>
 *
 * <p>The fix is not a shared helper the sources may call. <b>A step a source can
 * decline is a step a source will decline</b> — an opt-in leaves each source free
 * to skip half of it, which is the same invitation one level down. So a source
 * stops being a thing with behaviour and becomes a record of where its material
 * is: there is no method here to implement wrongly, because there is no method.</p>
 *
 * <h2>What is deliberately NOT a component</h2>
 *
 * <p><b>{@code prefix} is DERIVED, not stored.</b> It is exactly
 * {@code catalogue:<namespace>/}, and storing both would let them disagree — one
 * fact, one home. Every ownership question in the lane keys on this string, so a
 * namespace and a prefix that drifted apart would make a row owned by nobody.</p>
 *
 * <p><b>{@code authority} is NOT a component, and that is a measured decision.</b>
 * Both origins derive it from their own manifest — the fork from its
 * {@code pinned_commit}, ours from its {@code authority} field — so a static
 * value here would be wrong for the fork the moment the pin moves, and would
 * report a stale pin without saying so. It cannot be resolved eagerly either:
 * {@link CatalogueSources#all()} is called on hot paths (the {@code stats} block,
 * the address renderer) and is cheap by contract, so filling an authority per
 * call would put a megabyte of JSON parsing behind each one. The authority is
 * therefore READ FROM THE MANIFEST when something actually needs it, and this
 * record carries only the pointer to where that manifest is.</p>
 *
 * @param namespace        the origin's identity, and the whole of its address
 *     prefix. {@code java-design-patterns} for the pinned fork,
 *     {@code jawata-samples} for our own specimens
 * @param manifestResource the classpath resource holding this origin's rows —
 *     the thing that is read, so that the authority and the row set come from
 *     one place rather than from a constant and a file that can disagree
 * @param workspaceRoot    the repository-relative directory whose subdirectories
 *     are this origin's slugs, or an EMPTY string for an origin whose tree is not
 *     in this workspace at all (the fork is a separate checkout). This is what
 *     makes "does this address open in the workspace?" computable: nothing mapped
 *     a namespace onto a directory before, so the question could only be asked of
 *     the store, which answers whether a row carries a key — never whether the
 *     address it names exists
 * @param retiredPrefixes  spellings this origin USED to own. They cannot simply
 *     be forgotten: on a rename the old rows fall out of every prefix-keyed lane
 *     at once — invisible, still live, still answering with an address nothing
 *     backs — so {@link CatalogueSeeder} removes them on the next seed. An
 *     entry here is PERMANENT; an install upgrading from an old version years
 *     from now needs it exactly as much as one upgrading today
 */
public record CatalogueOrigin(
        String namespace,
        String manifestResource,
        String workspaceRoot,
        List<String> retiredPrefixes) {

    /**
     * The origin enforces its own validity — added 2026-08-28, after two audit
     * rounds refused a TEST that tried to police this from outside.
     *
     * <p>The collision these guard against was previously only <i>detectable</i>, by
     * an assertion in a contract test. That assertion could not be given a failing
     * input without constructing exactly the origin it forbids, so the control
     * written for it reduced twice to a comparison of compile-time constants — true
     * for every possible input, exercising no code. <b>An invariant a test has to
     * mirror is an invariant in the wrong place.</b> Here the collision is
     * unconstructible, which is strictly stronger than detectable, and the control
     * has something real to assert: that construction is refused.</p>
     */
    public CatalogueOrigin {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException(
                "a catalogue origin needs a namespace — it is the whole of its address"
                    + " prefix, and it is how a degradation line names WHICH catalogue is"
                    + " empty. Blank makes that line read 'catalogue  holds zero rows'.");
        }
        if (namespace.indexOf('/') >= 0) {
            throw new IllegalArgumentException(
                "a namespace must not contain '/'. The prefix is 'catalogue:<namespace>/',"
                    + " so a namespace carrying the separator yields a prefix that SWALLOWS"
                    + " another's: 'java' swallows 'java/design'. Ownership resolves by FIRST"
                    + " match, so the swallowed origin would be permanently unreachable while"
                    + " appearing registered, and its rows would be swept as orphans by the"
                    + " origin that claims none of them. Sibling namespaces cannot collide —"
                    + " the trailing slash prevents it — so this separator is the only"
                    + " collision that survives. Got: '" + namespace + "'");
        }
        retiredPrefixes = List.copyOf(retiredPrefixes);
        String live = "catalogue:" + namespace + "/";
        for (String retired : retiredPrefixes) {
            if (live.startsWith(retired) || retired.startsWith(live)) {
                throw new IllegalArgumentException(
                    "origin '" + namespace + "' retires '" + retired + "', which overlaps its"
                        + " OWN live prefix '" + live + "'. The retired-prefix migration"
                        + " REMOVES every row under a retired prefix WITHOUT the"
                        + " completeness guard — deliberately, because a retired spelling has"
                        + " no current input to be complete against — so this origin would"
                        + " retire its entire catalogue on the next boot.");
            }
        }
    }

    /**
     * The {@code source_ref} prefix every row of this origin carries.
     *
     * <p>Derived from the namespace rather than stored beside it. This is the
     * string {@code owning()}, {@code isCatalogue()}, the seeder's live-row index
     * and the reseed lane rule all key on, so there must be exactly one way to
     * produce it.</p>
     */
    public String prefix() {
        return "catalogue:" + namespace + "/";
    }

    /** True when this origin's material sits inside this repository. */
    public boolean inWorkspace() {
        return workspaceRoot != null && !workspaceRoot.isBlank();
    }
}
