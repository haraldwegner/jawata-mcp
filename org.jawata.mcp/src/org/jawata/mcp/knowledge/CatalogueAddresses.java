package org.jawata.mcp.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE ADDRESS LOOKUP — a catalogue row's public address, READ OFF the row.
 *
 * <p><b>Why this exists (Sprint 28d, arch step 4).</b> A cure names a design, and
 * a design has an address somebody can open. There are exactly two ways to
 * produce that address, and only one of them is honest:</p>
 *
 * <ul>
 *   <li><b>COMPOSE</b> — take the slug and build
 *       {@code "catalogue:java-design-patterns/" + slug + "/README.md"}. This
 *       always succeeds, and that is the whole problem: it succeeds for a slug
 *       nothing holds. It used to succeed in the WRONG SHAPE for the other
 *       origin too, whose refs carried no {@code /README.md} tail; S4 unified
 *       the scheme, so shape no longer distinguishes a composed address from a
 *       read one — which is exactly why the composition test now plants a ref no
 *       rule would generate. An address produced by composing is a claim about a
 *       row nobody looked at.</li>
 *   <li><b>RESOLVE</b> — find the row that carries the cure's {@code operation}
 *       key and read its {@code source_ref}. It fails when there is nothing to
 *       point at, which is exactly when a cure has no address.</li>
 * </ul>
 *
 * <p>This class only resolves. It has no way to build an address: the only
 * strings it returns are ones it read out of {@link StoredEntry#sourceRef()}.</p>
 *
 * <h2>An absent namespace is a STATED degradation, not an empty answer</h2>
 * <p>"The catalogue had nothing for you" and "there is no catalogue" are
 * different facts and only the second is a fault, yet both used to arrive as the
 * same empty result. So {@link #absentNamespaces()} NAMES every registered
 * namespace holding zero live rows — the same shape the store already uses for
 * an unembedded corpus and for substrate drift, where reporting an absence as a
 * clean zero is this project's oldest recorded lie.</p>
 *
 * <h2>Deliberately in {@code knowledge}, and deliberately ignorant of cures</h2>
 * <p>It knows rows, namespaces and addresses; it does not know what a smell is
 * or which design cures it. That declaration lives with the detectors, and
 * {@code knowledge} must not import {@code tools} — so the caller passes the
 * operation key in and this answers with an address or with nothing.</p>
 */
public final class CatalogueAddresses {

    /**
     * One resolved address: the key it was found by, the namespace that owns it,
     * and the row's own {@code source_ref}.
     *
     * <p>{@code sourceRef} is the row's stored value verbatim. Nothing here
     * reformats it, because the two sources spell their refs differently and a
     * normalisation would be a composition wearing a different name.</p>
     */
    public record Address(String operation, String namespace, String sourceRef) {
    }

    private final Map<String, Address> byOperation;
    private final List<String> absentNamespaces;

    private CatalogueAddresses(Map<String, Address> byOperation,
                               List<String> absentNamespaces) {
        this.byOperation = byOperation;
        this.absentNamespaces = absentNamespaces;
    }

    /**
     * Index a store's live catalogue rows ONCE.
     *
     * <p>Built eagerly and held, because the alternative — asking the store per
     * cure — is a full scan per finding, and a sweep produces hundreds of
     * findings. Cheap by contract for the registry itself: {@link
     * CatalogueSources#all()} is only asked for namespaces and prefixes here,
     * never for an authority — {@link CatalogueManifest#authorityOf} parses a
     * manifest on its first call, and a per-finding path must not pay that.</p>
     *
     * <p>SUPERSEDED and REJECTED rows are excluded. A retired pattern still has
     * a {@code source_ref}, and pointing a cure at it would hand a reader an
     * address the store itself has stopped answering with.</p>
     */
    public static CatalogueAddresses of(ExperienceStore store) {
        // Per-namespace first, then merged in REGISTRY ORDER, so two sources
        // carrying the same operation resolve to the same one on every run.
        // Merging in store.all() order would make the answer depend on row
        // insertion order, which is not a decision anybody made.
        Map<String, Map<String, Address>> perNamespace = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<CatalogueOrigin> origins = CatalogueSources.all();
        for (CatalogueOrigin o : origins) {
            perNamespace.put(o.namespace(), new LinkedHashMap<>());
            // Every registered namespace present even at ZERO: an absent key and
            // a zero must not read alike — the registry's own rule.
            counts.put(o.namespace(), 0);
        }

        if (store != null) {
            for (StoredEntry e : store.all()) {
                CatalogueOrigin owner = CatalogueSources.owning(e.sourceRef());
                if (owner == null) {
                    continue;
                }
                String status = e.status();
                if (ExperienceEntry.SUPERSEDED.equals(status)
                        || ExperienceEntry.REJECTED.equals(status)) {
                    continue;
                }
                counts.merge(owner.namespace(), 1, Integer::sum);
                String operation = e.operation();
                if (operation == null || operation.isBlank()) {
                    continue;   // a catalogue row without an operation key is
                                // unaddressable BY KEY; it still counts as a row,
                                // so the namespace is not reported absent for it
                }
                perNamespace.get(owner.namespace()).putIfAbsent(operation,
                    new Address(operation, owner.namespace(), e.sourceRef()));
            }
        }

        Map<String, Address> merged = new LinkedHashMap<>();
        List<String> absent = new ArrayList<>();
        for (CatalogueOrigin o : origins) {
            merged.putAll(perNamespace.get(o.namespace()));
            if (counts.get(o.namespace()) == 0) {
                absent.add(o.namespace());
            }
        }
        return new CatalogueAddresses(Collections.unmodifiableMap(merged),
            Collections.unmodifiableList(absent));
    }

    /**
     * The address a cure key points at, or {@code null} when NO ROW carries it.
     *
     * <p>Null is the answer, not an exception and not a plausible string: the
     * caller has to decide between "state that this cure has no address" and
     * "fall back", and it can only do that if the miss is visible.</p>
     */
    public Address address(String operation) {
        return operation == null ? null : byOperation.get(operation);
    }

    /** True when a live catalogue row carries this cure key. */
    public boolean resolves(String operation) {
        return address(operation) != null;
    }

    /** Every registered namespace holding ZERO live rows, named. */
    public List<String> absentNamespaces() {
        return absentNamespaces;
    }

    /**
     * Namespace &rarr; the authority behind it, for a REPORT rather than for a
     * finding.
     *
     * <p>Separate from resolution on purpose: an origin's authority is read from
     * its MANIFEST — the fork's from its pinned commit — so asking for it is a
     * JSON parse the first time. A per-finding cure must not pay that; an audit
     * run on demand may. {@link CatalogueManifest#authorityOf} remembers the
     * answer per manifest, which is safe because a classpath resource cannot
     * change while the process runs.</p>
     */
    public static Map<String, String> authorities() {
        Map<String, String> out = new LinkedHashMap<>();
        for (CatalogueOrigin o : CatalogueSources.all()) {
            out.put(o.namespace(), CatalogueManifest.authorityOf(o));
        }
        return out;
    }
}
