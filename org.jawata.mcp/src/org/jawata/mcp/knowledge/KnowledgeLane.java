package org.jawata.mcp.knowledge;

import java.util.Locale;
import java.util.Set;

/**
 * Sprint 28f Stage 5 — which LIFECYCLE a stored row lives under.
 *
 * <p>Four lanes, and the point of the split is that they are governed differently:
 * an experience is reviewed and can be superseded by a later one; a domain fact is
 * corrected or it is wrong; a rule is versioned, amended and retired; code-derived
 * knowledge is regenerated from the code it came from.</p>
 *
 * <h2>Why this is a FOURTH classification and not a reuse of one of the three</h2>
 *
 * <p>Three sets already partition entry types, and it would be easy — and wrong —
 * to make the lane a synonym for one of them. They answer different questions, and
 * the difference decides which may source a stored column:</p>
 *
 * <table>
 *   <caption>the three that already exist</caption>
 *   <tr><th>who</th><th>the question it answers</th><th>what it depends on</th></tr>
 *   <tr><td>{@link EntryForm#EXPERIENCE_TYPES}</td>
 *       <td>is this type held to the situation+verdict form?</td>
 *       <td>the type alone</td></tr>
 *   <tr><td>{@code ExperienceRetrieval.DOMAIN_TYPES}</td>
 *       <td>does the primer push this unprompted?</td>
 *       <td>the type alone</td></tr>
 *   <tr><td>{@link KnowledgeKind#of}</td>
 *       <td>is this retrieved hard-gated or meaning-ranked?</td>
 *       <td>the type AND whether the anchor resolves <b>now</b></td></tr>
 * </table>
 *
 * <p><b>{@code KnowledgeKind} is disqualified as a source, and that is the finding
 * worth keeping.</b> Its answer turns on {@code isJavaResolvable()}, so the SAME row
 * is a FACT on a machine where its symbol still exists and an EXPERIENCE on one where
 * the class was deleted. A lane is STORED and migrated once: sourcing it from a
 * workspace-dependent predicate would stamp whatever happened to resolve on the
 * migrating machine, and the column would then mean different things in two copies of
 * one store. A lane is a property of the ROW; a kind is a property of the row's
 * relationship to the code in front of you.</p>
 *
 * <p>The experience half IS {@link EntryForm#EXPERIENCE_TYPES} rather than a copy of
 * it — that set already answers exactly "is this type an experience", and two lists
 * of one fact drift the first time either moves.</p>
 *
 * <h2>There is no catch-all, deliberately</h2>
 *
 * <p>The stage's own clause originally ended "the rest &rarr; experience". That makes
 * the migration's test — <i>every row lands in exactly one lane</i> — true whatever the
 * mapping does, which is an assertion that cannot fail. A type nobody has classified is
 * not an experience; it is an unanswered question. {@link #of} answers {@code null} for
 * one, the migration leaves the column NULL and says how many, and
 * {@code LaneMigrationTest} asserts the mapping PER TYPE so a new unclassified type
 * turns it red — which is the only case a catch-all exists to hide.</p>
 *
 * <p>{@code null} rather than a refusal because the alternative bricks a store: types
 * are open-ended at {@code record}, so throwing here would make an upgrade refuse to
 * open a database over a type someone invented. A NULL lane is a visible, queryable
 * absence ({@code WHERE lane IS NULL}) — the honest direction, in a sprint whose rule
 * is that a figure may say less than the truth and never more.</p>
 */
public enum KnowledgeLane {

    /** Reviewed, superseded by later experience: lessons, failure modes, borrowed patterns. */
    EXPERIENCE("experience"),

    /** Corrected or wrong, never outdated by a newer outcome: facts, contracts, conventions. */
    DOMAIN("domain"),

    /** Regenerated from the code it was derived from. */
    CODE("code"),

    /** Versioned, amended, retired — Stage 5's {@code type=rule}. */
    RULES("rules");

    private final String wire;

    KnowledgeLane(String wire) {
        this.wire = wire;
    }

    /** The value stored in {@code experience_entry.lane} and read on the wire. */
    public String wire() {
        return wire;
    }

    /**
     * Types whose rows are DOMAIN — corrected when wrong, never superseded by an outcome.
     *
     * <p>The set is the one {@code ExperienceRetrieval} already used to decide what the
     * primer pushes, MOVED here rather than copied: "domain-layer entry type" is the fact
     * both readers want, and the primer pushes these precisely BECAUSE they are domain.</p>
     */
    public static final Set<String> DOMAIN_TYPES = Set.of(
        "domain_fact", "domain_concept", "bounded_context", "invariant", "ubiquitous_language",
        "user", "feedback", "naming_convention", "api_contract", "convention");

    /** Stage 5's own type: a promoted rule, which versions rather than supersedes. */
    public static final String RULE_TYPE = "rule";

    /** A published pattern the catalogue lent us — somebody else's experience. */
    private static final String REFERENCE_TYPE = "reference";

    /**
     * The lane for a row of this {@code type} and {@code provenanceKind}, or {@code null}
     * when no ruling covers the type.
     *
     * <p>{@code provenanceKind} is read for ONE type. A {@code reference} from the
     * catalogue is a borrowed EXPERIENCE — a pattern is what worked for somebody else —
     * while a {@code reference} of ours is a pointer to a resource, which carries neither
     * a situation nor an outcome and is therefore domain. Measured on the live store
     * 2026-09-12: 190 {@code reference} rows, 189 of them the catalogue.</p>
     *
     * @param type the entry's type; {@code null} or blank is unclassified
     * @param provenanceKind the entry's provenance, or {@code null} for our own
     * @return the lane, or {@code null} when the type is unclassified
     */
    public static KnowledgeLane of(String type, String provenanceKind) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String t = type.strip().toLowerCase(Locale.ROOT);
        if (EntryForm.EXPERIENCE_TYPES.contains(t)) {
            return EXPERIENCE;
        }
        if (RULE_TYPE.equals(t)) {
            return RULES;
        }
        if (REFERENCE_TYPE.equals(t)) {
            return CatalogueManifest.PROVENANCE.equals(provenanceKind) ? EXPERIENCE : DOMAIN;
        }
        if (DOMAIN_TYPES.contains(t)) {
            return DOMAIN;
        }
        return null;
    }

    /** {@link #of} as the stored string, or {@code null} when the type is unclassified. */
    public static String wireOf(String type, String provenanceKind) {
        KnowledgeLane lane = of(type, provenanceKind);
        return lane == null ? null : lane.wire();
    }
}
