package com.example.debug;

/**
 * Sprint 24 audit (2026-07-14, T1.7) fixture — a program built so that evaluating a
 * conditional breakpoint's condition INVOKES a method that never returns, because it blocks
 * on a lock a different, already-suspended thread is holding.
 *
 * <p>This is the JDI event-handler-thread invocation hazard. A conditional breakpoint's
 * condition was evaluated ON the event pump — the single thread that drains JDI's event
 * queue — via a blocking {@code invokeMethod}. If the invoked method does not return, the
 * pump is wedged for the rest of the session: every later {@code wait} times out forever and
 * no hit ever arrives again. The condition here calls {@link #needsTheHeldLock()}, which
 * tries to take {@link #LOCK} — held for the whole run by a separate daemon thread — so the
 * invoke blocks. The fix evaluates off the pump with a timeout, so the session survives.</p>
 */
public final class NestedInvokeTarget {

    static final Object LOCK = new Object();
    static volatile int tick;

    public static void main(String[] args) throws Exception {
        // A holder thread takes LOCK and never lets go — so an invoke that needs LOCK, run
        // on ANY other thread, blocks forever.
        Thread holder = new Thread(() -> {
            synchronized (LOCK) {
                while (true) {
                    try {
                        Thread.sleep(1_000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "lock-holder");
        holder.setDaemon(true);
        holder.start();
        Thread.sleep(400);   // let the holder acquire LOCK

        // The main loop the debugger breaks on. When it stops here and a condition invokes
        // needsTheHeldLock() on THIS thread, that call blocks on LOCK (held by `holder`).
        while (true) {
            tick++;
            Thread.sleep(50);
        }
    }

    /** A conditional breakpoint's condition invokes this; it blocks on the held lock. */
    static boolean needsTheHeldLock() {
        synchronized (LOCK) {
            return true;
        }
    }

    private NestedInvokeTarget() {
    }
}
