package org.jawata.mcp.engine;

import java.time.Duration;
import java.util.function.IntConsumer;

import org.jawata.spi.host.EngineLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the engine that runs alone does when its listener fails (Sprint 28k, E16; contract §5): it ends with a status a supervisor
 * reads. 13 is a port that is taken, 70 is a listener that went deaf behind a living process, 0 is every other end.
 *
 * <p>The status is the application's result, not a call into the process: the application returns {@link #exitCode()} when its
 * message loop returns, and the launcher exits with it. That is how 13 has always left the process, and 70 now leaves the same way,
 * through an orderly framework shutdown. The engine itself never exits the process.</p>
 */
public final class ResidentLifecycle implements EngineLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ResidentLifecycle.class);

    /** The status for a port that cannot be bound. */
    public static final int EXIT_PORT_TAKEN = 13;

    /** The status for a listener that stopped answering while the process lived. */
    public static final int EXIT_LISTENER_DEAF = 70;

    private static final Duration HALT_AFTER = Duration.ofSeconds(15);

    private final Runnable stopListening;
    private final IntConsumer halt;
    private final Duration haltAfter;
    private volatile int exitCode;

    /**
     * @param stopListening ends the message loop, so the application can return its result
     */
    public ResidentLifecycle(Runnable stopListening) {
        this(stopListening, status -> Runtime.getRuntime().halt(status), HALT_AFTER);
    }

    /**
     * @param stopListening ends the message loop, so the application can return its result
     * @param halt          what ends the process when an orderly end hangs
     * @param haltAfter     how long an orderly end gets
     */
    public ResidentLifecycle(Runnable stopListening, IntConsumer halt, Duration haltAfter) {
        this.stopListening = stopListening;
        this.halt = halt;
        this.haltAfter = haltAfter;
    }

    /** The status the application returns: 0 unless the listener failed. */
    public int exitCode() {
        return exitCode;
    }

    @Override
    public void listenerDeaf() {
        log.error("HTTP LISTENER STOPPED ANSWERING while the process is alive — exiting with status {}"
            + " so the resident is restarted instead of staying deaf", EXIT_LISTENER_DEAF);
        exitCode = EXIT_LISTENER_DEAF;
        // An orderly end is tried first so the shared store closes cleanly. A deaf process may have other things wrong with it,
        // so if that hangs the process is halted after a grace period.
        Thread fallback = new Thread(() -> {
            try {
                Thread.sleep(haltAfter.toMillis());
            } catch (InterruptedException ignored) {
                return;
            }
            halt.accept(EXIT_LISTENER_DEAF);
        }, "ResidentLifecycle-deaf-halt");
        fallback.setDaemon(true);
        fallback.start();
        stopListening.run();
    }

    @Override
    public void cannotListen(int port, Exception cause) {
        // jawata-mcp#2 (Sprint 27a Stage 8): a resident that cannot BIND must fail LOUDLY and NON-ZERO — a failed start reported as
        // a clean exit is the lie class this product exists to prevent, and it left jawata-studio rendering "exited unexpectedly
        // (status 0)" with nothing to diagnose. stderr, not SLF4J: the dist ships no logger binding, so a log line here is silence.
        System.err.println("FATAL: cannot bind port " + port
            + " — " + cause.getMessage()
            + " (is another resident already listening on it?)");
        exitCode = EXIT_PORT_TAKEN;
    }
}
