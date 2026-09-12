package org.jawata.mcp.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
     * The relations an entry's links may carry — DERIVED, so a typo cannot make a
     * link silently unreachable.
     *
     * <p>It was documented in one schema string and enforced nowhere, and the
     * documented list matched none of the writers: maintenance writes
     * {@code related} for a markdown link, the advisor writes {@code undo} for a
     * rollback handle, and an agent's {@code rel} was stored verbatim whatever it
     * said. A vocabulary that no writer uses and no reader checks is a comment.</p>
     *
     * <p><b>{@code cured_by} is the new one, and it points at a jawata CAPABILITY
     * rather than a code address</b> — {@code find_quality_issue(kind=…)},
     * {@code seat:refactor}. It is deliberately not {@code fixed_by}: that means
     * "what fixed THIS instance", which is history, while a cure claims "this is
     * what fixes ANY instance", which is a standing instruction.</p>
     *
     * <p><b>The boundary, which is the part that would otherwise be got wrong: fill
     * a cure ONLY when the remedy needs no judgement.</b> A cure sitting on an
     * entry will be run. One that is right half the time is worse than none,
     * because the half it is wrong about arrives with the store's authority behind
     * it. An experience whose remedy is two-phase — read the exception, work out
     * why it threw, then decide — earns {@code detected_by} and no cure, because a
     * detector deterministically finds the sites while the fix genuinely varies.</p>
     */
    public static final Set<String> LINK_RELS = Set.of(
        "handled_by", "fixed_by", "detected_by", "supersedes", "cured_by",
        // Written by the engine itself, not by an author: a markdown link between
        // ingested notes, and the rollback handle the advisor records for a plan.
        "related", "undo",
        // Sprint 28f Stage 5: a promoted RULE points back at the entries it was
        // drawn from. It is not `supersedes` — the sources are not replaced and go
        // on answering as themselves; the rule is a general instruction distilled
        // from them, and deleting the sources would leave it unaccountable rather
        // than merely unlinked.
        "derived_from");

    /** The link vocabulary as prose, derived so the schema cannot drift from the set. */
    public static String linkVocabulary() {
        return String.join(" | ", new java.util.TreeSet<>(LINK_RELS));
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
        return check(type, summary, symptoms, situation, verdict, null);
    }

    /**
     * As {@link #check(String, String, List, String, String)}, plus the rules a JOB owes
     * against the member it is anchored to — Sprint 28f Stage 7.
     *
     * <p><b>An overload rather than a widened signature, and the reason is the count.</b>
     * {@code check} has twelve references, four of them production, and none of those
     * callers holds an anchor or has any use for one. Widening would have made every one
     * of them pass a null to satisfy a rule about a type they never record. The five-argument
     * form keeps meaning exactly what it meant and delegates with no anchor, which is also
     * what makes {@code without_an_anchor_the_job_rules_cannot_fire} a real assertion rather
     * than a restatement.</p>
     *
     * <p><b>What is NOT enforced here, stated rather than left to be discovered.</b> The
     * stage's deliverable also says a job's summary "has a verb". There is no sound
     * mechanical test for that — every cheap proxy refuses good summaries — and a guessy
     * gate on a store an agent writes into all day costs more than the shape it catches.
     * What IS enforced is the failure that actually occurs: a summary that restates the
     * identifier instead of saying what it is FOR. That subsumes the common verb case,
     * because the identifier usually carries the verb already. Raised at C7 rather than
     * shipped as a check that guesses.</p>
     *
     * @param anchor the member this entry hangs on ({@code pkg.Type#member}), or
     *               {@code null} when there is none — with no anchor there is nothing to
     *               call a restatement OF, so the comparison stays silent
     */
    public static Optional<Refusal> check(String type, String summary, List<String> symptoms,
                                          String situation, String verdict, String anchor) {
        Optional<AdmissionPolicy.Refusal> shape = AdmissionPolicy.check(summary, symptoms);
        if (shape.isPresent()) {
            return Optional.of(new Refusal(shape.get().field(), shape.get().message()));
        }

        // Sprint 28c D9 — the shapes that are not knowledge whatever they are
        // labelled: a log line, a fallback slip, a point-in-time status, a
        // compaction artifact, a numbered heading, a summary too short to be a
        // claim. EVERY type, because a log line labelled domain_fact is still a
        // log line, and this is the choke both `record` and the md ingest already
        // pass through — one filter, not two stances that drift.
        StoryTemplate.Refusal story = StoryTemplate.refuse(summary);
        if (story != null) {
            return Optional.of(new Refusal("summary", story.why()));
        }

        // A situation that IS given must still be a condition rather than a
        // location, whatever the type — a wrong situation is worse than none,
        // because it matches confidently.
        String given = situation == null ? "" : situation.strip();
        if (!given.isEmpty()
                && AdmissionPolicy.misplacedInSituation(given)) {
            return Optional.of(new Refusal("situation",
                "situation '" + situation + "' is a location, not a condition."
                + " RULE: a situation says WHEN an entry applies, never WHERE a"
                + " file lives — a path answers a question this store is not for."
                + " REPHRASE: " + SITUATION_SHAPES
                + " The path belongs in 'details'."));
        }

        // Sprint 28f Stage 7 — the CODE lane's own form, and it binds BEFORE the
        // experience branch because a job is neither an experience nor a bare fact: it
        // owes no situation and no outcome, and it does owe something the others do not
        // — that it says what the member is FOR rather than what the member is CALLED.
        Optional<Refusal> derived = checkDerived(type, summary, anchor);
        if (derived.isPresent()) {
            return derived;
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
     * The CODE lane's form — Sprint 28f Stage 7. Empty for every other type.
     *
     * <p>A job says what one member is FOR. The failure it refuses is the one an agent
     * cataloguing code falls into by default: writing the signature back in prose. Both
     * {@code "parse(ICompilationUnit)"} and {@code "parse compilation unit"} restate what
     * the reader can already see, cost a row, and answer no question — and once stored
     * they rank against real questions forever.</p>
     *
     * <p>The comparison is on WORDS, not on the string: an identifier and its re-spaced
     * prose are the same statement, and only splitting camel case sees that. A summary
     * that adds anything the identifier does not carry is admitted, which is the whole
     * bar — it need not be eloquent, it must say something more than the name.</p>
     */
    private static Optional<Refusal> checkDerived(String type, String summary, String anchor) {
        String t = type == null ? "" : type.strip().toLowerCase(Locale.ROOT);
        if (!KnowledgeLane.CODE_TYPES.contains(t)) {
            return Optional.empty();
        }
        String said = summary == null ? "" : summary.strip();
        if (said.indexOf('(') >= 0) {
            return Optional.of(new Refusal("summary",
                "summary '" + said + "' carries a signature, so it restates what the reader"
                + " can already see. RULE: a job says what the member is FOR — the thing a"
                + " signature cannot tell you — and the parameter list is already in the"
                + " code this row points at."
                + " REPHRASE: say what a caller gets out of it, in the words a person would"
                + " use asking for it."));
        }
        if (anchor == null || anchor.isBlank()) {
            // Nothing to call a restatement OF. The rule that compares them stays silent
            // rather than guessing — an anchorless job is refused by nothing here.
            return Optional.empty();
        }
        List<String> saidWords = words(said);
        List<String> nameWords = words(memberOf(anchor));
        if (!nameWords.isEmpty() && saidWords.equals(nameWords)) {
            return Optional.of(new Refusal("summary",
                "summary '" + said + "' restates the member's own name and adds nothing."
                + " RULE: the identifier is already at the anchor this row carries, so a"
                + " summary that spells it out — with spaces or without — costs a row and"
                + " answers no question."
                + " REPHRASE: say what it is FOR, not what it is called."));
        }
        return Optional.empty();
    }

    /** The member half of {@code pkg.Type#member}, or the whole string when it has none. */
    private static String memberOf(String anchor) {
        int hash = anchor.indexOf('#');
        return hash < 0 ? anchor : anchor.substring(hash + 1);
    }

    /** Lower-case words, splitting camel case as well as punctuation and spaces. */
    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean boundary = Character.isUpperCase(c) && word.length() > 0
                && Character.isLowerCase(text.charAt(i - 1));
            if (!Character.isLetterOrDigit(c) || boundary) {
                if (word.length() > 0) {
                    out.add(word.toString().toLowerCase(Locale.ROOT));
                    word.setLength(0);
                }
            }
            if (Character.isLetterOrDigit(c)) {
                word.append(c);
            }
        }
        if (word.length() > 0) {
            out.add(word.toString().toLowerCase(Locale.ROOT));
        }
        return out;
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
