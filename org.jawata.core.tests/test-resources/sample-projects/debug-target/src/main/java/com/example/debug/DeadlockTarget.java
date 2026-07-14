package com.example.debug;

/**
 * Sprint 24 (D10/Stage 15) fixture — a deterministic two-thread deadlock, for the
 * profiling floor's {@code deadlock} action. Two threads lock the same two monitors
 * in opposite orders; a fixed startup sleep gives each thread time to acquire its
 * first lock before it reaches for the second, so the deadlock forms every run —
 * a flaky fixture would prove nothing about the tool reading it.
 */
public final class DeadlockTarget {

    static final Object LOCK_A = new Object();
    static final Object LOCK_B = new Object();

    public static void main(String[] args) throws Exception {
        Thread t1 = new Thread(() -> acquireBothLocks(LOCK_A, LOCK_B), "lock-a-then-b");
        Thread t2 = new Thread(() -> acquireBothLocks(LOCK_B, LOCK_A), "lock-b-then-a");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
        // The deadlock is permanent once formed — sleep well past both threads
        // reaching their second lock, then just keep the JVM alive to be probed.
        Thread.sleep(Long.MAX_VALUE);
    }

    private static void acquireBothLocks(Object first, Object second) {
        synchronized (first) {
            sleepQuietly(500);
            synchronized (second) {
                // Never reached.
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private DeadlockTarget() {
    }
}
