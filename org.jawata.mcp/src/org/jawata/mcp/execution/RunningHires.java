package org.jawata.mcp.execution;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The hires that are running right now, so one of them can be stopped from the Agent Control panel (Sprint 28h, S9).
 *
 * <p>The agent knows a hire by the tool call that started it, and the hire tool knows it by its seat and brief; neither knows the other's
 * handle, so a running hire is found by the pair, seat and brief, which two hires only share when they are the same job.</p>
 *
 * <p>A stop does two things. It runs what the hire registered to be run on a stop, which is how the code that started the brain's process
 * kills it: a hire reading that process's output is blocked in a read that a thread interrupt cannot end, so killing the process is what ends
 * the read. Then it interrupts the thread, for a hire that is waiting rather than reading. The hire is marked as stopped by the person, so
 * its answer says so instead of reporting a failed or interrupted run.</p>
 */
public final class RunningHires {

    private static final class Running {
        final String seat;
        final String brief;
        final Thread thread;
        final List<Runnable> onStop = new CopyOnWriteArrayList<>();

        Running(String seat, String brief, Thread thread) {
            this.seat = seat;
            this.brief = brief;
            this.thread = thread;
        }
    }

    /** A hire registered as running on the current thread; closing it removes the hire. */
    public final class Registration implements AutoCloseable {
        private final Running hire;

        private Registration(Running hire) {
            this.hire = hire;
        }

        /** Whether the person stopped this hire; true once, then the mark is gone. */
        public boolean wasStopped() {
            return stopped.remove(hire.thread);
        }

        @Override
        public void close() {
            synchronized (RunningHires.this) {
                running.remove(hire);
                stopped.remove(hire.thread);
            }
        }
    }

    private static final RunningHires SHARED = new RunningHires();

    private final List<Running> running = new CopyOnWriteArrayList<>();
    private final Set<Thread> stopped = ConcurrentHashMap.newKeySet();

    /** The one registry of running hires in this process, which the hire tool fills and the agent face reads. */
    public static RunningHires shared() {
        return SHARED;
    }

    /**
     * Registers a hire of this seat for this brief as running on the current thread. A stop mark left on this thread by an earlier hire is
     * dropped: it would otherwise end this hire the moment its brain's process started. Registering, stopping and closing take turns, so a
     * stop cannot mark a thread whose hire has just closed.
     */
    public synchronized Registration begin(String seat, String brief) {
        Running hire = new Running(seat, normal(brief), Thread.currentThread());
        stopped.remove(hire.thread);
        running.add(hire);
        return new Registration(hire);
    }

    /**
     * Registers what a stop does to the hire running on the current thread, such as killing the brain's process it has just started. When no
     * hire is registered on this thread, as in a run made outside the hire tool, there is nothing to stop and nothing is registered. A stop
     * that came before the process was started has already gone by, so the action is run at once: otherwise the process would start after
     * the stop and nothing would ever end it.
     */
    public void onStop(Runnable action) {
        for (Running hire : running) {
            if (hire.thread == Thread.currentThread()) {
                hire.onStop.add(action);
                if (stopped.contains(hire.thread)) {
                    action.run();
                }
            }
        }
    }

    /** Stops every running hire of this seat for this brief and answers how many there were. */
    public synchronized int stop(String seat, String brief) {
        int stoppedCount = 0;
        for (Running hire : running) {
            if (hire.seat.equals(seat) && hire.brief.equals(normal(brief))) {
                stopped.add(hire.thread);
                hire.onStop.forEach(Runnable::run);
                hire.thread.interrupt();
                stoppedCount++;
            }
        }
        return stoppedCount;
    }

    private static String normal(String brief) {
        return brief == null ? "" : brief.strip();
    }
}
