package org.jawata.samples.replacepatternwithidiom;

import java.util.function.Supplier;

/**
 * THE VIOLATION, deliberately — the Strategy pattern written as anonymous
 * classes, where the language now has an idiom for it. Paired with
 * {@link RetryPolicyAfter}.
 *
 * <p>The cure {@code replace_pattern_with_idiom} runs AWAY from a pattern: the
 * pattern is not wrong, it has been absorbed. A single-method interface plus
 * anonymous implementations IS a lambda, spelled the long way, and the ceremony
 * now hides the one line that differs between the policies.</p>
 *
 * <p><b>Do not "fix" this file.</b> Its defects are the specimen.</p>
 */
public final class RetryPolicyBefore {

    /** A single abstract method — a functional interface in all but the annotation. */
    public interface Backoff {
        long delayMillis(int attempt);
    }

    /** Three policies, each wrapped in five lines of ceremony around one expression. */
    public static final Backoff FIXED = new Backoff() {
        @Override
        public long delayMillis(int attempt) {
            return 100L;
        }
    };

    public static final Backoff LINEAR = new Backoff() {
        @Override
        public long delayMillis(int attempt) {
            return 100L * attempt;
        }
    };

    public static final Backoff EXPONENTIAL = new Backoff() {
        @Override
        public long delayMillis(int attempt) {
            return 100L * (1L << Math.min(attempt, 10));
        }
    };

    private RetryPolicyBefore() {
    }

    /** Sums the delays a policy would impose — deterministic, so it is testable. */
    public static long totalDelay(Backoff backoff, int attempts) {
        long total = 0;
        for (int i = 1; i <= attempts; i++) {
            total += backoff.delayMillis(i);
        }
        return total;
    }

    /** The same ceremony on the calling side: a supplier written as a class. */
    public static long totalDelayOfDefault(int attempts) {
        Supplier<Backoff> chosen = new Supplier<Backoff>() {
            @Override
            public Backoff get() {
                return EXPONENTIAL;
            }
        };
        return totalDelay(chosen.get(), attempts);
    }
}
