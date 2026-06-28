package org.goja.mcp.domain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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

    /** Register (or replace) a detector. Returns {@code this} for chaining. */
    public DetectorCatalog register(Detector detector) {
        Objects.requireNonNull(detector, "detector");
        Objects.requireNonNull(detector.kind(), "detector.kind()");
        byKind.put(detector.kind(), detector);
        return this;
    }

    /** All registered kinds, in registration order. */
    public List<String> kinds() {
        return List.copyOf(byKind.keySet());
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
