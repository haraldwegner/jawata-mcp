package org.jawata.mcp.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sprint 28c D13 — how close is this entry to the question, and nothing else.
 *
 * <p><b>Relevance is the only ranking key</b>, and here that is structural
 * rather than a rule someone must remember: this class is handed four
 * proximity numbers and returns one. It cannot read an entry's outcome, its
 * origin, its age, its length or how often it has been useful, because none of
 * those is a parameter. A later change that wanted to rank on any of them would
 * have to widen the signature, which is a visible edit rather than a quiet
 * one.</p>
 *
 * <p><b>The defect this replaces.</b> The anchorless lane ranked by
 * {@code cosine + bm25}, added raw. A cosine over these embeddings lands in
 * roughly −0.1 … 1 (measured responses do carry small negatives); BM25 is
 * unbounded and routinely reaches double digits on a corpus this size. So the
 * sum was BM25 with a rounding error attached, and the meaning half — the half
 * that can see that "bet against a stock" is a short position — could not move
 * the order at all. It went unnoticed while the store held twelve rows and every
 * row was a candidate; it became visible the moment 187 catalogue entries
 * arrived and took every slot from the entries that actually answered.</p>
 *
 * <p>A negative lane score is kept rather than clamped, and that is a decision:
 * a field whose meaning runs opposite to the question is evidence against the
 * row, and clamping it to zero would make "unrelated" and "contradictory" score
 * the same.</p>
 *
 * <h2>The four signals</h2>
 *
 * <p>Three of them are cosines against the SAME embedder, computed from three
 * different fields of the entry: the situation it declares, its one-line claim,
 * and its body. Same unit, same space, so weighting them is arithmetic and not
 * a unit conversion. The fourth is BM25 over the entry's whole text, which is a
 * different unit entirely and is therefore normalised before it is allowed near
 * the sum — against what a PERFECT word match of the question scores, see
 * {@link #normalise}.</p>
 *
 * <h2>The weights are a declared prior, not a fit</h2>
 *
 * <p>{@link #W_SITUATION} 0.6 / {@link #W_SUMMARY} 0.3 / {@link #W_DETAILS} 0.1
 * is Harald's design ruling, and the reasoning is stated so a later reader can
 * disagree with it on its merits: applicability is DECLARED in the situation, so
 * a question that describes a situation should meet that field hardest; the
 * summary carries the claim; the body supports it and dilutes fastest, because
 * averaging a long note into one 384-number vector dissolves exactly the rare
 * detail that would have discriminated.</p>
 *
 * <p>{@link #W_WORDS} 0.4 is derived from the one measurement that exists rather
 * than invented: on the frozen calibration corpus the meaning path answered 9 of
 * 12 cues alone and the word path 4 of 12, so words earn roughly 4/(9+4) ≈ 31%
 * of the decision; 0.4 against a meaning side summing to 1.0 gives 28.6%.</p>
 *
 * <p><b>None of these four was tuned until a probe went green, and that is a
 * rule and not a description.</b> The fixture this sprint measures against was
 * written by the same hand as the code; turning a knob until it passes measures
 * the hand. If the probe fails, the cause is diagnosed and reported — a weight
 * moved to make a self-authored bar go green is a fit wearing a prior's
 * clothes.</p>
 *
 * <p>PURE: numbers in, numbers out. No store, no connection, no embedder, no
 * clock.</p>
 */
public final class RelevanceMerge {

    /** Weight of the situation lane — the field applicability is declared in. */
    public static final double W_SITUATION = 0.6;

    /** Weight of the summary lane — the one-line claim. */
    public static final double W_SUMMARY = 0.3;

    /** Weight of the details lane — supporting body, dilutes fastest. */
    public static final double W_DETAILS = 0.1;

    /** Weight of the normalised word lane; see the class note for its derivation. */
    public static final double W_WORDS = 0.4;

    /**
     * What one entry scored, per dimension, and in total.
     *
     * <p>The parts ride into the response beside the total because a ranking
     * nobody can interrogate is a ranking nobody can correct. When an entry
     * outranks a better one, the four numbers say WHICH lane did it — and this
     * sprint spent a day on a ranking regression whose cause was one lane
     * silently reading a different field set from its twin.</p>
     */
    public record Score(double situation, double summary, double details, double words,
                        double total) {
    }

    private RelevanceMerge() {
    }

    /**
     * Rescale BM25 to 0..1 against what a PERFECT word match of this question scores.
     *
     * <p>Afterwards a value says <b>how much of the question's words a row carries</b>,
     * weighted by rarity: a row holding every discriminating word of the question reads
     * about 1.0, a row sharing one rare word of twelve reads a small fraction, and a row
     * sharing only common words reads nothing.</p>
     *
     * <p><b>The rule this replaces</b> divided by the best score in the result set. That
     * said which row matched best relative to the others — and so the best match always
     * read 1.0 and took the full {@link #W_WORDS}, whatever it had matched. Against the
     * meaning lanes, which are absolute and sit around 0.2 to 0.5 on a real question, a
     * row sharing ONE word of a paraphrased question outranked the row that answered it.
     * The 4.3.0 dogfood measured it: "at what point may I treat pulling back an
     * instruction I gave the exchange as finished" put a lesson about standing
     * instructions first, on the one word "instruction", and the cancel-confirmation fact
     * was not in the top eight. That is the unit mismatch this class was written to
     * remove, come back from the other side.</p>
     *
     * <p>Still not a fitted constant: the ceiling is computed per question from the
     * corpus's own statistics ({@link LexicalIndex.Scores#ceiling}), so it moves with
     * the corpus exactly as the scores do. Clamped at 1.0, because a short row or a
     * repeated word can exceed the average-length perfect match, and "more than the whole
     * question" is not a thing a word lane can mean.</p>
     *
     * @param stream  raw scores per id; {@code null} or empty yields empty
     * @param ceiling the score of a perfect word match for this question; zero or less
     *                means the question has no word any row could match, and yields empty
     */
    static Map<String, Double> normalise(Map<String, Double> stream, double ceiling) {
        if (stream == null || stream.isEmpty() || ceiling <= 0.0) {
            return Map.of();
        }
        Map<String, Double> out = new LinkedHashMap<>(stream.size());
        for (Map.Entry<String, Double> e : stream.entrySet()) {
            out.put(e.getKey(), Math.min(1.0, e.getValue() / ceiling));
        }
        return out;
    }

    /**
     * The weighted sum for one entry.
     *
     * <p><b>A missing lane contributes zero and the weights are NOT
     * renormalised.</b> That is the design's own ruling and it has a
     * consequence worth stating rather than discovering: an entry that declares
     * no situation cannot reach more than 0.4 of the meaning weight however well
     * its summary matches. Renormalising would hide that — it would let a row
     * with one populated field score as though it had matched on everything, so
     * a bare heading with a lucky summary would rank beside a fully written
     * story. Under-declaring is meant to cost something.</p>
     *
     * @param situation cosine on the situation lane, or 0 when the entry
     *                  declares none
     * @param summary   cosine on the summary lane
     * @param details   cosine on the body lane
     * @param words     the ALREADY-NORMALISED word score (see {@link #normalise})
     */
    static Score scoreOf(double situation, double summary, double details, double words) {
        double total = W_SITUATION * situation
            + W_SUMMARY * summary
            + W_DETAILS * details
            + W_WORDS * words;
        return new Score(situation, summary, details, words, total);
    }

    /**
     * Score every id that any lane scored.
     *
     * <p>The union, not the intersection: an entry the word lane alone found is
     * still ranked, and so is one only the situation lane reached. Requiring
     * agreement would silently drop exactly the rows one lane exists to catch
     * that the other cannot.</p>
     *
     * @param situationLane per-id cosine against the entry's situation; empty when
     *                      that lane is unavailable, which is the degrade path and
     *                      not an error
     * @param summaryLane   per-id cosine against the entry's one-line claim
     * @param detailsLane   per-id cosine against the entry's body
     * @param words         RAW BM25 per id with the question's ceiling; normalised
     *                      HERE, so callers hand over exactly what
     *                      {@link LexicalIndex#scored} produced and nothing in between
     *                      has to remember to rescale it
     */
    public static Map<String, Score> scoreAll(Map<String, Double> situationLane,
                                              Map<String, Double> summaryLane,
                                              Map<String, Double> detailsLane,
                                              LexicalIndex.Scores words) {
        Map<String, Double> sit = situationLane == null ? Map.of() : situationLane;
        Map<String, Double> sum = summaryLane == null ? Map.of() : summaryLane;
        Map<String, Double> det = detailsLane == null ? Map.of() : detailsLane;
        Map<String, Double> normalisedWords = words == null
            ? Map.of() : normalise(words.byId(), words.ceiling());

        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        ids.addAll(sit.keySet());
        ids.addAll(sum.keySet());
        ids.addAll(det.keySet());
        ids.addAll(normalisedWords.keySet());

        Map<String, Score> out = new LinkedHashMap<>(ids.size());
        for (String id : ids) {
            out.put(id, scoreOf(
                sit.getOrDefault(id, 0.0),
                sum.getOrDefault(id, 0.0),
                det.getOrDefault(id, 0.0),
                normalisedWords.getOrDefault(id, 0.0)));
        }
        return out;
    }
}
