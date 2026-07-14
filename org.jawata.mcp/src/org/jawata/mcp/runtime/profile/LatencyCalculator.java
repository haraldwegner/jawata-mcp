package org.jawata.mcp.runtime.profile;

import java.util.ArrayList;
import java.util.List;

/**
 * Sprint 24 (D12) — percentiles from a closed-loop trace, honestly. A caller that
 * waits for one response before issuing the next (which {@code latency_seam}'s own
 * fixture does, deliberately — it is the common, easy-to-write shape) silently
 * SKIPS the requests a real open-loop caller would still have issued during a
 * slow patch. Left uncorrected, that under-reports exactly the tail a latency
 * measurement exists to catch — the slower the stall, the more it hides.
 *
 * <p>The correction (Gil Tene's "coordinated omission" fix, as implemented by
 * HdrHistogram's {@code recordValueWithExpectedInterval}): for an observed value
 * {@code v} against an expected inter-arrival {@code interval}, backfill synthetic
 * samples at {@code v - interval, v - 2*interval, ...} down to {@code interval} —
 * representing the delayed-but-real requests a closed-loop caller's silence hid.
 * Both the RAW and the CORRECTED percentiles are reported side by side, so the
 * correction's effect is visible, not asserted.</p>
 */
public final class LatencyCalculator {

    private LatencyCalculator() {
    }

    /** The three percentiles this floor reports, by nearest-rank on a SORTED copy. */
    public record Percentiles(long p50, long p99, long p999) {
        static Percentiles of(List<Long> sortedAscending) {
            return new Percentiles(
                nearestRank(sortedAscending, 500),
                nearestRank(sortedAscending, 990),
                nearestRank(sortedAscending, 999));
        }

        /**
         * Nearest-rank index, in PURE INTEGER arithmetic — no {@code double} anywhere.
         *
         * <p>{@code 99.9} is not exactly representable in binary floating point; a
         * {@code double}-based {@code Math.ceil(99.9 / 100.0 * n)} lands a hair OVER an
         * exact integer boundary for some {@code n} (found live: n=1000 computed
         * 999.0000000000001, one full index past the intended 999.0 — landing squarely
         * on the single rare-stall sample this exact scenario was built to keep OUT of
         * p999). {@code permille} (parts per thousand: 500/990/999) with integer
         * ceiling-division has no representation error to land on either side of.
         */
        private static long nearestRank(List<Long> sortedAscending, int permille) {
            int n = sortedAscending.size();
            int index = (int) ((permille * (long) n + 999) / 1000) - 1;
            index = Math.max(0, Math.min(n - 1, index));
            return sortedAscending.get(index);
        }
    }

    /** Apply the correction: the observed value, plus its backfilled phantoms. */
    public static List<Long> applyCoordinatedOmissionCorrection(List<Long> raw, long expectedInterval) {
        List<Long> corrected = new ArrayList<>(raw.size());
        for (long value : raw) {
            corrected.add(value);
            if (expectedInterval > 0) {
                for (long missing = value - expectedInterval;
                        missing >= expectedInterval;
                        missing -= expectedInterval) {
                    corrected.add(missing);
                }
            }
        }
        return corrected;
    }

    /** The median gap between consecutive (already time-ordered) call-start timestamps. */
    public static long inferExpectedInterval(List<Long> orderedStartTimestamps) {
        if (orderedStartTimestamps.size() < 2) {
            return 0;
        }
        List<Long> gaps = new ArrayList<>(orderedStartTimestamps.size() - 1);
        for (int i = 1; i < orderedStartTimestamps.size(); i++) {
            gaps.add(orderedStartTimestamps.get(i) - orderedStartTimestamps.get(i - 1));
        }
        gaps.sort(Long::compareTo);
        return gaps.get(gaps.size() / 2);
    }

    public static Percentiles percentilesOf(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        return Percentiles.of(sorted);
    }
}
