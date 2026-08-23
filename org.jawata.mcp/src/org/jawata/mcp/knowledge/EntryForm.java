package org.jawata.mcp.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Sprint 28c (D3) — what an entry IS, enforced at every write.
 *
 * <p>An entry is an EXPERIENCE, not a note: a <b>situation</b> saying when it
 * applies, a <b>principle</b> that is one judgeable sentence, and an
 * <b>outcome</b>. Until now the store accepted anything with a summary, and the
 * cost is visible in its own contents — a recall that answers with "Required
 * follow-up", "Test plan" or "Summary table" has spent the reader's attention
 * and told them nothing. Those rows got in because nothing was standing here.</p>
 *
 * <p><b>This composes {@link AdmissionPolicy} rather than re-deriving it.</b>
 * That class's regexes mirror a committed derivation script
 * ({@code embed-goldens/derive_admission.py}) and were measured against the real
 * corpus; a second copy of those judgements would drift from the script silently
 * and no test would notice. So the shape checks stay there and this class adds
 * only what is new: is there a situation, and is there an outcome.</p>
 *
 * <p>Every refusal teaches, in AdmissionPolicy's own voice — what is wrong, the
 * RULE behind it, and a concrete rephrase. A gate that refuses without teaching
 * does not improve the store; it just moves the problem to the author, who will
 * write the same thing again with a word changed.</p>
 */
public final class EntryForm {

    private EntryForm() {
    }

    /**
     * The closed outcome vocabulary.
     *
     * <p>{@code unproven} covers knowledge we hold but have not yet tested — an
     * entry whose outcome is genuinely undetermined must be able to SAY so,
     * rather than have a verdict invented for it.</p>
     */
    public static final Set<String> VERDICTS = Set.of("worked", "failed_avoid", "unproven");

    /**
     * The types that ARE experiences, and are therefore held to the experience form.
     *
     * <p><b>Not every entry is a lesson</b> (Harald, 2026-08-21: "you cannot just
     * form everything upfront into lessons"). The store records domain facts, API
     * contracts and naming conventions beside lessons and failure modes, and those
     * did not "turn out" any way at all. Where a file lives, what a header means,
     * which flag the importer reads — none of it has an outcome, and demanding one
     * would do one of two harmful things: turn away true knowledge, or teach
     * authors to attach a verdict they never earned. The second is worse, because
     * the store then ranks on fiction.</p>
     *
     * <p>So the form binds where it means something. A lesson without an outcome is
     * genuinely incomplete — the outcome IS the lesson — and one without a situation
     * can only ever be found by resemblance. A fact is retrieved by its ANCHOR
     * (symbol, package, operation), which this store has always done well, and needs
     * neither field to be useful.</p>
     *
     * <p>Everything still passes the shape checks below: a heading is not knowledge
     * whatever its type, and a file path is not an observation.</p>
     */
    public static final Set<String> EXPERIENCE_TYPES = Set.of("lesson", "failure_mode");

    /**
     * True when {@code type} is held to the situation+outcome form.
     *
     * <p>PRIVATE deliberately. It was written public because Stage 9's migration
     * will plainly want it, and the C5 wiring check found it had no caller
     * outside this class — which is speculative generality, the thing the
     * architect seat flags. Stage 9 widens it in one line, when there is a
     * caller to widen it for.</p>
     */
    private static boolean isExperience(String type) {
        return type != null && EXPERIENCE_TYPES.contains(type.strip());
    }

    /** A refusal names the field and carries the whole teaching message. */
    public record Refusal(String field, String message) {
    }

    /**
     * The three shapes a usable situation takes, in the words an author needs.
     *
     * <p>ONE constant, rendered into the tool schema every client loads AND into
     * the refusal an author sees at the moment they got it wrong. Two copies of
     * this text would drift, and the drift would be invisible: the schema teaches
     * before the mistake, the refusal teaches after it, and nothing compares them.
     * The verdict vocabulary was re-typed as a literal in exactly that way until
     * it was made to derive from {@link #VERDICTS}.</p>
     *
     * <p>Derived from writing one real entry, where four attempts were rejected.
     * Every one of them was a valid CONDITION — which is all the old guidance
     * asked for — and every one described HOW THE SYSTEM WORKS ("when a suite
     * runner decides green or red from the counts a framework reports"). That is
     * a fourth shape: accurate, unfalsifiable, and matching nothing. The test is
     * whether a reader can answer "yes, that is me, right now" without
     * interpreting.</p>
     */
    public static final String SITUATION_SHAPES =
        "A situation is a GREP, a TASK, or a NUMBER."
        + " A grep — something you can look up in the code in front of you:"
        + " \"when a test class declares @BeforeAll or @AfterAll\"."
        + " A task — what you are doing right now:"
        + " \"when amending an order that is already partially filled\"."
        + " A number — a value you can read off an output:"
        + " \"when a test run reports a class-level exception count above zero\"."
        + " If it is none of the three it describes how the system works, which is"
        + " true during every call and tells no one whether this entry is for them.";

    /**
     * Check a new entry's form. Empty result = admitted.
     *
     * <p>The shape checks apply to EVERY type. The situation+outcome requirements
     * apply only to the types in {@link #EXPERIENCE_TYPES} — see that field for
     * why forcing them on a domain fact is worse than not having them.</p>
     *
     * @param type      the entry type; decides whether the experience form binds
     * @param summary   the principle — one judgeable sentence
     * @param symptoms  how the problem looked, in words
     * @param situation when the entry applies, as a condition (experiences only)
     * @param verdict   one of {@link #VERDICTS} (experiences only)
     */
    public static Optional<Refusal> check(String type, String summary, List<String> symptoms,
                                          String situation, String verdict) {
        Optional<AdmissionPolicy.Refusal> shape = AdmissionPolicy.check(summary, symptoms);
        if (shape.isPresent()) {
            return Optional.of(new Refusal(shape.get().field(), shape.get().message()));
        }

        // A situation that IS given must still be a condition rather than a
        // location, whatever the type — a wrong situation is worse than none,
        // because it matches confidently.
        String given = situation == null ? "" : situation.strip();
        if (!given.isEmpty() && AdmissionPolicy.misplaced(AdmissionPolicy.classify(given))) {
            return Optional.of(new Refusal("situation",
                "situation '" + situation + "' is a location, not a condition."
                + " RULE: a situation says WHEN an entry applies, never WHERE the"
                + " code lives — a symbol or path matches everything in it and"
                + " distinguishes nothing."
                + " REPHRASE: " + SITUATION_SHAPES
                + " The symbol belongs in 'symbol', the path in 'details'."));
        }

        if (!isExperience(type)) {
            // A fact, a contract, a convention. It is retrieved by its anchor and
            // owes no outcome. Nothing further to require.
            return Optional.empty();
        }

        String when = given;
        if (when.isEmpty()) {
            return Optional.of(new Refusal("situation",
                "this entry says what was learned but not WHEN it applies."
                + " RULE: an experience carries the condition under which it holds,"
                + " so the engine can decide whether it is relevant to the call in"
                + " front of it — without one, the entry can only ever be found by"
                + " resemblance."
                + " REPHRASE: " + SITUATION_SHAPES));
        }
        // (A situation that is a location was already refused above, for every
        // type — a wrong condition matches confidently, which is worse than none.)

        String outcome = verdict == null ? "" : verdict.strip();
        if (outcome.isEmpty()) {
            return Optional.of(new Refusal("verdict",
                "this entry has no outcome, so nothing can be learned from it."
                + " RULE: an experience records how it TURNED OUT — " + vocabulary() + "."
                + " If the outcome is genuinely not known yet, that is 'unproven',"
                + " which is an answer; being silent is not."
                + " REPHRASE: add verdict=worked when it held, verdict=failed_avoid"
                + " when it cost you, verdict=unproven when it is still open."));
        }
        if (!VERDICTS.contains(outcome)) {
            return Optional.of(new Refusal("verdict",
                "verdict '" + verdict + "' is not one of the outcomes this store records."
                + " RULE: the outcome vocabulary is closed, because retrieval ranks on"
                + " it and a free-text verdict cannot be ranked — " + vocabulary() + "."
                + " REPHRASE: pick the one that is true; 'unproven' is the honest"
                + " choice when it has not been settled."));
        }
        return Optional.empty();
    }

    /**
     * The form stamp: 1 when the entry carries a situation, else unclassified.
     *
     * <p>Lives here because it was written out by hand at three write sites — the
     * record verb, the md ingest's file entry and its section entries — and the
     * rule that {@code form} means "carries a situation" is a definition, not a
     * local convenience. Three copies drift; the audit that found a FOURTH
     * un-widened insert site is what this rule is for.</p>
     *
     * <p>Returns {@code null}, never 0: "nobody classified this row" and "this row
     * is classified as legacy" are different claims, and the round trip is
     * asserted on that difference.</p>
     */
    public static Integer formOf(String situation) {
        return situation != null && !situation.isBlank() ? 1 : null;
    }

    /**
     * The vocabulary, listed — enforcing a closed set without naming it makes
     * authors guess. DERIVED from {@link #VERDICTS}: a hand-typed copy of a
     * closed set inside the very class whose javadoc condemns that drift was
     * exactly the defect, and it survived a round of auditing.
     */
    private static String vocabulary() {
        List<String> v = VERDICTS.stream().sorted().toList();
        return String.join(", ", v.subList(0, v.size() - 1)) + " or " + v.get(v.size() - 1);
    }
}
