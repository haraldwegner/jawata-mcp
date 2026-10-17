package org.jawata.mcp.knowledge;

/**
 * Sprint 21 Stage 2 — a retrieval cue. Any subset of the fields may be set; the store
 * gathers candidates by matching ANY present cue (Phase 1), then the {@link
 * ExperienceRetrieval} fit-gate keeps only entries whose scope <em>contains</em> the cue
 * (Phase 2). All-blank is an empty query (returns absence).
 *
 * @param symbol         an FQN the agent is working on (type or {@code Type#member})
 * @param packageName    a package the agent is working in
 * @param operation      the operation about to run (e.g. {@code rename_symbol})
 * @param symptom        an observed symptom / cue phrase (alias-normalized on match)
 * @param externalSystem an external dependency implicated in a symptom
 * @param scope          Sprint 28g S22 — which lanes may answer, chosen BEFORE the search. A FILTER
 *                       on the corpus, never a cue: it is the lanes the search looks in, so a lane
 *                       is searched and ranked on its own and nothing is dropped afterwards.
 */
public record RecallQuery(String symbol, String packageName, String operation,
                          String symptom, String externalSystem, LaneScope scope) {

    /** A query always carries the lanes its caller named; there is no default to fall back on. */
    public RecallQuery {
        java.util.Objects.requireNonNull(scope, "a query must name its lanes");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * True when no cue is set — an empty query resolves to absence, never a scan.
     *
     * <p><b>{@code scope} is deliberately NOT read here, and that is the design.</b> The cues
     * say WHAT to look for; the scope says which lanes may answer. A query carrying only a scope
     * asks "give me the domain layer", which is the scan this method exists to refuse — reading
     * {@code scope} here would turn {@code recall(lane=domain)} into a whole-corpus sweep that
     * returns whatever happens to sort first.</p>
     */
    public boolean isEmpty() {
        return blank(symbol) && blank(packageName) && blank(operation)
            && blank(symptom) && blank(externalSystem);
    }

    /**
     * Whether the {@code symbol} cue is set.
     * @return {@code true} when {@code symbol} is non-null and non-blank
     */
    public boolean hasSymbol() {
        return !blank(symbol);
    }

    /**
     * Whether the {@code packageName} cue is set.
     * @return {@code true} when {@code packageName} is non-null and non-blank
     */
    public boolean hasPackage() {
        return !blank(packageName);
    }

    /**
     * Whether the {@code operation} cue is set.
     * @return {@code true} when {@code operation} is non-null and non-blank
     */
    public boolean hasOperation() {
        return !blank(operation);
    }

    /**
     * Whether the {@code symptom} cue is set.
     * @return {@code true} when {@code symptom} is non-null and non-blank
     */
    public boolean hasSymptom() {
        return !blank(symptom);
    }

    /**
     * Whether the {@code externalSystem} cue is set.
     * @return {@code true} when {@code externalSystem} is non-null and non-blank
     */
    public boolean hasExternalSystem() {
        return !blank(externalSystem);
    }
}
