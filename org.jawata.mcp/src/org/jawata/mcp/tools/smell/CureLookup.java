package org.jawata.mcp.tools.smell;

import org.jawata.mcp.knowledge.CatalogueAddresses;
import org.jawata.mcp.knowledge.ExperienceStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE CURE LOOKUP — a finding's cure, RESOLVED from the catalogue.
 *
 * <p>Sprint 28d arch step 4. Before this, a cure was a sentence a detector
 * built: three plan-kind names concatenated into a hint. The names were true and
 * nothing behind them was checked, so a cure naming a design the store does not
 * hold read exactly like one it does. This joins the three parts that make a
 * cure checkable:</p>
 *
 * <ol>
 *   <li>{@link CureCatalog} — WHICH design cures this kind, by catalogue key;</li>
 *   <li>{@link CatalogueAddresses} — the address that key resolves to, read off
 *       a row, or nothing;</li>
 *   <li>{@link RecipeCatalog} / {@link OcpCure} — the hardcoded map, which
 *       survives as the <b>STATED FALLBACK</b> and never as a silent default.</li>
 * </ol>
 *
 * <h2>The three answers this can give, and why they must stay distinct</h2>
 * <ul>
 *   <li><b>Resolved</b> — a live row carries the key; the cure has an address a
 *       reader can open.</li>
 *   <li><b>Unresolved, catalogue present</b> — rows exist, none carries this
 *       key. The cure is named and has NO address, and that is reported. It is
 *       not filled in from the slug, and no fallback is claimed: the store is
 *       there and it answered.</li>
 *   <li><b>Unresolved, namespace absent</b> — a registered namespace holds zero
 *       rows. That is a FAULT, not an answer, so the namespace is NAMED and the
 *       hardcoded recipes are handed over labelled as the degradation they are.
 *       Collapsing this into the case above is how "there is no catalogue"
 *       started reading like "the catalogue had nothing for you".</li>
 * </ul>
 */
public final class CureLookup {

    /** A cure that resolved: what runs it (may be null), and where its design lives. */
    public record ResolvedCure(String recipe, String operation, String namespace,
                               String address) {
    }

    /**
     * The answer for one smell kind.
     *
     * @param kind             the smell asked about
     * @param resolved         cures whose design was found, best-first
     * @param unresolved       declared cure keys no live row carries
     * @param absentNamespaces every registered namespace holding ZERO live rows
     *                         — reported ALWAYS, whether or not it explains this
     *                         particular miss, because a namespace that vanished
     *                         is a fact about the store and not about the query
     * @param degradation      why this answer is not the store's, or null when it is
     * @param fallbackRecipes  the hardcoded plan kinds, present ONLY alongside a
     *                         non-null degradation — a fallback nobody declared
     *                         is indistinguishable from an answer
     */
    public record Cures(String kind, List<ResolvedCure> resolved, List<String> unresolved,
                        List<String> absentNamespaces, String degradation,
                        List<String> fallbackRecipes) {

        /**
         * The cure as a sentence a finding can carry — and it always says WHERE
         * it came from.
         *
         * <p>A resolved cure names the plan kind AND the address behind it; an
         * unresolved one says the address is missing rather than dropping the
         * subject; a degraded one states the degradation before it hands the
         * hardcoded recipes over. The plan kinds appear in every branch under
         * the same {@code refactor_to_pattern kind=} spelling, so a reader does
         * not have to know which branch produced a message to find the runnable
         * answer in it.</p>
         */
        public String hint() {
            StringBuilder b = new StringBuilder();
            if (!resolved.isEmpty()) {
                List<String> names = new ArrayList<>();
                List<String> addresses = new ArrayList<>();
                for (ResolvedCure c : resolved) {
                    names.add(c.recipe() == null ? c.operation() : c.recipe());
                    addresses.add(c.operation() + " <" + c.address() + ">");
                }
                b.append(" refactor_to_pattern kind=").append(String.join(" / ", names))
                 .append(" — design(s) in the catalogue: ")
                 .append(String.join(", ", addresses)).append('.');
            }
            if (!unresolved.isEmpty()) {
                b.append(" NO CATALOGUE ADDRESS for: ").append(String.join(", ", unresolved))
                 .append(" — the cure is named and the catalogue holds no entry for it.");
            }
            if (degradation != null) {
                b.append(' ').append(degradation);
                if (!fallbackRecipes.isEmpty()) {
                    b.append(" Hardcoded fallback: refactor_to_pattern kind=")
                     .append(String.join(" / ", fallbackRecipes)).append('.');
                }
            }
            return b.toString();
        }
    }

    private CureLookup() {
    }

    /**
     * Resolve every declared cure for {@code kind} against an already-built
     * index.
     *
     * <p>Indexing walks every row, so building it per finding would be a full
     * scan per finding. A detector builds it once per file and calls this.</p>
     *
     * <p>A null store yields the SAME answer as an empty one — every namespace
     * absent, the degradation stated — rather than a different code path, so a
     * detector that reached no store says so instead of quietly printing the
     * hardcoded hint. That silent default is what this class exists to remove,
     * and it is what production shipped until the store was threaded into the
     * detector catalog.</p>
     */
    public static Cures forKind(CatalogueAddresses addresses, String kind) {
        List<CureCatalog.Cure> declared = CureCatalog.curesFor(kind);
        List<ResolvedCure> resolved = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (CureCatalog.Cure c : declared) {
            CatalogueAddresses.Address a = addresses.address(c.operation());
            if (a == null) {
                unresolved.add(c.operation());
            } else {
                // The address is the ROW's own source_ref. Nothing on this line
                // builds a string from c.operation() — that is the property.
                resolved.add(new ResolvedCure(c.recipe(), c.operation(), a.namespace(),
                    a.sourceRef()));
            }
        }

        List<String> absent = addresses.absentNamespaces();
        String degradation = null;
        List<String> fallback = List.of();
        if (!unresolved.isEmpty() && !absent.isEmpty()) {
            degradation = "DEGRADED — catalogue namespace(s) " + String.join(", ", absent)
                + " hold ZERO rows, so this cure could not be resolved from the store."
                + " What follows is the hardcoded map, not the catalogue.";
            fallback = RecipeCatalog.recipesFor(kind);
        }
        return new Cures(kind, List.copyOf(resolved), List.copyOf(unresolved),
            absent, degradation, List.copyOf(fallback));
    }

    /**
     * What a re-resolution sweep found.
     *
     * @param declared          distinct cure keys checked
     * @param resolved          how many still point at a live row
     * @param unresolved        how many do NOT — the number that must be zero,
     *                          and must be able to be non-zero
     * @param unresolvedOperations the keys themselves, NAMED: a count with no
     *                          names cannot be acted on, and "3 broke" is the
     *                          same output whether the fork renamed three
     *                          patterns or somebody mistyped one table row
     * @param absentNamespaces  registered namespaces holding zero live rows
     * @param authorities       namespace &rarr; where its content comes from,
     *                          because a FOREIGN pin moving is the reason this
     *                          sweep exists and a report that omits the pin
     *                          cannot say which authority moved
     */
    public record Audit(int declared, int resolved, int unresolved,
                        List<String> unresolvedOperations, List<String> absentNamespaces,
                        Map<String, String> authorities) {

        /** True when every declared cure still resolves. */
        public boolean clean() {
            return unresolved == 0;
        }
    }

    /**
     * RE-RESOLVE EVERY DECLARED CURE against the store as it stands.
     *
     * <p><b>Why this is owed.</b> {@code java-design-patterns} is a FOREIGN
     * authority pinned to somebody else's commit — {@link
     * org.jawata.mcp.knowledge.CatalogueSource} says so, and says that a foreign
     * source's addresses must be re-resolved when the pin moves. Moving the pin
     * can rename or drop a pattern under us; the declaration here would go on
     * naming it, and every cure for the affected kinds would quietly lose its
     * address. Nothing would fail — an unresolved cure just stops carrying an
     * address — which is precisely why the check has to be run rather than
     * waited for.</p>
     */
    public static Audit audit(ExperienceStore store) {
        return audit(store, CureCatalog.declaredOperations());
    }

    /**
     * The sweep over a CALLER-SUPPLIED declaration — the seam that makes the
     * check falsifiable.
     *
     * <p>A check that has never fired and a corpus with nothing to find produce
     * identical output, so a zero from {@link #audit(ExperienceStore)} alone is
     * not evidence the instrument works. Handing it a deliberately broken
     * declaration is how the non-zero half gets produced; the repaired run is
     * the other half of the pair.</p>
     */
    public static Audit audit(ExperienceStore store, List<String> declaredOperations) {
        CatalogueAddresses addresses = CatalogueAddresses.of(store);
        List<String> broken = new ArrayList<>();
        int ok = 0;
        for (String operation : declaredOperations) {
            if (addresses.resolves(operation)) {
                ok++;
            } else {
                broken.add(operation);
            }
        }
        Map<String, String> authorities = new LinkedHashMap<>(CatalogueAddresses.authorities());
        return new Audit(declaredOperations.size(), ok, broken.size(), List.copyOf(broken),
            addresses.absentNamespaces(), Map.copyOf(authorities));
    }
}
