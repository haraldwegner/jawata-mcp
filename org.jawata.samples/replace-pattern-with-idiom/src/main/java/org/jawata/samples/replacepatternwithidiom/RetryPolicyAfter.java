package org.jawata.samples.replacepatternwithidiom;

/**
 * THE CURE — {@link RetryPolicyBefore} after Replace Pattern with Idiom.
 *
 * <p>The Strategy is still here; the language expresses it now. Each policy is
 * the one expression that distinguishes it, and the interface says out loud
 * that it is functional, so the compiler defends that property instead of a
 * convention defending it.</p>
 *
 * <p><b>What it costs:</b> a lambda has no name and no place to hang a doc
 * comment, so a policy with a rationale worth writing down is better off a
 * named method — which is why {@code EXPONENTIAL} below is a method reference
 * to a real method rather than an inline lambda. The cure is not "make
 * everything a lambda"; it is "stop paying for a class where nothing needs a
 * class". A strategy that carries state, or implements more than one method,
 * keeps its class and this transformation refuses it.</p>
 */
public final class RetryPolicyAfter {

    /**
     * The annotation is the point: it makes "exactly one abstract method" a
     * compiler-checked property rather than something the next edit can break
     * silently.
     */
    @FunctionalInterface
    public interface Backoff {
        long delayMillis(int attempt);
    }

    public static final Backoff FIXED = attempt -> 100L;

    public static final Backoff LINEAR = attempt -> 100L * attempt;

    /** A method reference, because this one's cap is worth a sentence. */
    public static final Backoff EXPONENTIAL = RetryPolicyAfter::exponentialDelay;

    private RetryPolicyAfter() {
    }

    /** Doubling, capped at 2^10 so a long retry loop cannot run away. */
    private static long exponentialDelay(int attempt) {
        return 100L * (1L << Math.min(attempt, 10));
    }

    /** Unchanged: the consumer never depended on how the policies were spelled. */
    public static long totalDelay(Backoff backoff, int attempts) {
        long total = 0;
        for (int i = 1; i <= attempts; i++) {
            total += backoff.delayMillis(i);
        }
        return total;
    }

    /** The supplier ceremony collapses to the value it was wrapping. */
    public static long totalDelayOfDefault(int attempts) {
        return totalDelay(EXPONENTIAL, attempts);
    }
}
