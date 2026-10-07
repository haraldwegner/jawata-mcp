package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g D42(b) — <b>the lane is DERIVED from the cue rather than asked for</b>, so an
 * uncategorised question cannot silently answer out of the code lane.
 *
 * <p>The deliverable's reason is cost, in the sprint's own words: <i>"experience is small and can
 * be asked every time; the code lane is ~10,000 rows and must be asked only of a code question."</i>
 * So the rule has teeth in ONE direction — keep code out of a non-code question — and the tests
 * below are shaped to that claim and not to a wider one.</p>
 *
 * <h2>Why the override case IS the proof of life</h2>
 *
 * <p>Every exclusion assertion here needs a companion showing the excluded row was REACHABLE by
 * that very cue, or it is equally true of a store that answers nothing. The companion cannot be
 * an unfiltered recall, because unfiltered is the case under test. So it is the OVERRIDE: the same
 * cue with {@code lane=code} must return the job row. That one case proves the row is findable,
 * proves the caller can override, and is the clause the deliverable asks to have tested.</p>
 *
 * <h2>The fixture is built so only the lane can separate the rows</h2>
 *
 * <p>The job and the lesson carry the SAME anchor and the SAME distinctive words in their
 * summaries, so a symptom cue reaches both through the summary match and a symbol cue reaches
 * both through the anchor. They differ in TYPE alone, which is what decides the lane.</p>
 */
class TheLaneIsDerivedFromTheCueTest {

    /** The anchor both rows carry, so a symbol cue cannot be what separates them. */
    private static final String ANCHOR = "com.example.lanecue.Ledger";

    /** Words in BOTH summaries, so a symptom cue reaches both through the summary match. */
    private static final String SHARED_WORDS = "ledger totals drift after an amendment";

    private H2ExperienceStore store;
    private ExperienceRetrieval retrieval;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        retrieval = ExperienceRetrieval.keywordOnly(store, () -> null);

        // The CODE-lane row: a job is what one member is for, regenerated from the code.
        store.put(ExperienceEntry.of(
            SymbolFact.of("job", "Reports that " + SHARED_WORDS, Confidence.MEDIUM)
                .symbol(ANCHOR).build())
            .status(ExperienceEntry.ACCEPTED).build());

        // The EXPERIENCE-lane row, on the same anchor and sharing the same words.
        store.put(ExperienceEntry.of(
            SymbolFact.of("lesson", "When " + SHARED_WORDS + ", reconcile before trading",
                Confidence.MEDIUM).symbol(ANCHOR).build())
            .status(ExperienceEntry.ACCEPTED)
            .situation("when the ledger and the broker disagree")
            .verdict("worked")
            .build());

        // A DOMAIN-lane row on the same anchor. It pins the reading this build took: the
        // derivation excludes CODE and says nothing about domain, so an api_contract anchored
        // to a symbol must survive a non-code cue. A later tidy-up to exhaustive lane sets —
        // "symbol/package means code and experience, full stop" — turns this red, which is the
        // whole reason it is here rather than in a comment.
        store.put(ExperienceEntry.of(
            SymbolFact.of("api_contract", "The " + SHARED_WORDS + " only across a settlement",
                Confidence.MEDIUM).symbol(ANCHOR).build())
            .status(ExperienceEntry.ACCEPTED).build());
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    /**
     * Every type the answer carries, under EVERY key a producer here writes.
     *
     * <p>A recall answers under {@code entries} and {@code analogies}; a NOMINATION answers
     * under {@code candidates}, because ranking claims nothing and the result word is
     * {@code nominated} rather than a match. An earlier version of this helper read the recall's
     * two keys only, so every nomination assertion in this class ran over an empty set — the
     * exclusion half passed having looked at nothing, and the override half is what caught it.
     * That is this sprint's own recurring defect: a reader assuming the key the rows arrive
     * under instead of asking the producer.</p>
     */
    @SuppressWarnings("unchecked")
    private Set<String> typesFrom(Map<String, Object> result) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String key : List.of("entries", "analogies", "candidates")) {
            Object held = result.get(key);
            if (held instanceof List<?> l) {
                rows.addAll((List<Map<String, Object>>) l);
            }
        }
        Set<String> types = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            Object t = row.get("type");
            if (t != null) {
                types.add(String.valueOf(t));
            }
        }
        return types;
    }

    private Set<String> recalled(RecallQuery q) {
        return typesFrom(retrieval.recall(q, QualityLedger.SURFACE_QUESTION_HOOK, 15_000));
    }

    private static RecallQuery bySymptom(String lane) {
        return new RecallQuery(null, null, null, SHARED_WORDS, null, lane);
    }

    private static RecallQuery bySymbol(String lane) {
        return new RecallQuery(ANCHOR, null, null, null, null, lane);
    }

    /**
     * THE PROOF OF LIFE AND THE OVERRIDE, in one case. The job row is reachable by the symptom
     * cue when the caller asks for its lane by name — so its absence below is the derivation's
     * doing, and a caller who wants the code lane can still have it.
     */
    @Test
    void the_caller_can_override_the_derivation_and_reach_the_code_lane() {
        Set<String> types = recalled(bySymptom("code"));

        assertTrue(types.contains("job"),
            () -> "lane=code on a symptom cue must still answer the job row — without this the"
                + " exclusion below is untestable and this build is unfalsifiable. Found " + types);
    }

    @Test
    void a_symptom_cue_is_not_answered_out_of_the_code_lane() {
        Set<String> types = recalled(bySymptom(null));

        assertFalse(types.contains("job"),
            () -> "A SYMPTOM IS NOT A CODE QUESTION. The job row shares this cue's words and its"
                + " anchor, so only the derived lane can exclude it — drop the derivation and"
                + " this is what goes red. Found " + types);
        assertTrue(types.contains("lesson"),
            () -> "and the experience lane still answers, so this is an exclusion rather than an"
                + " empty result: " + types);
    }

    /**
     * THE DISCRIMINATOR. Without it, "a symptom cue excludes code" is equally satisfied by a
     * build that excludes the code lane from everything — which would make the ~10,000 rows the
     * cataloguer writes unreachable by the one cue shape they exist to answer.
     */
    @Test
    void a_symbol_cue_is_a_code_question_and_does_reach_the_code_lane() {
        Set<String> types = recalled(bySymbol(null));

        assertTrue(types.contains("job"),
            () -> "a symbol cue IS asking about code, so the code lane must answer it with no"
                + " lane named at all. Found " + types);
    }

    /**
     * The reading this build took, pinned. The deliverable names code, experience and patterns;
     * the product has a fourth lane, and {@code api_contract} is in it while being anchored to
     * a symbol. Excluding it would be knowledge lost to satisfy a rule that was never about it.
     */
    @Test
    void a_domain_fact_on_the_same_anchor_survives_a_non_code_cue() {
        Set<String> types = recalled(bySymptom(null));

        assertTrue(types.contains("api_contract"),
            () -> "the derivation keeps CODE out of a non-code question; it says nothing about"
                + " domain, and an api_contract anchored to this symbol is exactly the kind of"
                + " thing a symptom cue should still find. Found " + types);
    }

    /**
     * A prose question is never code-shaped, so the nomination path excludes the code lane
     * without being told to — and the deliverable is sharpest here: a question in words, ranked
     * by meaning against rows describing what members are for, reads as if the store had an
     * opinion about the question.
     */
    @Test
    void a_prose_nomination_is_not_answered_out_of_the_code_lane() {
        Set<String> overridden = typesFrom(retrieval.nominate(SHARED_WORDS, "code", 15_000));
        assertTrue(overridden.contains("job"),
            () -> "PROOF OF LIFE: lane=code must nominate the job row, or the exclusion below"
                + " measures nothing. Found " + overridden);

        Set<String> derived = typesFrom(retrieval.nominate(SHARED_WORDS, null, 15_000));
        assertFalse(derived.contains("job"),
            () -> "prose must never reach the code lane — the deliverable's sharpest clause."
                + " Found " + derived);
    }
}
