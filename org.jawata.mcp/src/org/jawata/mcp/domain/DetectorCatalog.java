package org.jawata.mcp.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Sprint 16b/D — an ordered registry of {@link Detector}s keyed by {@code kind}.
 * The Smell front door ({@code find_quality_issue}) projects its {@code kind}
 * enum + dispatch from this catalog, so a sprint that wants new smell coverage
 * simply {@link #register}s detectors here — the loaded tool surface is unchanged
 * while the capability behind it grows.
 *
 * <p>Insertion order is preserved so the projected enum is stable.</p>
 */
public final class DetectorCatalog {

    private final Map<String, Detector> byKind = new LinkedHashMap<>();
    /** kind -> the families it belongs to (Sprint 20). A kind may be in several. */
    private final Map<String, Set<String>> familiesByKind = new LinkedHashMap<>();

    /**
     * Register (or replace) a detector, optionally tagging it into one or more
     * families (Sprint 20). Returns {@code this} for chaining. Family tags are
     * registration metadata — detectors stay pure; {@code find_quality_issue}'s
     * {@code family} filter projects a subset of kinds from these tags.
     */
    public DetectorCatalog register(Detector detector, String... families) {
        Objects.requireNonNull(detector, "detector");
        Objects.requireNonNull(detector.kind(), "detector.kind()");
        byKind.put(detector.kind(), detector);
        familiesByKind.put(detector.kind(), Set.copyOf(Arrays.asList(families)));
        return this;
    }

    /** All registered kinds, in registration order. */
    public List<String> kinds() {
        return List.copyOf(byKind.keySet());
    }

    /**
     * Kinds in a given family, in registration order. Blank/null {@code family}
     * returns all kinds (no filter).
     */
    public List<String> kinds(String family) {
        if (family == null || family.isBlank()) {
            return kinds();
        }
        List<String> out = new ArrayList<>();
        for (String kind : byKind.keySet()) {
            if (familiesByKind.getOrDefault(kind, Set.of()).contains(family)) {
                out.add(kind);
            }
        }
        return List.copyOf(out);
    }

    /** The families a kind belongs to (empty if untagged / unknown). */
    public Set<String> familiesOf(String kind) {
        return familiesByKind.getOrDefault(kind, Set.of());
    }

    /** The detector for a kind, if registered. */
    public Optional<Detector> get(String kind) {
        return Optional.ofNullable(byKind.get(kind));
    }

    public boolean has(String kind) {
        return byKind.containsKey(kind);
    }

    public int size() {
        return byKind.size();
    }

    public Collection<Detector> all() {
        return List.copyOf(byKind.values());
    }
}
