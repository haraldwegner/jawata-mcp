package org.jawata.mcp.tools.smell;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE DECLARED CURES — which design answers which smell, stated once.
 *
 * <p>This is a table of LOOKUP KEYS, and it is deliberately not a table of
 * addresses. Each entry names the {@code operation} a catalogue row carries
 * ({@code design:<slug>}); the address that row lives at is read off the row by
 * {@link org.jawata.mcp.knowledge.CatalogueAddresses}. The split is the whole
 * point of Sprint 28d's arch step 4: a key that matches nothing yields NO cure,
 * whereas an address built from the same slug would yield a plausible one.</p>
 *
 * <p><b>The keys are LITERALS.</b> Not {@code "design:" + slug}, not
 * {@code slug.replace('_','-')} — every key is written out. A key assembled from
 * a naming convention is the composition this design forbids, one level up: it
 * would silently follow the fork's slug spelling and silently mis-spell the
 * samples' ({@code compose_method} the plan kind versus {@code compose-method}
 * the slug are one letter apart and neither is derivable from the other).</p>
 *
 * <h2>Why the recipe may be null</h2>
 * <p>A {@code recipe} is a runnable, parity-gated plan kind. Four of this
 * sprint's five principle kinds have no such transform — {@code cqs},
 * {@code coupling}, {@code composition_over_inheritance} and
 * {@code encapsulation} are cured by a design decision, not by an automated
 * rewrite. Their cure is still an ADDRESS a reader can open, so they belong
 * here with a null recipe rather than being left out and reading as "no cure
 * known". {@link RecipeCatalog} keeps its own, narrower answer: what can be
 * RUN. The two are different questions and this table does not overwrite it.</p>
 */
public final class CureCatalog {

    /**
     * One declared cure: the plan kind that performs it (or null when nothing
     * automates it), and the catalogue key its design lives under.
     */
    public record Cure(String recipe, String operation) {
    }

    /** The three designs that close a modification axis — OCP's answer, shared by its traces. */
    private static final List<Cure> OPEN_THE_AXIS = List.of(
        new Cure("refactor_to_state", "design:state"),
        new Cure("refactor_to_command_dispatcher", "design:command"),
        new Cure("form_template_method", "design:template-method"));

    private static final Map<String, List<Cure>> BY_KIND = byKind();

    private static Map<String, List<Cure>> byKind() {
        Map<String, List<Cure>> m = new LinkedHashMap<>();

        // --- the five principle kinds Sprint 28d adds ------------------------
        m.put("ocp", OPEN_THE_AXIS);
        m.put("cqs", List.of(
            new Cure(null, "design:command-query-responsibility-segregation")));
        m.put("coupling", List.of(
            new Cure(null, "design:dependency-injection"),
            new Cure(null, "design:mediator")));
        m.put("composition_over_inheritance", List.of(
            new Cure(null, "design:delegation"),
            new Cure(null, "design:strategy")));
        m.put("encapsulation", List.of(
            new Cure(null, "design:private-class-data")));

        // --- the traces and smells that already had recipes ------------------
        // Same designs as `ocp` because they ARE its traces: OcpDetector relabels
        // a trace finding, so the trace's cure and the principle's must be one
        // table or they drift the moment either is edited.
        m.put("divergent_change", OPEN_THE_AXIS);
        m.put("shotgun_surgery", OPEN_THE_AXIS);
        m.put("switch_statements", List.of(
            new Cure("refactor_to_state", "design:state")));
        m.put("type_code", List.of(
            new Cure("replace_type_code_with_class", "design:type-object")));
        m.put("singleton", List.of(
            new Cure("inline_singleton", "design:singleton")));
        m.put("long_method", List.of(
            new Cure("compose_method", "design:compose-method")));
        return Map.copyOf(m);
    }

    private CureCatalog() {
    }

    /** The declared cures for a smell kind, best-first; empty when none is declared. */
    public static List<Cure> curesFor(String kind) {
        if (kind == null) {
            return List.of();
        }
        return BY_KIND.getOrDefault(kind, List.of());
    }

    /**
     * Every DISTINCT cure key declared anywhere in this table — the set the
     * re-resolution check sweeps.
     *
     * <p>Distinct rather than per-kind, because the same design cures several
     * kinds and an audit counting {@code design:state} three times would report
     * a drift of three for one moved row.</p>
     */
    public static List<String> declaredOperations() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (List<Cure> cures : BY_KIND.values()) {
            for (Cure c : cures) {
                out.add(c.operation());
            }
        }
        return List.copyOf(out);
    }
}
