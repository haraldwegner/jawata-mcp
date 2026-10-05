package org.jawata.mcp.execution;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fires seats on their own schedule, with nobody in the chair (Sprint 28g S9a M5b).
 *
 * <h2>A malformed schedule is LOUD and does not disable the others</h2>
 *
 * <p>This is the defect the step exists to not carry. The Rust reference's
 * {@code due_seats} reads a seat's cron with {@code Schedule::parse(expr).ok()}, which
 * turns a parse FAILURE into the same answer as "this seat has no schedule" — so a
 * typo'd expression silently stops a seat firing forever, in flat contradiction of
 * {@code Schedule::parse}'s own promise of a loud error.</p>
 *
 * <p>So {@link #due} keeps THREE states apart where the reference keeps two:</p>
 *
 * <ul>
 *   <li>no schedule at all — ad-hoc only, never fires, and that is correct rather than a
 *       fault;</li>
 *   <li>a schedule that reads — fires when it matches;</li>
 *   <li>a schedule that does NOT read — reported, by name, with the reason.</li>
 * </ul>
 *
 * <p>And it reports rather than throwing, which is the second half of the decision. A
 * throw would let one typo'd seat stop every OTHER seat from firing — a worse failure than
 * the one being fixed, and silent in its own way, since nothing would say which seat did
 * it. The malformed ones come back beside the due ones so the caller can fire what is
 * good and complain about what is not.</p>
 *
 * <h2>A seat fires at most once per minute</h2>
 *
 * <p>Cron's unit is the minute, and the tick loop runs faster than that so it cannot
 * drift past one. {@link #tick} therefore refuses a minute it has already handled. The
 * reference never faced this because its cron has no caller at all — <em>"no manager
 * thread invokes it yet"</em> — so the firing thread, and this guard with it, are new on
 * both sides.</p>
 *
 * <h2>A minute that is MISSED is not caught up, deliberately</h2>
 *
 * <p>If the host suspends or the loop is starved past a whole minute, the seats due in
 * that minute do not fire late — the guard stops a minute firing twice and nothing makes
 * a skipped one fire at all. That is cron's own behaviour rather than an omission, and
 * for this scheduler it is also the safer half: catching up after a long suspend would
 * fire a BURST of hired runs, each of which costs money, with nobody present to stop it.
 * A reader who wants the other behaviour is looking for anacron, and should say so rather
 * than find it here.</p>
 */
public final class SeatScheduler implements AutoCloseable {

    private static final Logger log = Logger.getLogger(SeatScheduler.class.getName());

    /** How often the loop wakes to see whether the minute has turned. */
    private static final long DEFAULT_TICK_MILLIS = 5_000;

    /**
     * A seat the scheduler can fire.
     *
     * @param name     what it is called, which is what a report names
     * @param schedule its five-field cron, or NULL for ad-hoc only — never fires on its own
     * @param brief    what the brain is asked when it fires
     */
    public record ScheduledSeat(String name, String schedule, String brief) {

        public ScheduledSeat {
            Objects.requireNonNull(name, "a seat with no name cannot be reported on");
            Objects.requireNonNull(brief, "a seat that fires with no brief asks nothing");
        }

        /** Is this seat scheduled at all? A seat without one is ad-hoc, not broken. */
        public boolean isScheduled() {
            return schedule != null && !schedule.isBlank();
        }
    }

    /**
     * A seat whose schedule could not be read — the state the reference discards.
     *
     * @param seat       which seat
     * @param expression what it said
     * @param reason     why it could not be read
     */
    public record Malformed(String seat, String expression, String reason) {
    }

    /**
     * What a tick found.
     *
     * @param seats     the seats due to fire now
     * @param malformed the seats whose schedule could not be read — NOT silently skipped
     */
    public record Due(List<ScheduledSeat> seats, List<Malformed> malformed) {

        public Due {
            seats = List.copyOf(seats);
            malformed = List.copyOf(malformed);
        }
    }

    /** What firing a seat actually does. The caller owns it; this class only decides WHEN. */
    @FunctionalInterface
    public interface Firing {

        /** Fire this seat. An exception is logged and does not stop the loop. */
        void fire(ScheduledSeat seat) throws Exception;
    }

    private final List<ScheduledSeat> seats;
    private final Firing firing;
    private final Clock clock;
    private final long tickMillis;

    private Thread thread;
    private volatile boolean running;
    private LocalDateTime lastMinuteHandled;

    public SeatScheduler(List<ScheduledSeat> seats, Firing firing) {
        this(seats, firing, Clock.systemDefaultZone(), DEFAULT_TICK_MILLIS);
    }

    /**
     * The test door: an injected clock and tick interval, so the firing can be observed
     * without waiting for a real minute to turn.
     *
     * <p>Package-private for the same reason {@code WorkspaceFileWatcher}'s poll-interval
     * constructor is: WHEN to wake is a property of the real host, and a test needs it to
     * be a property of the test.</p>
     */
    SeatScheduler(List<ScheduledSeat> seats, Firing firing, Clock clock, long tickMillis) {
        this.seats = List.copyOf(Objects.requireNonNull(seats, "there are no seats to schedule"));
        this.firing = Objects.requireNonNull(firing, "a scheduler that fires nothing is a timer");
        this.clock = Objects.requireNonNull(clock, "a schedule needs to know the time");
        if (tickMillis <= 0) {
            throw new IllegalArgumentException("a tick of " + tickMillis + "ms never wakes");
        }
        this.tickMillis = tickMillis;
    }

    /**
     * Which seats are due at this moment, and which seats' schedules could not be read.
     *
     * <p>Pure, and the reason it takes the moment rather than reading a clock: a schedule
     * is judged against a time, and a function that fetched its own would be untestable at
     * every minute but the current one.</p>
     */
    public static Due due(List<ScheduledSeat> seats, LocalDateTime at) {
        Objects.requireNonNull(seats, "there are no seats to ask about");
        Objects.requireNonNull(at, "a schedule cannot be judged against no moment");
        List<ScheduledSeat> ready = new ArrayList<>();
        List<Malformed> malformed = new ArrayList<>();
        for (ScheduledSeat seat : seats) {
            if (!seat.isScheduled()) {
                continue;
            }
            SeatSchedule schedule;
            try {
                schedule = SeatSchedule.parse(seat.schedule());
            } catch (RuntimeException e) {
                // THE DEFECT, NOT CARRIED: the reference's `.ok()` would drop this seat
                // here and say nothing about it, ever.
                malformed.add(new Malformed(seat.name(), seat.schedule(), e.getMessage()));
                continue;
            }
            if (schedule.matches(at)) {
                ready.add(seat);
            }
        }
        return new Due(ready, malformed);
    }

    /**
     * Handle one minute: fire what is due, complain about what cannot be read.
     *
     * @return how many seats were fired
     */
    int tick(LocalDateTime at) {
        Objects.requireNonNull(at, "a tick happens at a time");
        LocalDateTime minute = at.truncatedTo(ChronoUnit.MINUTES);
        synchronized (this) {
            if (minute.equals(lastMinuteHandled)) {
                // The loop wakes faster than a minute so it cannot miss one; the price is
                // that it sees the same minute several times, and cron fires once a minute.
                return 0;
            }
            lastMinuteHandled = minute;
        }

        // THE FIRING IS DELIBERATELY OUTSIDE THE MONITOR. A fire is a hired run — a child
        // process read that can take minutes — and holding the lock across it would make
        // close() wait for it, so stopping the scheduler would hang for as long as the
        // run it is trying to stop. The lock guards the minute guard and nothing else.
        Due now = due(seats, at);
        for (Malformed bad : now.malformed()) {
            // Every tick it is still broken, because the requirement is that it is never
            // SILENT — a seat that stopped firing because of a typo must be impossible to
            // miss, and once-only would be missed by anyone who started reading later.
            log.warning(() -> "seat '" + bad.seat() + "' has a schedule that cannot be read,"
                + " so it is NOT firing: '" + bad.expression() + "' — " + bad.reason());
        }
        int fired = 0;
        for (ScheduledSeat seat : now.seats()) {
            try {
                firing.fire(seat);
                fired++;
            } catch (Exception e) {
                // One seat's failure must not stop the others, or the first broken seat
                // silently becomes the last seat that ever fires.
                log.log(Level.WARNING, "seat '" + seat.name() + "' failed to fire", e);
            }
        }
        return fired;
    }

    /** Start the firing thread. Idempotent. */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::runTickLoop, "jawata-seat-scheduler");
        thread.setDaemon(true);
        thread.start();
        log.info(() -> "seat scheduler started over " + seats.size() + " seat(s)");
    }

    @Override
    public void close() {
        Thread stopping;
        synchronized (this) {
            if (!running) {
                return;
            }
            running = false;
            stopping = thread;
            thread = null;
        }
        // Outside the monitor on purpose: the loop may be inside a fire, which does not
        // hold the lock, and this must not start waiting on one either. The interrupt
        // reaches a blocked read, which is what makes a close prompt rather than polite.
        if (stopping != null) {
            stopping.interrupt();
        }
    }

    private boolean isRunning() {
        return running;
    }

    private void runTickLoop() {
        while (isRunning()) {
            try {
                tick(LocalDateTime.now(clock));
                Thread.sleep(tickMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // The loop outlives anything one tick can throw: this thread is the only
                // thing that fires seats, and a thread that died takes every future
                // schedule with it without reporting that it has.
                log.log(Level.WARNING, "a scheduler tick failed", e);
            }
        }
    }
}
