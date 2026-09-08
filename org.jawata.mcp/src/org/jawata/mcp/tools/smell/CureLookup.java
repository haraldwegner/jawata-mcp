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
 *   <li>{@link CureCatalog#recipesFor} — the same table's runnable half, which
 *       survives as the <b>STATED FALLBACK</b> and never as a silent default.
 *       It was two further classes until S7; they held the same mappings a
 *       second and third time, so the fallback could disagree with the cure it
 *       was falling back from.</li>
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
     * @param degradation      why this answer is not the store's, or null when it is.
     *                         When a namespace holding ZERO live rows is what caused
     *                         the miss, this sentence NAMES it — that is where the
     *                         absent namespace reaches a reader, and it is the only
     *                         place, because a field nothing reads is not a report
     * @param fallbackRecipes  the hardcoded plan kinds, present ONLY alongside a
     *                         non-null degradation — a fallback nobody declared
     *                         is indistinguishable from an answer
     */
    public record Cures(String kind, List<ResolvedCure> resolved, List<String> unresolved,
                        String degradation, List<String> fallbackRecipes) {

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
        /**
         * HOW A READER ACTUALLY CALLS IT.
         *
         * <p>Every branch used to spell the invocation {@code refactor_to_pattern
         * kind=X}, which was true while every runnable cure happened to be a pattern
         * kind. P1 widened what a cure may name, and the sentence did not follow: a
         * feature-envy finding would have said "run refactor_to_pattern
         * kind=move_method", a call that does not exist. A standalone operation is
         * invoked by its own name.</p>
         */
        static String invocationOf(String operation) {
            // THE REGISTRY ANSWERS THIS NOW, not a hardcoded front door. Stage 1 folded
            // `move_method` into `move kind=method`, so the set of operations reached
            // through a front door is no longer "the pattern kinds" — and a rendering
            // that knows only one front door would have printed a bare `method`, which
            // names nothing a reader can call.
            String rendered =
                org.jawata.mcp.refactoring.OperationRegistry.theRegistry().invocationOf(operation);
            if (rendered != null && !rendered.equals(operation)) {
                return rendered;
            }
            // The registry did not resolve it: either the operation IS a tool name, or
            // nothing has registered yet. The second case is real — a unit test that
            // never built the application still renders these sentences — so the pattern
            // front door's own list stays as the standing fallback, exactly as CureTier
            // reads the union rather than the registry alone.
            return org.jawata.mcp.tools.RefactorToPatternTool.patternKinds().contains(operation)
                ? "refactor_to_pattern kind=" + operation
                : operation;
        }

        public String hint() {
            return hint(null);
        }

        /**
         * The same sentence, judged against ONE FINDING's address.
         *
         * <p>{@link #hint()} passes null and means "no finding in hand": it renders what
         * the KIND can offer, which is what every existing caller asks for and what every
         * pinned wording in {@code CureLookupTest} is about. Passing an address adds the
         * one question a per-kind answer cannot ask — can THIS finding be the thing the
         * cure is run from — and that question has to be asked per finding, because two
         * findings of one kind can differ in exactly that.</p>
         */
        public String hint(org.jawata.mcp.models.CodeAddress address) {
            StringBuilder b = new StringBuilder();
            if (!resolved.isEmpty()) {
                List<String> names = new ArrayList<>();
                List<String> addresses = new ArrayList<>();
                for (ResolvedCure c : resolved) {
                    names.add(c.recipe() == null ? c.operation() : c.recipe());
                    addresses.add(c.operation() + " <" + c.address() + ">");
                }
                b.append(' ').append(String.join(" / ", names.stream().map(Cures::invocationOf).toList()))
                 .append(" — design(s) in the catalogue: ")
                 .append(String.join(", ", addresses)).append('.');
            }
            List<String> noDesign = new ArrayList<>();
            for (CureCatalog.Cure c : CureCatalog.curesFor(kind)) {
                if (c.operation() == null && c.recipe() != null) {
                    noDesign.add(c.recipe());
                }
            }
            if (!noDesign.isEmpty()) {
                // A runnable step with no catalogue row: the fix is offered and no
                // address is claimed. Distinct from an address that WAS declared and did
                // not resolve, which is a defect and says so in the branch below.
                b.append(' ').append(String.join(" / ", noDesign.stream()
                     .map(Cures::invocationOf).toList()))
                 .append(" — no catalogue design for this one; the operation is the cure.");
            }
            if (!unresolved.isEmpty()) {
                b.append(" NO CATALOGUE ADDRESS for: ").append(String.join(", ", unresolved))
                 .append(" — the cure is named and the catalogue holds no entry for it.");
            }
            if (degradation != null) {
                b.append(' ').append(degradation);
                if (!fallbackRecipes.isEmpty()) {
                    // Through invocationOf like every other branch. This one was missed,
                    // and it is reachable: it fires when the catalogue namespace holds
                    // zero rows, which is exactly when a reader most needs the sentence
                    // to be right.
                    b.append(" Hardcoded fallback: ")
                     .append(String.join(" / ", fallbackRecipes.stream()
                         .map(Cures::invocationOf).toList())).append('.');
                }
            }
            // Stage 11a — the DERIVED tier, appended so every branch above keeps
            // its pinned wording. A kind with nothing declared stays BLANK: the
            // blank is a contract (OcpDetector's fallback branch keys on it), and
            // a tier sentence on an empty answer would flip that branch for the
            // one case where the table has nothing to derive from.
            if (b.length() > 0) {
                CureTier.Derivation tier = CureTier.derive(kind);
                // THE RENAME DOES NOT REACH THESE, which is the whole reason step 1 has a
                // gate of its own: rename_symbol moved twenty references across four files
                // and left the two literals a reader actually SEES, one line below the
                // constant it had just renamed. A string is invisible to every
                // reference-updating engine — the same property that let the operation
                // allowlist name a door that no longer existed.
                if (tier.tier() == CureTier.Tier.RUN && address != null
                        && !address.complete()) {
                    // FAIL VISIBLE. The cure is runnable and THIS finding cannot be what
                    // runs it: the detector named something no door can resolve — a bare
                    // member name rather than a qualified one. Rendering RUN would hand
                    // over an instruction that fails at the door, and the caller would
                    // have no way to tell whose gap it was. So it says CONSIDER and NAMES
                    // THE DETECTOR, because that is who has to fix it.
                    b.append(" TIER: CONSIDER — ").append(kind)
                     .append(" declares a runnable cure, but this finding carries no")
                     .append(" address it can be run from (symbol='")
                     .append(String.valueOf(address.symbol()))
                     .append("'). That is the ").append(kind)
                     .append(" detector's gap, not the cure's.");
                } else if (tier.tier() != CureTier.Tier.RUN) {
                    b.append(" TIER: CONSIDER — ").append(tier.reason()).append('.');
                } else if (tier.runnable().size() == 1) {
                    CureCatalog.Cure only = tier.runnable().get(0);
                    b.append(" TIER: RUN — run ").append(invocationOf(only.recipe()));
                    // A DISCRIMINATOR IS RENDERED EVEN WITH ONE CURE. It is not required
                    // there — nothing to tell apart — but where it exists it carries a
                    // caveat the reader needs, and `loops` is the case: its measurement
                    // used to arrive as a demotion and now arrives beside the step.
                    if (only.discriminator() != null) {
                        b.append(" — ").append(only.discriminator());
                    }
                    b.append('.');
                } else {
                    b.append(" TIER: RUN — ").append(tier.runnable().size())
                     .append(" alternatives; pick the one that fits and perform it:");
                    int rank = 0;
                    for (CureCatalog.Cure c : tier.runnable()) {
                        b.append(" (").append(++rank).append(") ")
                         .append(invocationOf(c.recipe()));
                        if (c.discriminator() != null) {
                            b.append(" — ").append(c.discriminator());
                        }
                        b.append(rank == tier.runnable().size() ? "." : ";");
                    }
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
            if (c.operation() == null) {
                // Declared with no design to read — see CureCatalog's note on the four
                // widened routes. NOT unresolved: nothing was claimed and lost, so it
                // must not be counted as a broken mapping. hint() reads it back from
                // the table, which keeps this record's shape — and the control test
                // that constructs it — untouched.
                continue;
            }
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
            fallback = CureCatalog.recipesFor(kind);
        }
        return new Cures(kind, List.copyOf(resolved), List.copyOf(unresolved),
            degradation, List.copyOf(fallback));
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
     * @param addresses         operation &rarr; the row's own {@code source_ref}.
     *                          mcp#67: the sweep re-resolved KEYS and never looked
     *                          at the ADDRESS it would hand a reader, so "all
     *                          clean" named nothing anyone could check against the
     *                          pin
     * @param movedOperations   declared cures whose ADDRESS differs from the
     *                          baseline's — the one shape a key check cannot see
     * @param movedAuthorities  namespaces whose authority differs from the
     *                          baseline's: the pin itself moved
     * @param compared          whether a baseline was supplied at all. Without one the two
     *                          move lists are empty BY CONSTRUCTION, and an empty list
     *                          renders identically to "nothing moved" — stating an answer
     *                          where the truth is that nobody looked. The lists cannot tell
     *                          a reader which; this says it
     */
    public record Audit(int declared, int resolved, int unresolved,
                        List<String> unresolvedOperations, List<String> absentNamespaces,
                        Map<String, String> authorities, Map<String, String> addresses,
                        List<String> movedOperations, List<String> movedAuthorities,
                        boolean compared) {

        /**
         * True when every declared cure still resolves AND nothing moved under it.
         *
         * <p>mcp#67: resolution alone used to be the whole test, so a pin that moved
         * and renamed a path while keeping the operation key reported
         * {@code clean: true} while every cure for that kind pointed at a dead
         * address. With no baseline to compare against, both move lists are empty and
         * this answers exactly what it always did.</p>
         */
        public boolean clean() {
            return unresolved == 0 && movedOperations.isEmpty() && movedAuthorities.isEmpty();
        }
    }

    /**
     * mcp#67 — the previous answer, against which a MOVE can be seen at all.
     *
     * <p>The issue reports two gaps — the sweep checks keys rather than addresses,
     * and nothing detects the pin moving — and they are one gap seen twice. A
     * renamed path and a correct path are indistinguishable by inspection, so
     * neither can be judged without a previous value. This is that value.</p>
     *
     * <p>It is a SEAM in the sense this class already uses for
     * {@link #audit(ExperienceStore, List)}: the parameter is what makes the check
     * falsifiable, because a sweep that has never seen a move and a corpus with no
     * move to find produce identical output.</p>
     */
    public record Baseline(Map<String, String> addresses, Map<String, String> authorities) {

        public Baseline {
            addresses = addresses == null ? Map.of() : Map.copyOf(addresses);
            authorities = authorities == null ? Map.of() : Map.copyOf(authorities);
        }

        // A `Baseline.of(Audit)` factory was written here and DELETED at C4: find_references
        // measured it at zero, including in the test, which builds a Baseline directly. This
        // file's own history is the precedent — `31348e85`, "Delete what nothing calls, and
        // correct a reason I invented", removed exactly this shape from these classes. The
        // convenience returns when a caller persists a previous sweep and needs it.
    }

    /**
     * RE-RESOLVE EVERY DECLARED CURE against the store as it stands.
     *
     * <p><b>Why this is owed.</b> {@code java-design-patterns} is a FOREIGN
     * authority pinned to somebody else's commit — {@link
     * org.jawata.mcp.knowledge.CatalogueOrigin} says so, and says that a foreign
     * origin's addresses must be re-resolved when the pin moves. Moving the pin
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
        return audit(store, declaredOperations, null);
    }

    /**
     * The same sweep, told what the answer was LAST time — mcp#67.
     *
     * <p>Without a baseline this behaves exactly as it always did, which is what
     * keeps the two existing entry points honest rather than quietly stricter.</p>
     */
    public static Audit audit(ExperienceStore store, List<String> declaredOperations,
                              Baseline baseline) {
        CatalogueAddresses addresses = CatalogueAddresses.of(store);
        List<String> broken = new ArrayList<>();
        // mcp#67 — the ADDRESS, not merely the fact that some row carries the key.
        // `resolves(operation)` answered the weaker question, so a pin that renamed a
        // path while keeping the key left every affected cure pointing at a dead
        // address and the sweep reporting clean.
        Map<String, String> resolvedAddresses = new LinkedHashMap<>();
        int ok = 0;
        for (String operation : declaredOperations) {
            CatalogueAddresses.Address address = addresses.address(operation);
            if (address == null) {
                broken.add(operation);
            } else {
                ok++;
                resolvedAddresses.put(operation, address.sourceRef());
            }
        }
        Map<String, String> authorities = new LinkedHashMap<>(CatalogueAddresses.authorities());
        return new Audit(declaredOperations.size(), ok, broken.size(), List.copyOf(broken),
            addresses.absentNamespaces(), Map.copyOf(authorities), Map.copyOf(resolvedAddresses),
            moved(baseline == null ? null : baseline.addresses(), resolvedAddresses),
            moved(baseline == null ? null : baseline.authorities(), authorities),
            baseline != null);
    }

    /**
     * Keys the baseline and the current answer BOTH carry, whose value differs.
     *
     * <p>A key the baseline never had is not a move — it is new. A key the current
     * answer has lost is not a move either: the unresolved count already speaks for
     * that, and reporting it twice under two names would make one repair look like
     * two problems. Only a key present on both sides with a different value is a
     * move, and that is precisely the case resolution cannot see.</p>
     */
    private static List<String> moved(Map<String, String> before, Map<String, String> after) {
        if (before == null || before.isEmpty()) {
            return List.of();
        }
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> was : before.entrySet()) {
            String now = after.get(was.getKey());
            if (now != null && !now.equals(was.getValue())) {
                changed.add(was.getKey());
            }
        }
        return List.copyOf(changed);
    }
}
