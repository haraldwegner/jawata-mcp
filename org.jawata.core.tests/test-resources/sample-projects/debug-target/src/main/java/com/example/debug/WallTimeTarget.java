package com.example.debug;

/**
 * Sprint 24 audit (2026-07-14, T2.1) fixture — a program whose WALL-CLOCK time is dominated
 * by BLOCKING, not by CPU. It spends almost all of its elapsed time parked inside {@link
 * #waitOnLock()} (blocked on a monitor another thread holds) and only a little burning CPU in
 * {@link #burnCpu()}.
 *
 * <p>A CPU profile ranks {@code burnCpu} near the top and would barely see {@code waitOnLock}
 * (a blocked thread is not on the CPU, so it is not execution-sampled). A WALL-CLOCK profile
 * must rank {@code waitOnLock} FAR higher — that is where the elapsed time actually goes.
 * This fixture exists so the wall dimension can be shown to be a genuine wall-time profile,
 * not CPU-time or lock-time under a different name.</p>
 */
public final class WallTimeTarget {

    static final Object LOCK = new Object();

    public static void main(String[] args) throws Exception {
        // A holder grabs LOCK for long stretches, so the main thread's waitOnLock() blocks
        // on the monitor for most of its wall clock.
        Thread holder = new Thread(() -> {
            while (true) {
                synchronized (LOCK) {
                    sleepQuietly(80);
                }
                sleepQuietly(5);   // a brief release so main can (occasionally) get in
            }
        }, "lock-holder");
        holder.setDaemon(true);
        holder.start();

        while (true) {
            burnCpu();       // a little CPU
            waitOnLock();    // a lot of blocked wall time
        }
    }

    /** A little CPU work — where a CPU profile points. */
    static long burnCpu() {
        long total = 0;
        for (int i = 0; i < 200_000; i++) {
            total += (i * 31) ^ (i >>> 3);
        }
        return total;
    }

    /** Blocks on the contended monitor — where WALL time goes, invisible to CPU sampling. */
    static void waitOnLock() {
        synchronized (LOCK) {
            // Got it briefly; the point is the time spent BLOCKED getting here.
            sleepQuietly(1);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private WallTimeTarget() {
    }
}
