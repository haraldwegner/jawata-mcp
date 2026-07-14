package org.jawata.mcp.tools.verification;

import org.jawata.mcp.runtime.profile.LatencyCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 (D12) — the coordinated-omission correction, proven with hand-verifiable
 * numbers (no JVM, no timing, no flakiness): a rare, severe stall that raw p999
 * COMPLETELY MISSES by pure count, and that the correction reveals.
 */
class LatencyCalculatorTest {

    /**
     * 999 fast calls (5ms) + ONE 500ms stall = 1000 raw samples. p999's nearest-rank
     * index (ceil(0.999*1000)-1 = 998) lands on the LAST of the 999 fast values — the
     * single stall sits at index 999, one past where p999 looks. Raw p999 is blind to
     * it BY CONSTRUCTION: exactly the coordinated-omission blind spot, not a contrived
     * edge case.
     */
    @Test
    @DisplayName("raw p999 misses a rare 1-in-1000 stall entirely; corrected p999 reveals it")
    void correctionRevealsAStallRawP999Misses() {
        List<Long> raw = new ArrayList<>(Collections.nCopies(999, 5L));
        raw.add(500L);

        LatencyCalculator.Percentiles rawPct = LatencyCalculator.percentilesOf(raw);
        assertEquals(5L, rawPct.p50(), "median is deep in the fast majority");
        assertEquals(5L, rawPct.p999(), "RAW p999 misses the stall — 999/1000 samples are fast, "
            + "so the nearest-rank index lands one short of the single slow sample");

        long expectedInterval = 5L;
        List<Long> corrected = LatencyCalculator.applyCoordinatedOmissionCorrection(raw, expectedInterval);
        // The stall (500) backfills 495, 490, ..., 10, 5 -- (500-5)/5 = 99 synthetic samples.
        assertEquals(1000 + 99, corrected.size(), "the stall's hidden calls are backfilled");

        LatencyCalculator.Percentiles correctedPct = LatencyCalculator.percentilesOf(corrected);
        assertEquals(5L, correctedPct.p50(), "the median is unaffected — the fast majority still dominates");
        assertEquals(495L, correctedPct.p999(),
            "CORRECTED p999 reveals the stall's shadow: the backfilled tail, invisible to raw p999");
        assertTrue(correctedPct.p999() > rawPct.p999(),
            "the whole point: corrected must show what raw hid");
    }

    @Test
    @DisplayName("no stall, no correction: an evenly-paced trace is unchanged by the correction")
    void evenlyPacedTraceIsUnaffected() {
        List<Long> raw = new ArrayList<>(Collections.nCopies(500, 5L));
        List<Long> corrected = LatencyCalculator.applyCoordinatedOmissionCorrection(raw, 5L);
        assertEquals(raw.size(), corrected.size(), "nothing to backfill when nothing exceeds the interval");
        assertEquals(LatencyCalculator.percentilesOf(raw).p999(),
            LatencyCalculator.percentilesOf(corrected).p999());
    }

    @Test
    @DisplayName("expected interval is inferred as the MEDIAN gap between call starts")
    void inferExpectedIntervalUsesMedianGap() {
        // Starts 5ms apart, one outlier gap (a scheduling hiccup at collection time,
        // not a real seam stall) must not skew the inference the way a MEAN would.
        List<Long> starts = List.of(0L, 5L, 10L, 15L, 20L, 25L, 30L, 1000L);
        long inferred = LatencyCalculator.inferExpectedInterval(starts);
        assertEquals(5L, inferred, "the median gap, not distorted by one outlier gap");
    }

    @Test
    @DisplayName("fewer than two timestamps: no interval can be inferred")
    void inferExpectedIntervalNeedsAtLeastTwoPoints() {
        assertEquals(0L, LatencyCalculator.inferExpectedInterval(List.of()));
        assertEquals(0L, LatencyCalculator.inferExpectedInterval(List.of(42L)));
    }
}
