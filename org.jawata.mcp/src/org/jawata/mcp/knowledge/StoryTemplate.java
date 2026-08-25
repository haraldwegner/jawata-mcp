package org.jawata.mcp.knowledge;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Sprint 28c D9 — what may enter the store, in the shape it must take.
 *
 * <p>The criterion, and everything here is that one sentence made checkable:
 * <b>an entry earns storage only if a future stranger, standing in a situation
 * they can recognise, would act differently for having read it.</b></p>
 *
 * <h2>Two halves, deliberately not one</h2>
 *
 * <p>This class holds the half a machine can decide. The other half — whether a
 * stranger could actually use the story — is decided by a reader with no session
 * context, and its verdict arrives as a stamp the reseed gate checks
 * deterministically. Keeping them apart is the whole design: a gate that tried
 * to judge meaning would either turn away real knowledge or teach authors to
 * dress noise up, and the second is worse, because retrieval then ranks on the
 * dressing.</p>
 *
 * <p><b>The mechanical half is deliberately incomplete, and says so.</b> It
 * catches the shapes that ARE decidable; it does not catch a well-formed
 * platitude, and it is not supposed to. {@code docs/story-template.md} carries
 * the same rules for a human reader, and {@code StoryTemplateTest} asserts every
 * field and every refusal named here appears there — so the documented rule
 * cannot drift from the enforced one.</p>
 *
 * <h2>Why these refusals and not others</h2>
 *
 * <p>Every one below was observed in the real store, not imagined. Harald read
 * back his own entries and found log lines, release announcements, sprint-phase
 * notes, compaction artifacts and rows whose entire summary was a chapter
 * heading. Each refusal names the shape it saw.</p>
 *
 * <p>PURE: text in, a verdict out. No store, no connection, no clock.</p>
 */
public final class StoryTemplate {

    /**
     * One field of the template, with the conditions a story owes it.
     *
     * <p>Conditions are DATA rather than prose baked into a message, because two
     * consumers need them: the refusal a gate emits, and the prompt the cold
     * reader is given. A second copy for the second consumer is how the two
     * start disagreeing about what a good story is.</p>
     */
    public record Field(String name, String question, List<String> conditions) {
    }

    /** What a candidate was refused for, and why that shape cannot be knowledge. */
    public record Refusal(String kind, String why) {
    }

    /** The template, in the order a story is written. */
    public static final List<Field> FIELDS = List.of(
        new Field("situation", "When does this apply?", List.of(
            "self-contained — a reader with no session context can tell whether they are IN it",
            "every referent named: not \"the number\", not \"leadership\", not \"the fix\"",
            "concrete: the observable condition, not the category it belongs to",
            "phrased as a condition, beginning \"when …\"",
            "not an address — a path, a symbol or a flag is an anchor, not a situation")),
        new Field("summary", "What happened, or what to do?", List.of(
            "a claim, not a topic — something a reader can act on or dispute",
            "a heading is not a claim, whatever it is labelled")),
        new Field("details", "Why, and what would a reader do differently?", List.of(
            "the mechanism, the evidence, the cost",
            "artifacts live HERE — paths, ids, flags, commands, versions — never in the"
                + " situation, which has to stay readable by someone who was not there")),
        new Field("outcome", "How did it turn out?", List.of(
            "experiences only: worked / failed_avoid / unproven",
            "a fact, an api_contract, a naming_convention or a reference owes NONE —"
                + " it never turned out any way at all, and inventing one makes"
                + " retrieval rank on fiction")),
        new Field("anchor", "Which code, if any?", List.of(
            "optional; its absence is normal — experience is experience without any code")));

    /**
     * A slip the shell-fallback hook records on every declared fallback.
     *
     * <p>Thousands of them, each saying which tool was reached for and why. That
     * is an audit trail and a capability-gap signal, and it belongs in the tool
     * lane — the store's own per-tool table — where the fallback report reads it.
     * In the knowledge lane it is pure volume: it crowds the budget an answer
     * has while telling a reader nothing they could act on.</p>
     */
    private static final Pattern FALLBACK_SLIP =
        Pattern.compile("^(jawata|goja)-fallback slip:", Pattern.CASE_INSENSITIVE);

    /**
     * A captured log line: a bracketed level, a leading timestamp, or a level
     * followed by a dotted logger name.
     *
     * <p>A log records that something happened. Knowledge is what to do about
     * it, and the two are not the same row.</p>
     */
    private static final Pattern LOG_LINE = Pattern.compile(
        "^\\[[^\\]]{1,64}\\]\\s*(INFO|WARN|WARNING|ERROR|DEBUG|TRACE|FATAL)\\b"
        + "|^\\d{4}-\\d\\d-\\d\\d[T ]\\d\\d:\\d\\d"
        + "|\\b(INFO|WARN|WARNING|ERROR|DEBUG|TRACE|FATAL)\\s+[a-z]\\w*(\\.\\w+){2,}\\s*[-:]");

    /**
     * A point-in-time status: an identifier followed by a SHOUTED status word.
     *
     * <p>"SPRINT 25 EXECUTING", "v2.7.1 RELEASED", "Sprint 28b CLOSED". Each was
     * true when written and is false now, and nothing retires it — so it surfaces
     * forever, outranking things that are still true.</p>
     *
     * <p><b>The capitals are the discriminator, and they are load-bearing.</b>
     * Real knowledge uses the same words inside a sentence: <i>"v3.4.0 shipped
     * semantic recall INERT — all three production sites built it via the
     * no-index constructor"</i> is one of the most useful entries in this store,
     * and it must survive. It does, because "shipped" is not shouted and does not
     * sit immediately after the version. A status NOTE announces; a lesson
     * narrates.</p>
     */
    private static final Pattern STATUS_NOTE = Pattern.compile(
        "^(sprint\\s+[\\w.-]+|v\\d+(\\.\\d+)+|release\\s+[\\w.-]+)\\s+"
        + "(EXECUTING|RELEASED|CLOSED|COMPLETE|COMPLETED|SIGNED|SHIPPED|DONE|FAILED"
        + "|IN PROGRESS|ABANDONED|APPROVED)\\b",
        Pattern.CASE_INSENSITIVE);

    /** The status words above must be SHOUTED to count; see {@link #STATUS_NOTE}. */
    private static final Pattern SHOUTED_STATUS = Pattern.compile(
        "\\b(EXECUTING|RELEASED|CLOSED|COMPLETE|COMPLETED|SIGNED|SHIPPED|DONE|FAILED"
        + "|IN PROGRESS|ABANDONED|APPROVED)\\b");

    /** A transcript's shadow — meaningless to any reader who was not in that session. */
    private static final Pattern COMPACTION = Pattern.compile(
        "context compacted|conversation summary|session is being continued"
        + "|summary of the conversation|continued from a previous conversation",
        Pattern.CASE_INSENSITIVE);

    /**
     * A numbered section heading: "4. Testing", "21. Resolved Questions",
     * "5.5 Edge-Case and Integration Tests".
     *
     * <p>A claim does not begin with its own position in a document. This one
     * matters because the word count below cannot catch a LONG heading, and the
     * store is full of them.</p>
     */
    private static final Pattern SECTION_NUMBER =
        Pattern.compile("^\\d+(\\.\\d+)*\\s*[.)]?\\s+\\S");

    /**
     * Below this, a summary is naming a topic rather than making a claim.
     *
     * <p><b>A judgement, not a measurement, and stated as one</b> — the same
     * honesty {@link LexicalIndex#discriminates} owes its own constant. No
     * distribution was fitted to pick four; the argument is that a claim needs a
     * subject and something said about it, and three words rarely carry both.
     * Every heading Harald found in his own store is one, two or three words:
     * "Overview", "Test plan", "21. Resolved Questions".</p>
     *
     * <p><b>Its cost is real and is not hidden:</b> a genuine three-word claim is
     * refused. That is why the refusal message asks for a rephrase rather than
     * declaring the knowledge worthless — the author is being asked to say the
     * thing, not to go away.</p>
     */
    public static final int MIN_CLAIM_WORDS = 4;

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private StoryTemplate() {
    }

    /**
     * The refusal this summary earns, or {@code null} when nothing mechanical
     * objects to it.
     *
     * <p>{@code null} means "no mechanical objection", NOT "this is a good
     * story" — the difference is the whole point of the two-halves design, and a
     * caller that reads one as the other has re-created the problem this class
     * exists to solve.</p>
     */
    public static Refusal refuse(String summary) {
        if (summary == null || summary.isBlank()) {
            return new Refusal("empty",
                "an entry with no summary claims nothing, so nobody can judge whether it"
                + " applies to them");
        }
        String s = summary.strip();

        if (FALLBACK_SLIP.matcher(s).find()) {
            return new Refusal("fallback_slip",
                "a declared shell-fallback is an audit record, not knowledge. Its"
                + " capability-gap value is read from the tool lane, where it already"
                + " lives; in the knowledge lane it only spends the budget an answer has.");
        }
        if (LOG_LINE.matcher(s).find()) {
            return new Refusal("log_line",
                "this is a log line. A log records that something HAPPENED; an experience"
                + " says what to DO about it. Write the second and put the log in details"
                + " as evidence.");
        }
        if (COMPACTION.matcher(s).find()) {
            return new Refusal("compaction_artifact",
                "this is a transcript's shadow — meaningless to any reader who was not in"
                + " that session. If something durable was learned there, say THAT.");
        }
        if (STATUS_NOTE.matcher(s).find() && SHOUTED_STATUS.matcher(s).find()) {
            return new Refusal("status_note",
                "this is project progress: true when written, false now, and nothing"
                + " retires it — so it would surface forever, outranking things that are"
                + " still true. Progress belongs in the sprint doc and the file memory."
                + " If the episode TAUGHT something, record the lesson instead.");
        }
        if (SECTION_NUMBER.matcher(s).find()) {
            return new Refusal("section_heading",
                "a claim does not begin with its own position in a document. This is a"
                + " heading; say what the section CONCLUDED.");
        }
        if (countWords(s) < MIN_CLAIM_WORDS) {
            return new Refusal("not_a_claim",
                "\"" + s + "\" names a topic; it does not make a claim. REPHRASE as"
                + " something a reader can act on or dispute — at least "
                + MIN_CLAIM_WORDS + " words, because a claim needs a subject and"
                + " something said about it.");
        }
        return null;
    }

    private static int countWords(String s) {
        int n = 0;
        var m = WORD.matcher(s);
        while (m.find()) {
            n++;
        }
        return n;
    }

    /**
     * The template rendered as the prompt a cold reader is given.
     *
     * <p>Built from {@link #FIELDS} rather than written out, so the questions a
     * reviewer is asked and the conditions a gate enforces cannot drift apart.</p>
     *
     * <p><b>Its production caller is {@link #reviewPrompt}</b>, reached from the
     * experience tool's {@code review} verb. It was unwired for two sprints and
     * carried in {@code build/unwired-baseline.txt} with that stated — the review
     * step was deliberately held until the template itself settled, and the
     * template settling is what unblocked it.</p>
     */
    /**
     * The cold reader's brief for ONE candidate entry — the questions plus the
     * candidate itself, ready to hand to an agent with no session context.
     *
     * <p>This is what gives {@link #coldReaderPrompt()} a production caller. The
     * questions live there and are rendered here unchanged, so the brief an agent
     * receives cannot drift from the contract the template publishes.</p>
     *
     * <p><b>The reader is the SECOND opinion, not the first.</b> The deterministic
     * form gate has already run by the time this is built, and its verdict travels
     * beside this prompt rather than inside it: a reader told "the gate refused
     * this" grades the refusal instead of the entry, and the two questions worth
     * asking — is this the right KIND, and would a stranger act on it — are exactly
     * the ones a gate cannot answer.</p>
     *
     * <p><b>What it cannot do, stated so no caller relies on it:</b> it cannot check
     * a fact. An entry can be fluent, correctly scoped, concretely nouned and false,
     * and every reader will pass it. That is bounded by the provenance rule on the
     * why, not here.</p>
     */
    public static String reviewPrompt(String type, String summary, String situation,
                                      String verdict, String details, List<String> symptoms) {
        StringBuilder sb = new StringBuilder(coldReaderPrompt());
        sb.append("\n---\nTHE CANDIDATE ENTRY\n---\n");
        sb.append("\ntype:      ").append(blankToDash(type));
        sb.append("\nsituation: ").append(blankToDash(situation));
        sb.append("\nclaim:     ").append(blankToDash(summary));
        sb.append("\noutcome:   ").append(blankToDash(verdict));
        if (symptoms != null && !symptoms.isEmpty()) {
            sb.append("\nsymptoms:");
            for (String s : symptoms) {
                sb.append("\n  - ").append(s);
            }
        }
        if (details != null && !details.isBlank()) {
            sb.append("\n\ndetails:\n").append(details);
        }
        sb.append("\n\n---\nAnswer the four questions, then end with exactly one line:\n")
          .append("VERDICT: keep\n  or\nVERDICT: drop\n")
          .append("A verdict of drop needs one sentence saying what it would take to keep it.\n");
        return sb.toString();
    }

    private static String blankToDash(String s) {
        return s == null || s.isBlank() ? "(none)" : s.strip();
    }

    public static String coldReaderPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You have NO context beyond the story below. Answer four questions.\n\n")
          .append("1. WHICH KIND is it, and is that right? A PRINCIPLE (violating it\n")
          .append("   produces a CLASS of different problems), a DURABLE FACT (one\n")
          .append("   condition, one remedy, no wider rule underneath), or a\n")
          .append("   PERISHABLE FACT (a fact about someone else's defect or release,\n")
          .append("   true now and false when they fix it)? A fact wearing a\n")
          .append("   principle's shape is the commonest defect a reader catches.\n")
          .append("2. WHEN does this apply? Restate the situation in your own words.\n")
          .append("   If you cannot, the situation is not self-contained — say so.\n")
          .append("   Name any word you would need the project explained to follow.\n")
          .append("3. What would you DO DIFFERENTLY for having read it? Not what you\n")
          .append("   would do — DIFFERENTLY. If a competent person does the same\n")
          .append("   thing without this entry, it has told them nothing.\n")
          .append("4. IS IT THE RIGHT WIDTH? Name one neighbouring case the claim\n")
          .append("   should also cover, and one system where it should NOT hold.\n\n")
          .append("You CANNOT check a fact, and you are not asked to. An entry can be\n")
          .append("fluent, correctly scoped and false; that is bounded elsewhere.\n\n")
          .append("The story owes these fields:\n");
        for (Field f : FIELDS) {
            sb.append("\n- ").append(f.name()).append(" — ").append(f.question()).append('\n');
            for (String c : f.conditions()) {
                sb.append("    * ").append(c).append('\n');
            }
        }
        return sb.toString();
    }

    /** The refusal kinds this class can emit — the set the documentation must cover. */
    public static List<String> refusalKinds() {
        return List.of("empty", "fallback_slip", "log_line", "compaction_artifact",
            "status_note", "section_heading", "not_a_claim");
    }

    /**
     * What a story owes, rendered into the {@code record} verb's own schema — so
     * every client's agent reads it BEFORE writing, not only in the refusal after.
     *
     * <p>Built from {@link #FIELDS} and {@link #refusalKinds()} rather than typed
     * out, for the reason {@code EntryForm.SITUATION_SHAPES} already states about
     * its own text: the schema teaches before the mistake and the refusal teaches
     * after it, and two hand-written copies of the same rules drift with nothing
     * comparing them. Here the drift is impossible — both come from this class.</p>
     *
     * <p>Kept to one paragraph deliberately. This rides in a tool schema that
     * every client loads on every session; the full statement of the rules is
     * {@code docs/story-template.md}, and the cold reader gets
     * {@link #coldReaderPrompt()}.</p>
     */
    public static String authorGuidance() {
        StringBuilder sb = new StringBuilder("A story owes: ");
        for (int i = 0; i < FIELDS.size(); i++) {
            Field f = FIELDS.get(i);
            sb.append(i == 0 ? "" : "; ").append(f.name()).append(" (")
              .append(f.question()).append(')');
        }
        sb.append(". REFUSED outright, whatever the type: ")
          .append(String.join(", ", refusalKinds()).replace('_', ' '))
          .append(" — a log records that something happened, an experience says what")
          .append(" to DO about it, and project progress was true when written and is")
          .append(" false now.");
        return sb.toString();
    }
}
