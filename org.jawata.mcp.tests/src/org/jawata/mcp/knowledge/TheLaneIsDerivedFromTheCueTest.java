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

    /**
     * The package the {@code area} row is anchored to — deliberately NOT the anchor's own, so
     * the row is reached by the cue's WORDS rather than by its address.
     *
     * <p>This javadoc first ended "so a symptom cue cannot fit it and it can only arrive as an
     * analogy". Both halves are struck: the arrival route is NOT established (see
     * {@link #an_area_row_is_excluded_by_the_derivation_too}), and the case's own proof-of-life
     * shows the row IS returned on a symptom cue once the lane is named. A re-audit found this
     * sentence standing 47 lines above the withdrawal of the same claim, in a javadoc the
     * withdrawing commit itself wrote.</p>
     */
    private static final String AREA_PACKAGE = "com.example.otherarea";

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

        // C14a — THE OTHER CODE-LANE KIND. `CodeKind` declares JOB and AREA, and the three
        // rows above only ever exercise the first.
        //
        // An `area` is NOT address-bound, so `KnowledgeKind.of` answers EXPERIENCE where an
        // anchored `job` answers FACT — a different route through the retrieval, which is what
        // makes this row worth having rather than a fourth near-duplicate. It is anchored to a
        // PACKAGE other than the anchor's, so a symptom cue cannot FIT it.
        //
        // IT WAS ADDED TO REACH THE ANALOGY-POOL EXCLUSION AND IT DOES NOT. Two mutations say
        // so jointly: disabling the analogy exclusion alone left this case GREEN, and disabling
        // the KEYWORD filter alone turned it RED. So the keyword filter is what excludes this
        // row and the analogy pool is not involved. The withdrawn claim is kept visible here
        // and in the case's own javadoc rather than quietly replaced.
        store.put(ExperienceEntry.of(
            SymbolFact.of("area", "This package is where the " + SHARED_WORDS
                + " is decided", Confidence.MEDIUM)
                .scope(List.of(AREA_PACKAGE), List.of()).build())
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

    /**
     * C14a — THE DERIVATION EXCLUDES AN {@code area} ROW TOO, not only a {@code job}.
     *
     * <p>{@code CodeKind} declares TWO code-lane kinds and every case above exercises one of
     * them. This exercises the other, and it is not a restatement: an {@code area} is anchored
     * to a PACKAGE rather than a symbol and is not address-bound, so it classifies as an
     * EXPERIENCE where an anchored {@code job} classifies as a FACT. Those two travel
     * different routes through the retrieval, and only this case covers the second.</p>
     *
     * <p><b>WHAT THIS CASE DOES NOT DO, and it was written believing the opposite.</b> It was
     * added to close the analogy-pool exclusion that round 1 declared uncovered, and it does
     * not reach it. TWO mutations establish that jointly, and neither alone would have:</p>
     * <ul>
     *   <li>the ANALOGY exclusion disabled alone — this case stayed GREEN, so that branch is
     *       not what excludes the row;</li>
     *   <li>the KEYWORD filter disabled alone — this case went RED, so that filter is.</li>
     * </ul>
     *
     * <p>So the assertion below is LIVE rather than vacuous — it reddens on a real mutation —
     * and what it covers is the keyword filter's exclusion of an {@code area} row, not the
     * analogy pool's. The javadoc that stood here claimed the analogy pool was "its only route
     * in"; that is withdrawn, and the assertion was NOT weakened to match, because the case
     * measures something real under a corrected description.</p>
     *
     * <p><b>WHAT IS STILL NOT MEASURED, stated so the next reader does not inherit a guess.</b>
     * Which response key the row arrives under once the filter is off is unknown — the helper
     * merges {@code entries}, {@code analogies} and {@code candidates}, so no run of this test
     * can answer it. A re-audit traced a LEXICAL nomination route into the pool that needs no
     * embedding corpus ({@code AnalogyPolicy} floors the lexical stream at negative infinity,
     * and this row's summary carries the cue verbatim), so round 1's "it needs a corpus" is
     * probably wrong — but whether that route ever delivers this row is open. An earlier
     * version of this paragraph asserted the obstacle was ORDER and routed an architecture
     * conclusion off that; the attribution was inference, not measurement, and is withdrawn.
     * <b>The analogy-pool exclusion remains exercised by nothing.</b></p>
     */
    @Test
    void an_area_row_is_excluded_by_the_derivation_too() {
        Set<String> overridden = recalled(bySymptom("code"));
        assertTrue(overridden.contains("area"),
            () -> "PROOF OF LIFE: the area row must be REACHABLE on a symptom cue when the"
                + " caller names the code lane, or the exclusion below measures nothing. Found "
                + overridden);

        Set<String> derived = recalled(bySymptom(null));
        assertFalse(derived.contains("area"),
            () -> "an area row describing what a package is FOR must not answer a cue about a"
                + " symptom — the derivation covers BOTH code-lane kinds, not just the job."
                + " Found " + derived);
    }
}
