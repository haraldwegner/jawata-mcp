package org.jawata.mcp.tools.smell;

import org.jawata.mcp.tools.RefactorToPatternTool;

import java.util.ArrayList;
import java.util.List;

/**
 * THE TIER IS DERIVED, NOT ASSIGNED — Sprint 28d Stage 11a.
 *
 * <p>The cure model (ARCHITECTURE-28d.md, "What the catalogue is", ruled built
 * 2026-08-31): whether a finding's answer is <b>PERFORM</b> (one runnable route,
 * run it) or <b>ADVISE</b> (a design decision) is a function of the declared
 * routes and the operations that actually exist. A tier written into a table
 * would drift the day an operation shipped or was renamed; a derived tier
 * cannot, because it is recomputed from the table and the registry on every
 * ask.</p>
 *
 * <h2>The rules, in the order they decide</h2>
 * <ol>
 *   <li>No cure declared — ADVISE. <b>Zero cures is a normal state</b>, not a
 *       defect: a cure is unfillable until its steps exist.</li>
 *   <li>Cures declared, none runnable — ADVISE: the cures name designs and
 *       nothing automates them.</li>
 *   <li>A declared step is NOT in the registry — ADVISE, and the step is
 *       NAMED. Never silently narrowed to the remaining routes: a table
 *       declaring a step that does not exist is a defect to surface, and
 *       narrowing would hide it exactly the way an absent field reading as
 *       empty hid three fields at Stage 10.</li>
 *   <li>Exactly one runnable route, its step registered — PERFORM, naming the
 *       step.</li>
 *   <li>Several runnable routes — ADVISE: nothing mechanical chooses between
 *       them, and a derivation that picked one anyway would have invented a
 *       preference no table declares.</li>
 * </ol>
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
        PERFORM,
        /** A design decision: alternatives, design-only cures, or nothing declared. */
        ADVISE
    }

    /**
     * One derivation: the kind asked about, the tier, the single runnable step
     * when the tier is {@link Tier#PERFORM} (null otherwise), and the reason —
     * which advise cause applied, stated so a reader can tell a design decision
     * from a mis-spelled table row.
     */
    public record Derivation(String kind, Tier tier, String recipe, String reason) {
    }

    private CureTier() {
    }

    /** Derive against the real registry — the front door's published kinds. */
    public static Derivation derive(String kind) {
        return derive(kind, RefactorToPatternTool.publishedKinds());
    }

    /** Derive against a caller-supplied registry — the seam the control uses. */
    public static Derivation derive(String kind, List<String> registry) {
        List<CureCatalog.Cure> declared = CureCatalog.curesFor(kind);
        if (declared.isEmpty()) {
            return new Derivation(kind, Tier.ADVISE, null,
                "no cure declared — a normal state, not a defect");
        }
        List<String> runnable = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (CureCatalog.Cure c : declared) {
            if (c.recipe() == null) {
                continue;
            }
            runnable.add(c.recipe());
            if (!registry.contains(c.recipe())) {
                missing.add(c.recipe());
            }
        }
        if (runnable.isEmpty()) {
            return new Derivation(kind, Tier.ADVISE, null,
                "the declared cures name designs; nothing automates them");
        }
        if (!missing.isEmpty()) {
            return new Derivation(kind, Tier.ADVISE, null,
                "step(s) not in the operation registry: " + String.join(", ", missing));
        }
        if (runnable.size() == 1) {
            return new Derivation(kind, Tier.PERFORM, runnable.get(0),
                "one runnable route, every step registered");
        }
        return new Derivation(kind, Tier.ADVISE, null,
            runnable.size() + " runnable routes and nothing mechanical chooses"
                + " between them — a design decision");
    }
}
