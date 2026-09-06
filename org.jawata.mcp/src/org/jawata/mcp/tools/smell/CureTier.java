package org.jawata.mcp.tools.smell;

import java.util.ArrayList;
import java.util.List;

/**
 * THE TIER IS DERIVED, NOT ASSIGNED — Sprint 28d Stage 11a.
 *
 * <p>The cure model: whether a finding's answer is <b>RUN</b> (do this) or
 * <b>CONSIDER</b> (a design decision) is a function of the declared routes and the
 * operations that actually exist.
 *
 * <p><b>The two were called PERFORM and ADVISE until 2026-09-06.</b> The words are
 * Harald's, and they are step 1 of the tier reversal: <i>"You have 3 alternatives.
 * Pick the most appropriate one and perform."</i> The RULES below are unchanged by
 * this step and still derive from the COUNT of runnable routes — which is the thing
 * being overturned, in step 3, not here. Renaming first keeps the two changes
 * separately revertible. A tier written into a table
 * would drift the day an operation shipped or was renamed; a derived tier
 * cannot, because it is recomputed from the table and the registry on every
 * ask.</p>
 *
 * <h2>The rules, in the order they decide</h2>
 * <ol>
 *   <li>No cure declared — CONSIDER. <b>Zero cures is a normal state</b>, not a
 *       defect: a cure is unfillable until its steps exist.</li>
 *   <li>Cures declared, none runnable — CONSIDER: the cures name designs and
 *       nothing automates them.</li>
 *   <li>A declared step is NOT in the registry — CONSIDER, and the step is
 *       NAMED. Never silently narrowed to the remaining routes: a table
 *       declaring a step that does not exist is a defect to surface, and
 *       narrowing would hide it exactly the way an absent field reading as
 *       empty hid three fields at Stage 10.</li>
 *   <li><b>AT LEAST ONE runnable route, every step registered — RUN, carrying
 *       the whole ranked list.</b></li>
 * </ol>
 *
 * <h2>Rule 5 is gone, and it is what this class existed to get wrong</h2>
 *
 * <p>There used to be a fifth rule: <i>several runnable routes — ADVISE, because
 * nothing mechanical chooses between them.</i> It read as caution and it was a
 * measure of COVERAGE wearing certainty's clothes — every fix the product gained
 * demoted the smell that gained it, so a smell with three good answers instructed
 * LESS than one with a single answer. Measured three times before it was
 * overturned: routing row 61 to {@code cqs} would have cost that smell its
 * instruction, routing row 59 to {@code type_code} the same, and thirteen rows sat
 * unrouted with the tier rule as the written reason.</p>
 *
 * <p>Harald, 2026-09-06: <i>"But this is software development. There are always
 * degrees of freedom... You can say: You have 3 alternatives. Pick the most
 * appropriate one and perform"</i> — and on the thirteen: <i>"if you leave 13 on
 * the street on your discretion, that's a flaw"</i>. So the verdict is RUN whenever
 * anything is runnable, the alternatives travel as a RANKED LIST, and each carries
 * the sentence that tells it from its neighbours. Nothing mechanical chooses — the
 * AGENT chooses, which is what it is for, and what it needs to choose with is a
 * discriminator rather than a demotion.</p>
 *
 * <p>The partial-route rule went with it. A route that commonly declines used to
 * pull its kind down to ADVISE; that measurement is now the route's DISCRIMINATOR,
 * so the reader gets the same number and still gets an instruction.</p>
 *
 * <p>The registry parameter is the falsifiable seam, in the same style as
 * {@link CureLookup#audit(org.jawata.mcp.knowledge.ExperienceStore, List)}: a
 * derivation that has only ever seen the real registry cannot show its
 * missing-step branch works. The default registry is the front door's own
 * published kind list, through an accessor — never a copy.</p>
 */
public final class CureTier {

    /** The two answers the model derives — the split dossier item 11 tracked. */
    public enum Tier {
        /** One runnable route, every step registered: run it. */
        RUN,
        /** A design decision: alternatives, design-only cures, or nothing declared. */
        CONSIDER
    }

    /**
     * One derivation: the kind asked about, the tier, the RANKED runnable cures
     * when the tier is {@link Tier#RUN} (empty otherwise), and the reason — which
     * consider-cause applied, stated so a reader can tell a design decision from a
     * mis-spelled table row.
     *
     * <p>{@code runnable} replaced a single {@code recipe} when rule 5 went. A lone
     * recipe could only be filled when there was exactly one, which is why several
     * routes had to mean "no instruction": the shape could not carry them. The list
     * is in the table's declaration order, which is the author's ranking.</p>
     */
    public record Derivation(String kind, Tier tier, List<CureCatalog.Cure> runnable,
                             String reason) {

        /** The first ranked step, or null when nothing is runnable — for callers that want one. */
        public String recipe() {
            return runnable.isEmpty() ? null : runnable.get(0).recipe();
        }
    }

    private CureTier() {
    }

    /**
     * Derive against the real registry — every operation the product publishes.
     *
     * <p>Sprint 28d-rescue (P1) changed what "the real registry" means, and nothing
     * else here. It used to be one front door's kind list, which silently made every
     * standalone operation unnameable as a cure step; it is now
     * {@link org.jawata.mcp.refactoring.OperationRegistry}, which every tool feeds as
     * it registers. The derivation rules below are untouched — the seam was already a
     * parameter, which is why this is a one-line change rather than a rewrite.</p>
     */
    public static Derivation derive(String kind) {
        // THE UNION, and each half is there for a different failure.
        //
        // The registry alone is not enough: it is a process-wide singleton that fills
        // when the application registers its tools, so anything reaching this before
        // that — a unit test, an embedding with no tool registry, or a JVM where some
        // other test registered two fabricated tools — sees an incomplete set and
        // derives ADVISE for kinds whose route is perfectly real, with a reason about
        // plumbing. A missing answer must not read as a negative one.
        //
        // The pattern kinds alone are not enough either: that was the pre-P1 read, and
        // it is exactly what made `move_method` and `encapsulate_field` unnameable.
        //
        // The union has neither failure. It cannot make a real step look absent, and it
        // still refuses a step nothing anywhere publishes — which is the only thing
        // this derivation is entitled to conclude.
        java.util.Set<String> published = new java.util.LinkedHashSet<>(
            org.jawata.mcp.tools.RefactorToPatternTool.patternKinds());
        published.addAll(org.jawata.mcp.refactoring.OperationRegistry.theRegistry().all());
        return derive(kind, List.copyOf(published));
    }

    /** Derive against a caller-supplied registry — the seam the control uses. */
    public static Derivation derive(String kind, List<String> registry) {
        List<CureCatalog.Cure> declared = CureCatalog.curesFor(kind);
        if (declared.isEmpty()) {
            return new Derivation(kind, Tier.CONSIDER, List.of(),
                "no cure declared — a normal state, not a defect");
        }
        List<CureCatalog.Cure> runnable = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (CureCatalog.Cure c : declared) {
            if (c.recipe() == null) {
                continue;
            }
            runnable.add(c);
            if (!registry.contains(c.recipe())) {
                missing.add(c.recipe());
            }
        }
        if (runnable.isEmpty()) {
            return new Derivation(kind, Tier.CONSIDER, List.of(),
                "the declared cures name designs; nothing automates them");
        }
        if (!missing.isEmpty()) {
            return new Derivation(kind, Tier.CONSIDER, List.of(),
                "step(s) not in the operation registry: " + String.join(", ", missing));
        }
        // ONE RULE WHERE THERE WERE TWO. The count of runnable routes decides NOTHING
        // any more: it is a measure of how much the product can do, and the old rule
        // read it as doubt. Whatever is runnable is handed over, ranked, and the agent
        // picks — which is why every cure past the first has to carry a discriminator,
        // enforced at load time by CureCatalog's INVARIANT 3.
        return new Derivation(kind, Tier.RUN, List.copyOf(runnable),
            runnable.size() == 1
                ? "one runnable route, every step registered"
                : runnable.size() + " runnable routes, every step registered — ranked,"
                    + " each with what tells it from the others; pick the one that fits"
                    + " and perform it");
    }
}
