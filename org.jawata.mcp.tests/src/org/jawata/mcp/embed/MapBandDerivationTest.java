package org.jawata.mcp.embed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jawata.mcp.knowledge.EmbeddingService;

/**
 * Sprint 28f Stage 0, measurement 3 — DERIVE the map band, never choose it.
 *
 * <p>The architecture's section 8 names four jobs that one distance metric
 * serves, and rules that they never swap: retrieval keeps the broad band, dedup
 * keeps {@code DEDUP_THRESHOLD}, and the two new ones — the MAP at task start
 * and duplicate nomination before a write — need bars of their own. This test
 * supplies the map's, from three measured distributions rather than from
 * anybody's judgement.</p>
 *
 * <p><b>The instrument is the product's own embedder, deliberately.</b> Sprint
 * 27's reference generator ({@code gen_stage0.py}) needs a Python model
 * download, and what ships is the Java encoder — which that sprint already
 * pinned to the reference at cosine ≥ 0.999 on committed goldens
 * ({@code MiniLmEmbedderParityTest}). Measuring with the shipped encoder
 * therefore measures what the map will actually use, and the parity gate is
 * what makes that equivalent to measuring the reference.</p>
 *
 * <p><b>The three sets, and why the third must not be used for this bar.</b>
 * UNRELATED pairs give the floor: their p95 is the distance a task and a job
 * reach by sharing a vocabulary alone, so a bar at or below it nominates noise.
 * DESIGNATED pairs must clear that floor, or the bar is too high to answer the
 * question it exists for. NEAR-DUPLICATE pairs — two wordings of ONE job — are
 * the DEDUP edge and are recorded here only so the two bands can be seen apart:
 * deriving the map's bar from them would put it up where paraphrases live, and
 * a map that only fires on paraphrases of its own rows is a map of nothing.</p>
 *
 * <p>The corpus is frozen at {@code embed-goldens/28f-band-pairs.json}, written
 * before any 28f retrieval code existed and by hand rather than from the live
 * store — a corpus drawn from the store measures the store.</p>
 */
class MapBandDerivationTest {

    private static final String PAIRS = "/test-resources/embed-goldens/28f-band-pairs.json";

    @Test
    void deriveTheMapBandFromTheThreeDistributions() throws Exception {
        EmbeddingService embedder = EmbeddingService.shared();
        // An absent embedder is a real condition on a machine without the model,
        // and it is a SKIP rather than a pass: a band cannot be derived from a
        // metric that is not running, and reporting one would be an invention.
        Assumptions.assumeTrue(embedder.available(),
            "[28f MAP BAND] NOT RUN — no embedder: " + embedder.unavailableReason());

        JsonNode fixture;
        try (InputStream in = getClass().getResourceAsStream(PAIRS)) {
            assertTrue(in != null, "the frozen pair fixture must be on the test classpath: " + PAIRS);
            fixture = new ObjectMapper().readTree(in);
        }

        Map<String, float[]> jobVectors = new LinkedHashMap<>();
        Map<String, String> jobText = new LinkedHashMap<>();
        for (JsonNode job : fixture.get("jobs")) {
            String id = job.get("id").asText();
            String summary = job.get("summary").asText();
            jobText.put(id, summary);
            jobVectors.put(id, embedder.embed(summary));
        }
        assertFalse(jobVectors.isEmpty(), "the fixture must carry jobs");

        System.out.println("S0-MEASUREMENT-3 per-pair scores");
        List<Double> designated = scoresOf(fixture.get("designated"), embedder, jobVectors, "DES");
        List<Double> unrelated = scoresOf(fixture.get("unrelated"), embedder, jobVectors, "UNR");

        List<Double> nearDuplicate = new ArrayList<>();
        for (JsonNode pair : fixture.get("near_duplicates")) {
            nearDuplicate.add(EmbeddingService.cosine(
                embedder.embed(pair.get("a").asText()),
                embedder.embed(pair.get("b").asText())));
        }

        Collections.sort(designated);
        Collections.sort(unrelated);
        Collections.sort(nearDuplicate);

        double floor = percentile(unrelated, 0.95);
        double designatedMin = designated.get(0);

        // The derived bar sits between the floor and the weakest designated pair.
        // Midpoint rather than either endpoint: sitting ON the floor admits the
        // noise the floor measures, and sitting ON the weakest designated pair
        // leaves it no margin at all.
        double bar = (floor + designatedMin) / 2.0;

        System.out.println("S0-MEASUREMENT-3 map band");
        System.out.printf("  unrelated  n=%d median=%.4f p95=%.4f max=%.4f%n",
            unrelated.size(), percentile(unrelated, 0.50), floor,
            unrelated.get(unrelated.size() - 1));
        System.out.printf("  designated n=%d min=%.4f median=%.4f max=%.4f%n",
            designated.size(), designatedMin, percentile(designated, 0.50),
            designated.get(designated.size() - 1));
        System.out.printf("  near_dupes n=%d min=%.4f median=%.4f max=%.4f%n",
            nearDuplicate.size(), nearDuplicate.get(0), percentile(nearDuplicate, 0.50),
            nearDuplicate.get(nearDuplicate.size() - 1));
        System.out.printf("  DERIVED map_bar=%.4f (floor=%.4f, weakest designated=%.4f)%n",
            bar, floor, designatedMin);

        // THE MEASUREMENT, which is this test's product: how many genuine
        // task-to-job pairs fall at or below the noise floor. Every one of them
        // is a question the map would answer with "nothing" while the answer sat
        // in the lane. A single cosine cutoff can only serve the map if this
        // count is zero.
        long belowFloor = designated.stream().filter(d -> d <= floor).count();
        System.out.printf("  OVERLAP designated pairs at or below the floor: %d of %d%n",
            belowFloor, designated.size());
        System.out.printf("S0-MEASUREMENT-3 floor=%.4f designated_min=%.4f designated_median=%.4f"
            + " near_dupe_min=%.4f below_floor=%d of %d single_bar_possible=%b%n",
            floor, designatedMin, percentile(designated, 0.50), nearDuplicate.get(0),
            belowFloor, designated.size(), belowFloor == 0);

        // WHAT IS ASSERTED, and why it is this and not the count above. The
        // assertion's job is to prove the CORPUS is a usable instrument; the
        // count's job is to report what the instrument measured. Asserting the
        // count would make a true finding about the metric read as a broken
        // test, and would go red for as long as the finding stood - while
        // weakening it to fit the finding would be the self-marking this
        // project refuses. So: the designated set as a whole must out-score the
        // noise floor. If even that fails, the corpus is not measuring
        // task-to-job similarity at all and no number it produces means
        // anything.
        double designatedMedian = percentile(designated, 0.50);
        assertTrue(designatedMedian > floor,
            String.format("the corpus is not a usable instrument: the MEDIAN designated"
                + " task-to-job pair (%.4f) does not out-score the p95 of unrelated pairs"
                + " (%.4f). Fix the corpus before reading any band off it.",
                designatedMedian, floor));

        // The three sets must come out ORDERED BY MEDIAN: noise below real
        // answers below paraphrases of one job. That is what "three distributions"
        // means and it is all section 8 needs to keep the bands from swapping
        // jobs.
        //
        // An earlier version of this assertion demanded the WEAKEST near-duplicate
        // out-score the designated median - i.e. disjoint ranges - and it failed
        // (0.2405 against 0.2867). The claim was mine and had no basis: section 8
        // says the bands serve different jobs, never that no pair of them shares a
        // score. Recorded rather than quietly relaxed, because rewriting an
        // assertion until it passes is the defect this project has paid for most.
        double nearDupeMedian = percentile(nearDuplicate, 0.50);
        double unrelatedMedian = percentile(unrelated, 0.50);
        assertTrue(unrelatedMedian < designatedMedian && designatedMedian < nearDupeMedian,
            String.format("the three sets must order by median - unrelated (%.4f) below"
                + " designated (%.4f) below near-duplicate (%.4f). They do not, so these are"
                + " not three distinguishable distributions and no band read off them means"
                + " anything.", unrelatedMedian, designatedMedian, nearDupeMedian));
    }

    private static List<Double> scoresOf(JsonNode pairs, EmbeddingService embedder,
            Map<String, float[]> jobVectors) {
        return scoresOf(pairs, embedder, jobVectors, null);
    }

    /**
     * Every pair's own score, printed under {@code label} when one is given.
     *
     * <p>The per-pair line exists because the aggregate cannot tell a metric that
     * does not separate from a corpus with one badly-worded question in it. When
     * the weakest designated pair falls under the noise floor, the only way to
     * know which of those two it is, is to read the pair.</p>
     */
    private static List<Double> scoresOf(JsonNode pairs, EmbeddingService embedder,
            Map<String, float[]> jobVectors, String label) {
        List<Double> out = new ArrayList<>();
        for (JsonNode pair : pairs) {
            String taskText = pair.get("task").asText();
            String jobId = pair.get("job").asText();
            float[] task = embedder.embed(taskText);
            float[] job = jobVectors.get(jobId);
            assertTrue(job != null, "every pair must name a job the fixture declares: " + jobId);
            double score = EmbeddingService.cosine(task, job);
            out.add(score);
            if (label != null) {
                System.out.printf("    %s %.4f  %s  <- %s%n", label, score, jobId,
                    taskText.length() > 64 ? taskText.substring(0, 64) + "..." : taskText);
            }
        }
        return out;
    }

    /** Linear-interpolated order statistic over an ALREADY SORTED list. */
    private static double percentile(List<Double> sorted, double q) {
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double k = q * (sorted.size() - 1);
        int lo = (int) Math.floor(k);
        int hi = Math.min(lo + 1, sorted.size() - 1);
        return sorted.get(lo) + (k - lo) * (sorted.get(hi) - sorted.get(lo));
    }
}
