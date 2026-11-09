package org.jawata.mcp.execution;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
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

    /**
     * What a running hire's brain has reported so far, read off its stream as it arrives (S9, E3): the tool calls it has made, the size of
     * its context at its last usage report, and the cost it last reported. A figure the brain has not sent is absent, never zero.
     */
    public record Progress(int toolSteps, OptionalLong contextTokens, OptionalDouble costUsd) {
    }

    private static final class Running {
        final String seat;
        final String brief;
        final Thread thread;
        final List<Runnable> onStop = new CopyOnWriteArrayList<>();
        volatile Progress progress = new Progress(0, OptionalLong.empty(), OptionalDouble.empty());
        /** Whether the brain is compacting its own context right now, as its stream last said. */
        volatile boolean compacting;

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

        /** The brain the person moved this hire onto, if the stop was a Replace (D53 (i)); answered once, then the mark is gone. */
        public Optional<Replacement> replacement() {
            return Optional.ofNullable(replacements.remove(hire.thread));
        }

        @Override
        public void close() {
            synchronized (RunningHires.this) {
                running.remove(hire);
                stopped.remove(hire.thread);
                replacements.remove(hire.thread);
            }
        }
    }

    /** The brain, and the model of it, a running hire is to continue on after its stop (D53 (i)). The model is empty for a brain that takes none. */
    public record Replacement(SeatHire.Brain brain, String model) {
    }

    private static final RunningHires SHARED = new RunningHires();

    private final List<Running> running = new CopyOnWriteArrayList<>();
    private final Set<Thread> stopped = ConcurrentHashMap.newKeySet();
    private final java.util.Map<Thread, Replacement> replacements = new ConcurrentHashMap<>();

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

    /** Counts one more tool call for the hire running on the current thread; a thread running no hire is ignored. */
    public void noteToolStep() {
        for (Running hire : running) {
            if (hire.thread == Thread.currentThread()) {
                Progress seen = hire.progress;
                hire.progress = new Progress(seen.toolSteps() + 1, seen.contextTokens(), seen.costUsd());
            }
        }
    }

    /**
     * Records what the brain of the hire running on the current thread last reported about its usage. A figure the report did not carry keeps
     * the value the hire had before it: a report that names only a context size has not said the cost fell to nothing.
     */
    public void noteUsage(OptionalLong contextTokens, OptionalDouble costUsd) {
        for (Running hire : running) {
            if (hire.thread == Thread.currentThread()) {
                Progress seen = hire.progress;
                hire.progress = new Progress(seen.toolSteps(), contextTokens.isPresent() ? contextTokens : seen.contextTokens(),
                    costUsd.isPresent() ? costUsd : seen.costUsd());
            }
        }
    }

    /** Records that the brain of the hire running on the current thread began or ended compacting its context; a thread running no hire is ignored. */
    public void noteCompaction(boolean compacting) {
        for (Running hire : running) {
            if (hire.thread == Thread.currentThread()) {
                hire.compacting = compacting;
            }
        }
    }

    /** Whether the running hire of this seat for this brief is compacting its context at this moment; false when no such hire is running. */
    public boolean compacting(String seat, String brief) {
        for (Running hire : running) {
            if (hire.seat.equals(seat) && hire.brief.equals(normal(brief))) {
                return hire.compacting;
            }
        }
        return false;
    }

    /** What the running hire of this seat for this brief has reported so far; none when no such hire is running. */
    public Optional<Progress> progress(String seat, String brief) {
        for (Running hire : running) {
            if (hire.seat.equals(seat) && hire.brief.equals(normal(brief))) {
                return Optional.of(hire.progress);
            }
        }
        return Optional.empty();
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

    /**
     * Stops every running hire of this seat for this brief so that it continues on another brain (D53 (i)), and answers how many there were.
     * The hire's own run reads the mark and hires the seat again; nothing else is told. Only the brain's process is ended and the thread is not
     * interrupted: the run then reads to the end of the output and keeps what the brain has said, which an interrupt would lose, and the next brain needs it.
     */
    public synchronized int replace(String seat, String brief, SeatHire.Brain brain, String model) {
        int replaced = 0;
        for (Running hire : running) {
            if (hire.seat.equals(seat) && hire.brief.equals(normal(brief))) {
                replacements.put(hire.thread, new Replacement(brain, model));
                stopped.add(hire.thread);
                hire.onStop.forEach(Runnable::run);
                replaced++;
            }
        }
        return replaced;
    }

    private static String normal(String brief) {
        return brief == null ? "" : brief.strip();
    }
}
