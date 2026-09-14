package org.jawata.mcp.knowledge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sprint 27a D9 — matching on WORDS, scored by how rare they are.
 *
 * <p><b>Why this exists.</b> The store's older word-matching required every
 * token of a cue to appear as a substring of a row's summary or one of its
 * symptoms, joined by AND, un-stopworded and unscored, with the row's body
 * never searched. On the cue "our test coverage looked like it fell from 77% to
 * 34% overnight" the token {@code looked} alone takes 2,080 rows to zero. That
 * path answers 2 of the 12 frozen calibration cues.</p>
 *
 * <p><b>What rarity buys.</b> The entry that answers that cue shares four rare
 * words with it — {@code fell} occurs in 6 of 2,080 rows, {@code 77} in 16,
 * {@code 34} in 33, {@code overnight} in 25. Weighted by rarity it ranks
 * <b>1st of 2,080</b>; the meaning path ranks it 28th, because averaging a
 * 300-word note into one 384-number vector dissolves exactly that rarity.</p>
 *
 * <p><b>And why this does NOT replace meaning-matching.</b> Measured alone
 * against the frozen contract it scores 3 of 12, against the meaning path's 9.
 * The two fail on DIFFERENT cues, which is the whole reason both run and their
 * rankings are merged — see {@link AnalogyPolicy#nominate(Map, Map)}. A word
 * matcher cannot see that "bet against a stock" is a short position; a vector
 * cannot see that two texts share the same four rare tokens.</p>
 *
 * <p>No stop-word list, deliberately: inverse document frequency already drives
 * words like "the" and "our" to near zero, and a hand-maintained list is one
 * more thing to be wrong about this corpus.</p>
 *
 * <p>PURE: rows in, scores out. No store, no connection, no embedder.</p>
 */
public final class LexicalIndex {

    /**
     * BM25 term-frequency saturation. The published default from the method's
     * own literature, NOT fitted to this corpus — this sprint has already paid
     * once for a constant that held only on the data it was chosen against.
     */
    public static final double K1 = 1.5;

    /** BM25 length normalisation; the published default, for the same reason. */
    public static final double B = 0.75;

    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    /**
     * Does sharing this word tell you anything about WHICH row to pick?
     *
     * <p>A word in more than half the rows does not: it is a property of the
     * corpus, not of the match. Inverse document frequency already discounts
     * such words toward zero on a large corpus — but "toward zero" is not zero,
     * and on a SMALL one it fails outright, because rarity cannot be estimated
     * from a handful of documents. Against a store holding a single row, the
     * word "the" has df = 1 of n = 1 and scores 0.2877 as though it were rare.
     * That is not hypothetical: it is how the question "the espresso machine
     * leaks water onto the kitchen floor" came to nominate a Javadoc seat run,
     * matching on "the" and nothing else.</p>
     *
     * <p>A proportion, deliberately, so the rule means the same thing whatever
     * the corpus size — the property the fitted margin retired at C2 lacked.</p>
     *
     * <p><b>Half is a judgement, not a measurement, and is stated as one.</b> No
     * document-frequency distribution was fitted to pick it; the argument is
     * that a word carried by most rows cannot discriminate between them, and a
     * majority is the natural place to put that line. Its COST is real and
     * pinned by test: on a corpus of one row nothing matches by words at all,
     * and on a handful of rows only terms unique to one of them do. That is
     * acceptable because a near-empty store has no rarity to estimate and the
     * meaning path carries recall there — but it is a limit, not a free
     * property.</p>
     */
    static boolean discriminates(int docFreq, int corpusSize) {
        return docFreq * 2 <= corpusSize;
    }

    private LexicalIndex() {
    }

    /**
     * Lower-case runs of letters and digits.
     *
     * <p>Splitting on punctuation is what lets the cue's {@code 77%} meet the
     * note's {@code 77.60→34.34%}: as raw strings neither contains the other,
     * and the older substring rule therefore missed a row that names the very
     * numbers the question asks about.</p>
     */
    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        var m = WORD.matcher(text.toLowerCase(java.util.Locale.ROOT));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /**
     * The words a row is matched on: its situation, its summary, its symptoms,
     * and its body detail — everything a human wrote about it.
     *
     * <p>The body is included because the older rule searched only the summary,
     * so a note whose explanation lived in its detail was unmatchable by any
     * word that explanation used.</p>
     *
     * <p>The SITUATION is included because it is the field the 28c form ranks
     * applicability by — and this lane could not see it. Found live: a question
     * that paraphrased an entry's situation nearly verbatim missed the top 8
     * while eight unrelated entries filled it, because every shared word lived
     * in the one column this method skipped. The meaning lane already embeds
     * the situation ({@code EmbeddingService.documentOf}); a word lane that
     * reads a different field set silently disagrees with it on exactly the
     * rows where the situation carries the match.</p>
     */
    public static String textOf(StoredEntry e) {
        if (e == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (e.facets() != null && e.facets().situation() != null) {
            sb.append(e.facets().situation());
        }
        if (e.summary() != null) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(e.summary());
        }
        if (e.symptoms() != null) {
            for (String s : e.symptoms()) {
                sb.append(' ').append(s);
            }
        }
        Object details = e.body() == null ? null : e.body().get("details");
        if (details != null) {
            sb.append(' ').append(details);
        }
        return sb.toString();
    }

    /**
     * What the word lane measured for one question.
     *
     * <p>{@code byId} is the raw BM25 score per row. {@code ceiling} is what a row would
     * score if it contained EVERY discriminating word of the question once, at the
     * corpus's average length — the score of a perfect word match for THIS question.
     * It is the unit {@link RelevanceMerge} divides by, and it exists because the other
     * unit, the best score in the result set, made the best match read as perfect
     * whatever it matched: a note sharing one word of a twelve-word question scored 1.0
     * whenever nothing shared more (the 4.3.0 dogfood, defect 2).</p>
     *
     * <p>A question word that no row contains does not raise the ceiling: no row could
     * match it, so counting it would shrink every row's score alike and only weaken the
     * lane. A word in most rows does not either, for the reason {@link #discriminates}
     * gives.</p>
     */
    public record Scores(Map<String, Double> byId, double ceiling) {
        /** No question, or nothing to search: a real answer, not a failure. */
        public static final Scores NONE = new Scores(Map.of(), 0.0);
    }

    /**
     * The corpus side of BM25, computed ONCE: each row's length and each word's postings
     * (the rows holding it, with the term frequency in each).
     *
     * <p><b>Why it exists (2026-09-14).</b> The per-question form rebuilt all of this on
     * every recall. At 10,257 rows a profile put 18% of each recall's CPU into that
     * rebuild, beside 61% spent reading the rows it was rebuilt from, and recall missed
     * the hook's 1.2 s budget. The statistics are a property of the CORPUS, not of the
     * question, so they are built when the corpus changes ({@link StoreCorpus}) and a
     * question only walks the postings of its own words.</p>
     *
     * <p>Scores are the same as the per-question form: each row's sum takes the same
     * terms in the same order, and rows are reported in corpus order.</p>
     */
    public static final class Corpus {
        private final String[] ids;
        private final int[] lengths;
        private final double avgLength;
        private final Map<String, int[][]> postings;   // word -> {row index, term frequency}

        private Corpus(String[] ids, int[] lengths, double avgLength,
                Map<String, int[][]> postings) {
            this.ids = ids;
            this.lengths = lengths;
            this.avgLength = avgLength;
            this.postings = postings;
        }

        /** How many rows the statistics were computed over. */
        public int size() {
            return ids.length;
        }
    }

    /** Build the {@link Corpus} for these rows; the statistics are over exactly this set. */
    public static Corpus index(List<StoredEntry> corpus) {
        int n = corpus == null ? 0 : corpus.size();
        String[] ids = new String[n];
        int[] lengths = new int[n];
        Map<String, List<int[]>> building = new HashMap<>();
        long total = 0;
        for (int i = 0; i < n; i++) {
            StoredEntry e = corpus.get(i);
            List<String> words = tokenize(textOf(e));
            Map<String, Integer> tf = new LinkedHashMap<>();
            for (String w : words) {
                tf.merge(w, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> t : tf.entrySet()) {
                building.computeIfAbsent(t.getKey(), k -> new ArrayList<>())
                    .add(new int[] {i, t.getValue()});
            }
            ids[i] = e.id();
            lengths[i] = words.size();
            total += words.size();
        }
        Map<String, int[][]> postings = new HashMap<>(building.size() * 2);
        for (Map.Entry<String, List<int[]>> p : building.entrySet()) {
            postings.put(p.getKey(), p.getValue().toArray(new int[0][]));
        }
        double avgLength = total == 0 ? 1.0 : (double) total / Math.max(1, n);
        return new Corpus(ids, lengths, avgLength, postings);
    }

    /** BM25 for one cue over a prebuilt {@link Corpus}; see {@link #scored(String, List)}. */
    public static Scores scoredIn(String cue, Corpus corpus) {
        List<String> query = tokenize(cue);
        if (query.isEmpty() || corpus == null || corpus.size() == 0) {
            return Scores.NONE;
        }
        int n = corpus.size();
        double[] sums = new double[n];
        for (String w : query) {
            int[][] rows = corpus.postings.get(w);
            if (rows == null) {
                continue;
            }
            int df = rows.length;
            if (!discriminates(df, n)) {
                continue;
            }
            // Lucene's non-negative IDF form: a word in EVERY row scores
            // near zero rather than negative, so a cue of only common words
            // ranks nothing rather than ranking it backwards.
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            for (int[] row : rows) {
                int i = row[0];
                int f = row[1];
                double norm = f + K1 * (1 - B + B * corpus.lengths[i] / corpus.avgLength);
                sums[i] += idf * (f * (K1 + 1)) / norm;
            }
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            if (sums[i] > 0) {
                scores.put(corpus.ids[i], sums[i]);
            }
        }
        // A row of average length holding a word once scores exactly that word's idf:
        // (1 * (K1 + 1)) / (1 + K1 * (1 - B + B * 1)) = 1. So the perfect match is the sum
        // of the idfs — iterated over the SAME token list the scores were, so a repeated
        // question word counts in the ceiling exactly as often as it can count in a score.
        double ceiling = 0.0;
        for (String w : query) {
            int[][] rows = corpus.postings.get(w);
            int df = rows == null ? 0 : rows.length;
            if (df > 0 && discriminates(df, n)) {
                ceiling += Math.log(1 + (n - df + 0.5) / (df + 0.5));
            }
        }
        return new Scores(scores, ceiling);
    }
}
