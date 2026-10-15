package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostOS;
import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.learn.GateOutcome;
import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28g S9a M5b — the gate, in the step's own words: <em>the schedule fires a hire
 * with no human present</em>.
 *
 * <p>This is the half no code anywhere had run. The Rust cron is library-only — its own
 * doc says <em>"no manager thread invokes it yet"</em> and its only caller sits inside a
 * {@code #[cfg(test)]} block — and Java's only {@code ScheduledExecutorService} was
 * one-shot. So the firing thread is new on both sides, and this is what proves it fires.</p>
 *
 * <h2>How "no human present" is actually asserted</h2>
 *
 * <p>A test that called {@code issue} and then checked a file would prove nothing about
 * who called it. So the hire records the NAME OF THE THREAD it ran on, and the assertion
 * is that the name is the scheduler's. Nothing in a test body can produce that.</p>
 */
class SeatSchedulerFiresUnattendedTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HostProcesses host = HostProcesses.system();
    private Path dir;
    private RuntimeArtifactStore artifacts;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(!HostOS.current().isWindows(), "the brain fixture is a POSIX shell script");
        dir = Files.createTempDirectory("jawata-seat-scheduler-");
        artifacts = new RuntimeArtifactStore(dir.resolve("artifacts"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dir != null) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // best effort; the temp dir is the OS's problem after this
                    }
                });
            }
        }
    }

    private HostCommand brain(String body) throws Exception {
        Path file = Files.createTempFile(dir, "brain-", ".sh");
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return HostCommand.of(file.toString());
    }

    private static SeatScheduler.ScheduledSeat seat(String name, String schedule) {
        return new SeatScheduler.ScheduledSeat(name, schedule, "Tidy " + name + ".");
    }

    /** A clock stopped at a Sunday 06:00, so a schedule naming it is exercised on a real date. */
    private static Clock stoppedAt(int minute) {
        return Clock.fixed(LocalDateTime.of(2026, 10, 4, 6, minute)
            .atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    }

    @Test
    @DisplayName("M5b's GATE: the schedule fires a real hire, on the scheduler's own thread, with nobody calling it")
    void theScheduleFiresAHireWithNoHumanPresent() throws Exception {
        HostCommand command = brain("""
            echo "I would tidy the ledger's naming."
            echo "TOOL-STEPS: 3"
            echo "COST-USD: 0.11"
            """);
        HiredRun run = new HiredRun(host, artifacts, new PlanLedger(), null);
        GatedStep step = new GatedStep(0, "compile_workspace", JSON.readTree("{}"));

        CountDownLatch fired = new CountDownLatch(1);
        AtomicReference<String> firedOnThread = new AtomicReference<>();
        AtomicReference<HiredRun.Proposal> proposal = new AtomicReference<>();

        SeatScheduler.Firing hire = s -> {
            firedOnThread.set(Thread.currentThread().getName());
            proposal.set(run.issue(new ScriptDialect(command.executable(), List.of()), command,
                s.brief(), HiredBrain.Ceilings.of(Duration.ofSeconds(20)), List.of(step),
                gate -> new GateOutcome(gate.tool(), gate.gateArguments(), 0, true)));
            fired.countDown();
        };

        // NOTHING BELOW CALLS issue. The scheduler is started and then waited on.
        try (SeatScheduler scheduler = new SeatScheduler(
                List.of(seat("cataloguer", "* * * * *")), hire, stoppedAt(0), 50)) {
            scheduler.start();
            assertTrue(fired.await(30, TimeUnit.SECONDS),
                "the schedule never fired — which is the whole deliverable");
        }

        HiredRun.Proposal made = proposal.get();
        assertAll(
            () -> assertEquals("jawata-seat-scheduler", firedOnThread.get(),
                "THE CLAUSE: the hire ran on the SCHEDULER's thread, so nobody called it."
                    + " A test invoking issue itself could not produce this name"),
            () -> assertTrue(Files.exists(made.record()), "and a proposal record is on disk"),
            () -> assertTrue(artifacts.list().contains(made.artifactId()),
                "listed as an artifact like any other"),
            () -> assertTrue(Files.readString(made.record()).contains("Tidy cataloguer."),
                "carrying the brief the SEAT declared, not one the test passed in"),
            () -> assertTrue(Files.readString(made.record()).contains("I would tidy"),
                "and what the brain said back"),
            () -> assertEquals(1, made.stepsPassed(), "its gate ran and the ledger advanced"),
            () -> assertNotEquals(Boolean.TRUE,
                artifacts.readManifest(made.artifactId()).orElseThrow().get("applied"),
                "and it did not report applying anything — read from the census, which this"
                    + " scratch directory cannot take, so the answer is unknown rather than false"));
    }

    @Test
    @DisplayName("a seat with NO schedule never fires, and that is correct rather than a fault")
    void aSeatWithNoScheduleNeverFires() {
        AtomicInteger fires = new AtomicInteger();
        try (SeatScheduler scheduler = new SeatScheduler(
                List.of(seat("adhoc", null), new SeatScheduler.ScheduledSeat("blank", "  ", "b")),
                s -> fires.incrementAndGet(), stoppedAt(0), 50)) {
            assertAll(
                () -> assertEquals(0, scheduler.tick(LocalDateTime.of(2026, 10, 4, 6, 0))),
                () -> assertEquals(0, fires.get(), "ad-hoc means ad-hoc"),
                () -> assertTrue(SeatScheduler.due(List.of(seat("adhoc", null)),
                    LocalDateTime.of(2026, 10, 4, 6, 0)).malformed().isEmpty(),
                    "and an absent schedule is NOT a malformed one — the distinction the"
                        + " reference loses in the other direction"));
        }
    }

    @Test
    @DisplayName("THE DEFECT NOT CARRIED: a malformed schedule is REPORTED by name, and does not silence the good seats")
    void aMalformedScheduleIsReportedAndDoesNotSilenceTheOthers() {
        // The reference reads a seat's cron with `Schedule::parse(expr).ok()`, so a typo
        // becomes "no schedule" and the seat stops firing forever with nothing said.
        List<SeatScheduler.ScheduledSeat> seats = List.of(
            seat("broken", "0 6 * *"),
            seat("alsoBroken", "99 * * * *"),
            seat("good", "* * * * *"));

        SeatScheduler.Due due = SeatScheduler.due(seats, LocalDateTime.of(2026, 10, 4, 6, 0));

        assertAll(
            () -> assertEquals(2, due.malformed().size(),
                "both bad schedules are REPORTED rather than dropped"),
            () -> assertEquals(List.of("alsoBroken", "broken"),
                due.malformed().stream().map(SeatScheduler.Malformed::seat).sorted().toList(),
                "by name, because a report that cannot name the seat cannot be acted on"),
            () -> assertTrue(due.malformed().stream()
                    .anyMatch(m -> m.reason().contains("got 4")),
                "with the reason, so it can be fixed without guessing"),
            () -> assertEquals(1, due.seats().size(),
                "AND THE OTHER HALF: the good seat still fires. Throwing would have let one"
                    + " typo stop every other seat — a worse failure than the one being fixed"),
            () -> assertEquals("good", due.seats().get(0).name()));
    }

    @Test
    @DisplayName("a seat fires ONCE per minute however often the loop wakes")
    void aSeatFiresOncePerMinute() {
        AtomicInteger fires = new AtomicInteger();
        LocalDateTime at = LocalDateTime.of(2026, 10, 4, 6, 0);
        try (SeatScheduler scheduler = new SeatScheduler(
                List.of(seat("cataloguer", "* * * * *")),
                s -> fires.incrementAndGet(), stoppedAt(0), 50)) {
            assertAll(
                () -> assertEquals(1, scheduler.tick(at), "the first tick of a minute fires"),
                () -> assertEquals(0, scheduler.tick(at.plusSeconds(20)),
                    "a later SECOND of the same minute does not — cron's unit is the minute,"
                        + " and the loop wakes faster than that so it cannot miss one"),
                () -> assertEquals(0, scheduler.tick(at.plusSeconds(59))),
                () -> assertEquals(1, scheduler.tick(at.plusMinutes(1)),
                    "THE CONTROL: the next minute fires again, so the guard is a per-minute"
                        + " gate and not a fire-once-ever latch"),
                () -> assertEquals(2, fires.get()));
        }
    }

    @Test
    @DisplayName("one seat throwing does not stop the others, or the first broken seat becomes the last to fire")
    void oneSeatThrowingDoesNotStopTheOthers() {
        AtomicInteger good = new AtomicInteger();
        try (SeatScheduler scheduler = new SeatScheduler(
                List.of(seat("throws", "* * * * *"), seat("works", "* * * * *")),
                s -> {
                    if ("throws".equals(s.name())) {
                        throw new IllegalStateException("this seat's detector blew up");
                    }
                    good.incrementAndGet();
                }, stoppedAt(0), 50)) {
            assertAll(
                () -> assertEquals(1, scheduler.tick(LocalDateTime.of(2026, 10, 4, 6, 0)),
                    "one of the two fired"),
                () -> assertEquals(1, good.get(), "and it was the one that works"));
        }
    }

    @Test
    @DisplayName("close() does not wait for an in-flight fire")
    void closeDoesNotWaitForAnInFlightFire() throws Exception {
        // The firing is outside the monitor on purpose: a fire is a hired run, which can
        // take minutes, and a close that waited on one would hang for as long as the run
        // it is trying to stop.
        CountDownLatch inFire = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SeatScheduler scheduler = new SeatScheduler(List.of(seat("slow", "* * * * *")),
            s -> {
                inFire.countDown();
                release.await(20, TimeUnit.SECONDS);
            }, stoppedAt(0), 50);

        scheduler.start();
        assertTrue(inFire.await(20, TimeUnit.SECONDS), "proof of life: it really is mid-fire");

        long startedClosing = System.nanoTime();
        scheduler.close();
        long millis = (System.nanoTime() - startedClosing) / 1_000_000;
        release.countDown();

        assertTrue(millis < 5_000, "close() returned in " + millis + "ms while a fire was"
            + " still in flight — holding the lock across the fire would have made this"
            + " wait for the run");
    }
}
