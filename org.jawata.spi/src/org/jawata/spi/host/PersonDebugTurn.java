package org.jawata.spi.host;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The turn state of an engine whose own debugger route serves the person (Sprint 28i, Stage 25, ARCHITECTURE-28i-s25.md, F3): it is the
 * person's turn to debug from the first debug launch begun until the last one ends. The engine's debugger route calls {@link #begin()} when
 * the person starts a launch and ends the returned {@link Launch} when it is over; the engine's own tools ask {@link #personIsDebugging()}.
 * The engine is told once when a launch begins while none was running, so the agent's sessions end and the person has the program to
 * themselves.
 */
public final class PersonDebugTurn implements DebugTurn {

    /** One debug launch of the person's; ending it more than once ends it once. */
    public final class Launch {

        private final AtomicBoolean ended = new AtomicBoolean();

        private Launch() {
        }

        /** The person's launch is over. */
        public void end() {
            if (ended.compareAndSet(false, true)) {
                running.decrementAndGet();
            }
        }
    }

    private final AtomicInteger running = new AtomicInteger();
    private final List<Runnable> endAgentSessions = new CopyOnWriteArrayList<>();

    @Override
    public boolean personIsDebugging() {
        return running.get() > 0;
    }

    @Override
    public void onPersonStartsDebugging(Runnable endAgentSessions) {
        this.endAgentSessions.add(endAgentSessions);
    }

    /**
     * The person starts a debug launch of their own. When no other launch of theirs is running, the agent's sessions are ended first.
     *
     * @return the launch, to be ended when it is over
     */
    public Launch begin() {
        if (running.getAndIncrement() == 0) {
            endAgentSessions.forEach(Runnable::run);
        }
        return new Launch();
    }
}
