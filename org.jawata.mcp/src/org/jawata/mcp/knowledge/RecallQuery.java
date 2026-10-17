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

    /**
     * The five-cue form: every cue, and the scope the engine derives from them.
     *
     * <p>Kept until the call that names no lane is refused (S22, architecture step M6), because
     * the scope arrived after 64 construction sites existed and widening a record's canonical
     * constructor breaks every one of them. A cue naming a symbol or a package asks about code
     * and may be answered by any lane; any other cue is never answered out of the code lane.</p>
     *
     * @param symbol         an FQN the agent is working on
     * @param packageName    a package the agent is working in
     * @param operation      the operation about to run
     * @param symptom        an observed symptom / cue phrase
     * @param externalSystem an external dependency implicated in a symptom
     */
    public RecallQuery(String symbol, String packageName, String operation,
                       String symptom, String externalSystem) {
        this(symbol, packageName, operation, symptom, externalSystem,
            LaneScope.derivedFor(!blank(symbol) || !blank(packageName)));
    }

    /** A query built without a scope gets the one derived from its cues. */
    public RecallQuery {
        if (scope == null) {
            scope = LaneScope.derivedFor(!blank(symbol) || !blank(packageName));
        }
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

    /**
     * Whether this cue ASKS A CODE QUESTION — Sprint 28g D42(b), the lane derived from the cue.
     *
     * <p>A cue naming a {@code symbol} or a {@code packageName} is asking about code. Anything
     * else — a symptom, an operation, an external system, a question in prose — is not, and
     * must not be answered out of the code lane.</p>
     *
     * <p><b>The rule has teeth in ONE direction, and that is the requirement rather than a
     * softening of it.</b> Its reason is cost, in the sprint's own words: <i>"experience is small
     * and can be asked every time; the code lane is ~10,000 rows and must be asked only of a code
     * question; the patterns answer design questions."</i> So what the derivation does is keep
     * CODE out of a non-code question; it does not narrow a code question to the code lane, and
     * it says nothing about domain or rules. Reading the deliverable's lists as EXHAUSTIVE would
     * drop {@code api_contract} and {@code naming_convention} — {@link KnowledgeLane#DOMAIN_TYPES},
     * anchored to symbols — out of every symbol recall, which is knowledge lost to satisfy a rule
     * that was never about them.</p>
     *
     * <p>The deliverable's third category needs no branch here, which is worth stating because
     * its absence looks like an omission: <i>"experience and patterns"</i> are ONE lane.
     * Measured rather than assumed — {@code KnowledgeLane.of("reference", }
     * {@code CatalogueManifest.PROVENANCE)} answers {@link KnowledgeLane#EXPERIENCE}, on the
     * ruling that a borrowed pattern is somebody else's experience.</p>
     *
     * @return {@code true} when a {@code symbol} or {@code packageName} cue is set
     */
    public boolean isCodeShaped() {
        return hasSymbol() || hasPackage();
    }

    /**
     * Whether the caller NAMED the lanes, rather than the engine deriving them from the cue.
     * @return {@code true} when {@link #scope()} came from the caller
     */
    public boolean namesScope() {
        return !scope.derived();
    }
}
