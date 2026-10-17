package org.jawata.mcp.knowledge;

import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Advisor;
import org.jawata.mcp.domain.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 21 Stage 3 — the store-backed {@link Advisor} that FILLS the Sprint-18 seam
 * (replacing {@code NoOpAdvisor} at the orchestration wiring). It bridges the domain
 * learning seam to the knowledge store (DIP: implements {@code domain.Advisor}, wraps the
 * {@link ExperienceStore}).
 *
 * <ul>
 *   <li><b>adviseBefore</b> → the Stage-2 terminal retrieval: a cue built from the
 *       refactoring {@code kind} (operation) + {@code target} (symbol) yields ≤1 fitting
 *       node's advice, or empty on absence. Advisory only — never a hard gate.</li>
 *   <li><b>record</b> → the write-admission filter: admit only what JDT cannot re-derive —
 *       negatives / hazards / failure modes (a {@code ROLLED_BACK} or {@code FLAGGED}
 *       outcome). A successful {@code APPLIED} outcome is the derivable normal path and is
 *       dropped; duplicates (same scope + summary) are dropped. Every admitted write is a
 *       {@code candidate} pending promotion.</li>
 * </ul>
 */
public final class ExperienceAdvisor implements Advisor {

    private static final Logger log = LoggerFactory.getLogger(ExperienceAdvisor.class);

    /** Terminal advice: at most one fitting node (the most specific). */
    private static final int MAX_ADVICE = 1;

    private final ExperienceStore store;
    private final ExperienceRetrieval retrieval;

    /**
     * Keyword-only advisor. Kept for the tests written before meaning recall
     * existed; production uses the index-bearing constructor below, because a
     * pre-advice surface with no embedder can only match on exact words.
     */
    public ExperienceAdvisor(ExperienceStore store, Supplier<IJdtService> jdt) {
        this(store, jdt, null);
    }

    /**
     * Sprint 27a D2 — the pre-advice surface, running the SAME union recall the
     * front door does. Before this it built a keyword-only retriever, so a
     * meaning-only lesson never reached the refactoring pre-advice however well
     * it matched — the surface computed nothing to discard, and then discarded
     * it (audit finding F3).
     */
    public ExperienceAdvisor(ExperienceStore store, Supplier<IJdtService> jdt,
                             EmbeddingIndex index) {
        this.store = store;
        this.retrieval = new ExperienceRetrieval(store, jdt, index);
    }

    /** Install the quality ledger so this surface's speak/abstain is counted. */
    public void setQualityLedger(QualityLedger ledger) {
        retrieval.setQualityLedger(ledger);
    }

    /**
     * Advice under the store's OWN retrieval budget — the form a caller that ASKED for it uses.
     *
     * <p>The default is right here and wrong on a path nobody asked on, which is why the
     * overload below exists: a caller that chose to ask is willing to wait out a wedged store.</p>
     */
    @Override
    public List<String> adviseBefore(String kind, String target) {
        return adviseBefore(kind, target, ExperienceRetrieval.RETRIEVAL_BUDGET_MILLIS);
    }

    /**
     * The same advice under a deadline the CALLER chooses — Sprint 28g D42, step M4.
     *
     * <p>It exists because the default budget is unusable on a path nobody asked on. A block
     * riding EVERY tool response cannot wait fifteen seconds for an advisory, and the default's
     * own javadoc says fifteen seconds is <i>"not a performance target"</i> but the point past
     * which slow is indistinguishable from wedged. The vouched fact behind D42's deadline clause
     * is sequential store reads walking past a FOUR-second watchdog until the mandatory line was
     * never emitted at all.</p>
     *
     * <p>So the budget is a PARAMETER rather than a constant: a call site on the response path
     * cannot omit it and quietly inherit the long one. That is the whole of <i>M4 before M5</i> —
     * the ordering holds because the deadline is an argument M5's call has to supply.</p>
     *
     * <p>Past the deadline the answer is EMPTY rather than wrong. The underlying recall cancels
     * the abandoned read and answers {@code RESULT_UNAVAILABLE} with its reason, which carries no
     * {@code entries} and no {@code analogies} — so this renders nothing from it. A response
     * therefore carries a prior-work block or carries none; it never carries a half-read one.</p>
     */
    public List<String> adviseBefore(String kind, String target, long budgetMillis) {
        return priorWork(kind, target, budgetMillis, QualityLedger.SURFACE_PRE_ADVICE).advice();
    }

    /**
     * What the response path needs and a {@code List<String>} cannot say — WHICH empty this is.
     *
     * <p>Sprint 28g D42, the second half of the architect's deadline refusal: <i>"past the
     * deadline the response reads 'we did not look' — never silence, never 'nothing found'"</i>.
     * {@link #adviseBefore} cannot carry that. Past the deadline the underlying recall answers
     * {@code RESULT_UNAVAILABLE}, which strips every key but the cue, so this surface renders
     * nothing and returns an EMPTY LIST — the identical value it returns when the store simply
     * holds nothing about this target. The distinction the refusal exists to preserve was being
     * destroyed at the return type, one layer below the caller that needed it.</p>
     *
     * <p>So the outcome travels WITH the advice. {@link PriorWork#looked()} is false only for the
     * one case where the store was never read to a conclusion, and {@link PriorWork#reason()} is
     * the recall's own sentence about why — not a sentence composed here, because the layer that
     * failed is the one that can say what happened.</p>
     *
     * <p>It is the ONE read and the ONE rendering: {@code adviseBefore} delegates here rather than
     * repeating the walk, so the two forms cannot disagree about what the store said.</p>
     */
    @SuppressWarnings("unchecked")
    public PriorWork priorWork(String kind, String target, long budgetMillis, String surface) {
        RecallQuery q = new RecallQuery(
            looksLikeFqn(target) ? target : null,   // symbol cue
            null,                                    // package
            blankToNull(kind),                       // operation = the refactoring kind
            null,                                    // symptom
            null,                                    // external_system
            // A TOOL CALL asks the lessons and what the member is FOR (Sprint 28g S22, the
            // lane table): what was learned about this symbol and operation, the contracts and
            // rules that bind it, and the catalogued job that may already do the work. A TYPED
            // MESSAGE is a different occasion and does not ask the code lane.
            LaneScope.of(KnowledgeLane.EXPERIENCE, KnowledgeLane.DOMAIN, KnowledgeLane.RULES,
                KnowledgeLane.CODE));
        if (q.isEmpty()) {
            return PriorWork.nothingToAsk();
        }
        // The SAME union recall the front door runs, counted to the pre-advice
        // surface — so the quality ledger records when this surface actually
        // reaches the agent, and when it stays silent, as a side effect of the
        // normal call rather than through a separate feed.
        // THE SURFACE IS THE CALLER'S, not this method's. It was a constant here, and D42 then
        // gave this method a second caller that runs on every tool response — which put the
        // choke's whole traffic into the pre-advice counter and made one rate two populations
        // added together. The constant four lines from it in QualityLedger is the rule against
        // that, and it was written for this exact conflation.
        Map<String, Object> r = retrieval.recall(q, surface, budgetMillis);

        // THE RESULT WORD IS THE ONLY THING THAT DISTINGUISHES THE TWO EMPTIES, and it is read
        // before the rows because the rows cannot tell them apart. `unavailable` strips its
        // half-built answer and then puts `entries` back as an EMPTY LIST — deliberately, so no
        // reader meets a missing key — so the loop below runs cleanly over nothing and this
        // method used to return exactly what a healthy store holding nothing returns.
        //
        // That silent equivalence is what D42 removed, and it is stated as the measured fact it
        // is: an earlier version of this comment claimed the loop would THROW on a stripped
        // `entries`, and a mutation disabling this branch disproved it — the test failed on the
        // outcome assertion, not with a NullPointerException, and the run's own log showed the
        // budget firing while the rendering completed. The check is no less necessary for that;
        // only its reason changed, from avoiding a throw to preserving a distinction.
        if (ExperienceRetrieval.RESULT_UNAVAILABLE.equals(r.get("result"))) {
            return PriorWork.notLooked(String.valueOf(r.get("reason")));
        }

        // A vouched entry leads; failing that, the meaning/word NOMINEES recall
        // already computed. Before Sprint 27a this method read `entries` only
        // and returned nothing on RESULT_ANALOGY, so every analogy the recall
        // produced here was thrown away (F3). Both are rendered through the one
        // carrier, so the ontology's rules cannot hold on the front door and
        // lapse here.
        List<Map<String, Object>> vouched = (List<Map<String, Object>>) r.get("entries");
        List<Map<String, Object>> nominees =
            (List<Map<String, Object>>) r.getOrDefault("analogies", List.of());
        List<String> advice = new ArrayList<>();
        for (Map<String, Object> e : vouched) {
            if (advice.size() >= MAX_ADVICE) {
                break;
            }
            advice.add(renderAdvice(e));
        }
        for (Map<String, Object> a : nominees) {
            if (advice.size() >= MAX_ADVICE) {
                break;
            }
            advice.add(renderAdvice(a));
        }
        return PriorWork.looked(advice);
    }

    /**
     * One answer from the store, carrying WHETHER IT WAS READ as well as what it said.
     *
     * <p>Three states, and the middle one is the whole reason this type exists:</p>
     * <ul>
     *   <li><b>read, and it had something</b> — {@code advice} non-empty;</li>
     *   <li><b>NOT READ</b> — {@code looked()} false, {@code reason} the recall's own sentence.
     *       A caller must say so rather than fall silent, because silence here is read as
     *       "nothing is known", which is a claim nobody made;</li>
     *   <li><b>read, and it genuinely had nothing</b> — {@code looked()} true, {@code advice}
     *       empty. An absence, and reporting it as one is correct.</li>
     * </ul>
     *
     * <p>{@code looked()} is derived rather than stored, so the two cannot drift: there is one
     * fact — the result word — and the question is asked of it.</p>
     */
    public record PriorWork(List<String> advice, String result, String reason) {

        /** The store answered; {@code advice} is what it said, empty included. */
        static PriorWork looked(List<String> advice) {
            return new PriorWork(List.copyOf(advice), ExperienceRetrieval.RESULT_MATCH, null);
        }

        /** The store was never read to a conclusion — the deadline, or a saturated pool. */
        static PriorWork notLooked(String reason) {
            return new PriorWork(List.of(), ExperienceRetrieval.RESULT_UNAVAILABLE, reason);
        }

        /**
         * There was no question to ask: the cue was empty, so no read was attempted and none
         * was owed. Counted as HAVING LOOKED, deliberately — a caller that supplied nothing to
         * look up is not owed a "we did not look" line about the store, which would report an
         * outage that did not happen.
         */
        static PriorWork nothingToAsk() {
            return new PriorWork(List.of(), ExperienceRetrieval.RESULT_ABSENCE, null);
        }

        /** False only when the store was not read to a conclusion. */
        public boolean looked() {
            return !ExperienceRetrieval.RESULT_UNAVAILABLE.equals(result);
        }
    }

    @Override
    public void record(Outcome outcome) {
        if (outcome == null) {
            return;
        }
        // Write admission — only what JDT cannot re-derive (negatives / hazards / failures).
        String type;
        if (Outcome.ROLLED_BACK.equals(outcome.status())) {
            type = "failure_mode";
        } else if (Outcome.FLAGGED.equals(outcome.status())) {
            type = "hazard";
        } else {
            log.debug("record: APPLIED is JDT-derivable — not admitted ({})", outcome.operation());
            return;                                  // successful apply = the derivable normal path
        }

        String target = outcome.target();
        String op = outcome.operation() == null ? "operation" : outcome.operation();
        String summary = op + " " + outcome.status().toLowerCase(Locale.ROOT)
            + (target != null && !target.isBlank() ? " on " + target : "");
        if (isDuplicate(target, summary)) {
            log.debug("record: duplicate candidate skipped ({})", summary);
            return;
        }

        SymbolFact.Builder fb = SymbolFact.of(type, summary, Confidence.LOW);
        if (looksLikeFqn(target)) {
            fb.symbol(target);
        }
        List<String> notes = outcome.notes();
        if (notes != null && !notes.isEmpty()) {
            fb.details(String.join("; ", notes));
        }

        ExperienceEntry.Builder eb = ExperienceEntry.of(fb.build())
            .operation(outcome.operation())
            .scopeKind(looksLikeFqn(target) ? "symbol" : null);

        // This writer goes STRAIGHT TO THE STORE, not through the record verb, so
        // EntryForm's gate never sees it — and `failure_mode` is an experience type,
        // which owes a situation and an outcome. Without these two lines it mints
        // exactly the rows the front door refuses, and nothing anywhere reports it:
        // the gate guards a VERB, and a verb cannot guard a caller that does not use it.
        //
        // Both facts are already in hand, so the form holds by construction rather than
        // by a runtime check that could only drop the record. The situation is
        // task-shaped — what you are doing when this applies — and deliberately omits
        // the target, which is already carried as the symbol anchor; a situation naming
        // one class would match only that class. The outcome is not a judgement call: a
        // plan that rolled back is the definition of failed_avoid.
        if (EntryForm.EXPERIENCE_TYPES.contains(type)) {
            String situation = "when applying a " + op + " refactoring plan";
            eb.situation(situation).verdict("failed_avoid").form(EntryForm.formOf(situation));
        }

        if (outcome.undoChangeId() != null && !outcome.undoChangeId().isBlank()) {
            eb.addLink("undo", outcome.undoChangeId());
        }
        store.put(eb.build());
        log.info("recorded {} candidate: {}", type, summary);
    }

    /** Dedup by scope + summary — a symbol-scoped candidate already saying the same thing. */
    private boolean isDuplicate(String target, String summary) {
        if (!looksLikeFqn(target)) {
            return false;
        }
        String norm = H2ExperienceStore.normalize(summary);
        for (StoredEntry e : store.query(
                new RecallQuery(target, null, null, null, null, LaneScope.of(KnowledgeLane.EXPERIENCE)))) {
            if (H2ExperienceStore.normalize(e.summary()).equals(norm)) {
                return true;
            }
        }
        return false;
    }

    private static String renderAdvice(Map<String, Object> e) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(e.get("type")).append("] ").append(e.get("summary"));
        Object status = e.get("status");
        if (status != null) {
            sb.append(" (").append(status).append(')');
        }
        Object details = e.get("details");
        if (details != null) {
            sb.append(" — ").append(details);
        }
        if (e.get("resolved_pointer") instanceof Map<?, ?> p && Boolean.TRUE.equals(p.get("stale"))) {
            sb.append(" [pointer stale]");
        }
        // Sprint 27a D2: when this is an analogy (a nominee, not a vouched
        // entry), it MUST carry its basis in words and its provenance — the
        // rendering contract's positive half. A bare summary would let a nominee
        // read as a vouched fact, the distinction the whole retrieval ontology
        // turns on.
        if (e.get("basis") instanceof List<?> basis && !basis.isEmpty()) {
            sb.append(" — ").append(String.join(", ",
                basis.stream().map(String::valueOf).toList()));
        }
        Object provenance = e.get("provenance");
        if (provenance != null) {
            sb.append(" (").append(provenance).append(')');
        }
        Object framing = e.get("framing");
        if (framing != null) {
            sb.append(" [").append(framing).append(']');
        }
        return sb.toString();
    }

    /** A dotted identifier with no path/space separators — a Java FQN (possibly {@code #member}). */
    private static boolean looksLikeFqn(String s) {
        return s != null && s.contains(".") && !s.contains("/") && !s.contains(" ") && !s.endsWith(".java");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
