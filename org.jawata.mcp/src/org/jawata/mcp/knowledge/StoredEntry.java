package org.jawata.mcp.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Sprint 21 Stage 2 — a Phase-1 candidate row projected out of {@code experience_entry}
 * (plus its alias-normalized symptoms). Carries the indexed scope columns the fit-gate
 * needs, the current column {@code status} (which the frozen {@code body_json} does not
 * reflect after a {@link ExperienceStore#setStatus}), and the parsed {@code body}
 * document to return.
 */
public record StoredEntry(String id, String type, String symbolFqn, String packageName,
                          String operation, String status, String confidence, String language,
                          String externalSystem, String summary, List<String> symptoms,
                          String sourceRef, String scopeKind, String workspaceId,
                          Instant createdAt, Map<String, Object> body, Facets facets) {

    /**
     * Sprint 28c — the experience form, projected as ONE component rather
     * than five more positional fields.
     *
     * <p>Five more components on a record that already has sixteen makes every
     * construction site a counting exercise, and the questions callers actually
     * ask ("is this the new form?", "is its evidence dead?") are answered here
     * rather than re-derived at each call site from a null check.</p>
     *
     * <p>Every field is nullable and null MEANS something: unclassified, not
     * "classified as legacy". {@code form} is deliberately {@code Integer}, not
     * {@code int}, so the difference survives the projection.</p>
     */
    public record Facets(String situation, String cause, String verdict,
                         String provenanceKind, Integer form, Boolean evidenceDead,
                         String originClient) {

        /** A legacy row: no facets at all, which is what every pre-28c entry is. */
        public static final Facets NONE = new Facets(null, null, null, null, null, null, null);

        /** True when the entry arrived in the 28c form — it carries a situation. */
        public boolean isForm1() {
            return form != null && form == 1;
        }

        /** True when a human has been told the evidence behind this entry is gone. */
        public boolean hasDeadEvidence() {
            return Boolean.TRUE.equals(evidenceDead);
        }
    }

    /**
     * mcp#59: TRUE WHEN THIS ROW STILL ANSWERS — it has not been retired.
     *
     * <p>A superseded row was replaced by a newer one; a rejected row was judged wrong.
     * Neither is a candidate and neither is repair work: counting them teaches a review
     * sweep to repair corpses, which is what {@code migrate_form} did when a catalogue
     * update retired 187 pattern rows and they came back the next morning as a repair
     * class of 188.</p>
     *
     * <p><b>The rule is not new; a Java caller simply could not reach it.</b> It already
     * existed as the SQL fragment {@code AND status NOT IN ('rejected', 'superseded')} in
     * THREE places — {@code EmbeddingIndex} twice, {@code H2ExperienceStore} once — and a
     * WHERE clause is text the compiler cannot hand to anybody. So a fourth reader
     * walking {@link ExperienceStore#all()}, which filters nothing, silently got a
     * different population from every other reader, and nothing said so. This is that
     * rule where a Java reader can ask for it.</p>
     */
    public boolean isLive() {
        return !"superseded".equals(status) && !"rejected".equals(status);
    }

    /** Never null: a legacy row projects {@link Facets#NONE}. */
    public Facets facets() {
        return facets == null ? Facets.NONE : facets;
    }

    /** Sprint 21c: a section entry split out of a memory file ({@code scope_kind} marker). */
    public boolean isSection() {
        return "section".equals(scopeKind);
    }

    /**
     * Sprint 21a (item I): true when this entry's anchor may be judged by the JDT
     * resolver. Non-Java anchors (rust, ts, …) are OPAQUE to maintenance — never staled
     * or superseded by a resolver that cannot see them.
     *
     * <p><b>Null/blank stays Java-resolvable, and Sprint 28f E6 DELIBERATELY LEFT IT SO
     * after trying the other way.</b> E6 stopped the insert sites defaulting an absent
     * language to {@code "java"}, so null now means the author named no language — and the
     * obvious next step, requiring an explicit "java" here, was written and measured:
     * <b>seventeen tests went red</b>, every one of them a row with a JAVA FQN ANCHOR and
     * no stated language. They were right and the change was wrong.</p>
     *
     * <p>The reason is what this method actually asks. Not <i>"what language is this note
     * written in"</i> but <i>"may the JDT resolver judge THIS ANCHOR"</i> — and a row
     * carrying {@code com.example.Type#member} has a Java anchor whoever wrote it and in
     * whatever prose. A markdown story that deliberately anchors itself to a symbol is
     * making a claim about code, and when that symbol goes the story IS stale; exempting
     * it would have removed the staleness signal from exactly the notes that point at
     * code. What E6 fixes is the row with NO anchor, which this method never sees —
     * {@code refresh} skips it for having no FQN long before the language is consulted.</p>
     */
    public boolean isJavaResolvable() {
        return language == null || language.isBlank() || "java".equalsIgnoreCase(language);
    }

    /** Scope-specificity rank for disambiguation — higher = more specific = preferred. */
    public int specificity() {
        if (symbolFqn != null && !symbolFqn.isBlank()) {
            return 3;                       // symbol-scoped: the most specific
        }
        if (packageName != null && !packageName.isBlank()) {
            return 2;                       // package-scoped
        }
        if (operation != null && !operation.isBlank()) {
            return 1;                       // operation-scoped
        }
        return 0;                           // symptom / broad
    }

    /** Confidence rank (high › medium › low › unknown) for disambiguation tie-breaks. */
    public int confidenceRank() {
        if (confidence == null) {
            return 0;
        }
        return switch (confidence.toLowerCase(java.util.Locale.ROOT)) {
            case "high" -> 3;
            case "medium" -> 2;
            case "low" -> 1;
            default -> 0;
        };
    }
}
