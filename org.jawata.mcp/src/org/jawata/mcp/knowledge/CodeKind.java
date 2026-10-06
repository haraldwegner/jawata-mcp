package org.jawata.mcp.knowledge;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sprint 28g S14 D32 — the CODE lane's kinds, each carrying WHAT IT HANGS ON.
 *
 * <p><b>Why this type exists rather than a predicate over names.</b> D32's measure asks that a
 * description with no anchor be refused while an area is not, <em>"and the thing that tells them
 * apart is a property of the type rather than a list of type names"</em>. What stood there was
 * {@code KnowledgeLane.CODE_TYPES.contains(type)} — a {@code Set.of("job", "area")}. That set
 * could not tell the two apart even in principle, because it holds BOTH, while their anchor
 * requirements genuinely differ: a job says what one MEMBER is for, an area what one PACKAGE is
 * for. A name list can answer "is this the code lane" and nothing finer, so the rule that needed
 * the finer answer had none and admitted an anchorless job.</p>
 *
 * <p><b>{@link KnowledgeLane#CODE_TYPES} is derived from this enum rather than written beside
 * it.</b> Two lists of the same population drift, and this codebase has closed that shape
 * repeatedly — the derivation is what makes adding a third code kind a one-place change instead
 * of a two-place convention.</p>
 *
 * <p>The wire strings are unchanged, because they are what a caller types and what every stored
 * row already carries.</p>
 */
public enum CodeKind {

    /** What one member is FOR. Anchored to that member, so it is nothing without one. */
    JOB("job", Anchor.MEMBER),

    /** What one package is FOR. Anchored to the package, which is carried as a scope. */
    AREA("area", Anchor.PACKAGE);

    /**
     * What a kind hangs on — the property that replaces the name list.
     *
     * <p>It is deliberately about the ANCHOR and not about "needs a symbol argument": the same
     * distinction decides where a row is filed, how a staleness sweep re-resolves it, and which
     * column a reader scopes by. Naming it after the argument would have tied it to one door.</p>
     */
    public enum Anchor {
        /** A single member — {@code pkg.Type#member}. */
        MEMBER,
        /** A package. */
        PACKAGE
    }

    private final String wire;
    private final Anchor anchor;

    CodeKind(String wire, Anchor anchor) {
        this.wire = wire;
        this.anchor = anchor;
    }

    /** The type string a caller writes and a stored row carries. */
    public String wire() {
        return wire;
    }

    /** What a row of this kind hangs on. */
    public Anchor anchorsTo() {
        return anchor;
    }

    /**
     * The kind for a type string, or empty when the type is not in the code lane at all.
     *
     * <p>Empty is the answer for every experience and every domain type, which is what lets a
     * caller ask this one question instead of first asking whether the lane applies.</p>
     */
    public static Optional<CodeKind> of(String type) {
        if (type == null) {
            return Optional.empty();
        }
        String t = type.strip().toLowerCase(Locale.ROOT);
        for (CodeKind k : values()) {
            if (k.wire.equals(t)) {
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }

    /** The wire strings, for the one place that still needs the set rather than the kind. */
    public static Set<String> wires() {
        return java.util.Arrays.stream(values())
            .map(CodeKind::wire)
            .collect(Collectors.toUnmodifiableSet());
    }
}
