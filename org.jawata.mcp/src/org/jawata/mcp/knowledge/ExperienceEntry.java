package org.jawata.mcp.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 21 (v2.0): a stored knowledge entry — the shared {@link SymbolFact} core plus
 * the retrieval facets the experience store indexes (recall-gap notes §4.1): curation
 * status, scope kind, operation, alias-normalized symptoms, typed links, and fault
 * ownership. The store persists {@link #toMap()} as the {@code body_json} document and
 * indexes these facets into columns / child tables for two-phase retrieval (Stage 2).
 */
public final class ExperienceEntry {

    // Curation status.
    public static final String CANDIDATE = "candidate";
    public static final String ACCEPTED = "accepted";
    public static final String REJECTED = "rejected";
    public static final String SUPERSEDED = "superseded";

    private final SymbolFact fact;
    private final String status;
    private final String scopeKind;
    private final String operation;
    private final List<String> symptoms;
    private final List<Link> links;

    private final String faultOwner;
    private final String externalSystem;
    /** Sprint 21a (item I): anchor language; null/blank = java. Non-Java anchors are
     *  opaque to JDT maintenance (never staled by a resolver that cannot see them). */
    private final String language;

    // Sprint 28c (D3) — the knowledge-spine facets. All nullable: an entry that
    // carries none of them is a legacy row, and `form` is what says so without
    // guessing. Only lessons and failure modes are REQUIRED to carry situation
    // and verdict (EntryForm); a domain fact legitimately has neither.
    /** When this entry applies, as a condition — never package geography. */
    private final String situation;
    /**
     * Sprint 28c (v15) — the DIAGNOSIS: the underlying problem this entry's
     * solution addresses, distinct from the symptoms that reveal it. One
     * symptom maps to many causes (a fast heartbeat: running, a heart attack,
     * a virus) and the solution binds to the cause, never to the symptom — so
     * a symptom-recall that returns several entries is a differential, and
     * this field is what discriminates between them. Ruled first-class on
     * 2026-08-27: "this should not be buried somewhere" — it had lived
     * unqueryably inside summary/details prose.
     */
    private final String cause;
    /** worked / failed_avoid / unproven. */
    private final String verdict;
    /** recorded / ingested / catalog / seat_run / migrated. Provenance is a FIELD, not content. */
    private final String provenanceKind;
    /** null/0 = legacy row, 1 = the 28c form. */
    private final Integer form;

    /** A typed graph edge; {@code rel} ∈ {handled_by, fixed_by, detected_by, supersedes}. */
    public record Link(String rel, String target) {}

    private ExperienceEntry(Builder b) {
        this.fact = b.fact;
        this.status = b.status;
        this.scopeKind = b.scopeKind;
        this.operation = b.operation;
        this.symptoms = List.copyOf(b.symptoms);
        this.links = List.copyOf(b.links);
        this.faultOwner = b.faultOwner;
        this.externalSystem = b.externalSystem;
        this.language = b.language;
        this.situation = b.situation;
        this.cause = b.cause;
        this.verdict = b.verdict;
        this.provenanceKind = b.provenanceKind;
        this.form = b.form;
    }

    public static Builder of(SymbolFact fact) {
        return new Builder(fact);
    }

    /** Convenience: a {@code candidate} entry wrapping just a fact. */
    public static ExperienceEntry candidate(SymbolFact fact) {
        return of(fact).build();
    }

    public SymbolFact fact() {
        return fact;
    }

    public String status() {
        return status;
    }

    public String scopeKind() {
        return scopeKind;
    }

    public String operation() {
        return operation;
    }

    public List<String> symptoms() {
        return symptoms;
    }

    // Sprint 28c (D3) — the knowledge-spine facets. Null on a legacy row.

    public String situation() {
        return situation;
    }

    public String cause() {
        return cause;
    }

    public String verdict() {
        return verdict;
    }

    public String provenanceKind() {
        return provenanceKind;
    }

    public Integer form() {
        return form;
    }

    public List<Link> links() {
        return links;
    }

    public String faultOwner() {
        return faultOwner;
    }

    public String externalSystem() {
        return externalSystem;
    }

    public String language() {
        return language;
    }

    /** Full document = the fact's map merged with the retrieval facets. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>(fact.toMap());
        m.put("status", status);
        if (scopeKind != null && !scopeKind.isBlank()) {
            m.put("scope_kind", scopeKind);
        }
        if (operation != null && !operation.isBlank()) {
            m.put("operation", operation);
        }
        if (!symptoms.isEmpty()) {
            m.put("symptoms", symptoms);
        }
        if (!links.isEmpty()) {
            List<Map<String, Object>> ls = new ArrayList<>();
            for (Link l : links) {
                ls.add(Map.of("rel", l.rel(), "target", l.target()));
            }
            m.put("links", ls);
        }
        if (faultOwner != null && !faultOwner.isBlank()) {
            m.put("fault_owner", faultOwner);
        }
        if (externalSystem != null && !externalSystem.isBlank()) {
            m.put("external_system", externalSystem);
        }
        if (language != null && !language.isBlank()) {
            m.put("language", language);
        }
        return m;
    }

    public static final class Builder {
        private final SymbolFact fact;
        private String status = CANDIDATE;
        private String scopeKind;
        private String operation;
        private List<String> symptoms = new ArrayList<>();
        private List<Link> links = new ArrayList<>();
        private String faultOwner;
        private String externalSystem;
        private String language;
        // Sprint 28c (D3) facets — see the outer class's fields.
        private String situation;
        private String cause;
        private String verdict;
        private String provenanceKind;
        private Integer form;

        private Builder(SymbolFact fact) {
            if (fact == null) {
                throw new IllegalArgumentException("fact is required");
            }
            this.fact = fact;
        }

        public Builder status(String status) {
            if (status != null && !status.isBlank()) {
                this.status = status;
            }
            return this;
        }

        public Builder scopeKind(String scopeKind) {
            this.scopeKind = scopeKind;
            return this;
        }

        public Builder operation(String operation) {
            this.operation = operation;
            return this;
        }

        public Builder symptoms(List<String> symptoms) {
            this.symptoms = symptoms == null ? new ArrayList<>() : new ArrayList<>(symptoms);
            return this;
        }

        public Builder addSymptom(String symptom) {
            this.symptoms.add(symptom);
            return this;
        }

        public Builder links(List<Link> links) {
            this.links = links == null ? new ArrayList<>() : new ArrayList<>(links);
            return this;
        }

        public Builder addLink(String rel, String target) {
            this.links.add(new Link(rel, target));
            return this;
        }

        public Builder faultOwner(String faultOwner) {
            this.faultOwner = faultOwner;
            return this;
        }

        public Builder externalSystem(String externalSystem) {
            this.externalSystem = externalSystem;
            return this;
        }

        public Builder language(String language) {
            this.language = language;
            return this;
        }

        // Sprint 28c (D3) — the knowledge-spine facets. Every one is optional
        // here; EntryForm decides which are REQUIRED, and it decides by type,
        // because a domain fact has no outcome and demanding one would teach
        // authors to invent verdicts.

        public Builder cause(String cause) {
            this.cause = cause;
            return this;
        }

        public Builder situation(String situation) {
            this.situation = situation;
            return this;
        }

        public Builder verdict(String verdict) {
            this.verdict = verdict;
            return this;
        }

        public Builder provenanceKind(String provenanceKind) {
            this.provenanceKind = provenanceKind;
            return this;
        }

        public Builder form(Integer form) {
            this.form = form;
            return this;
        }

        public ExperienceEntry build() {
            return new ExperienceEntry(this);
        }
    }
}
