package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sprint 28h S12 (D29 category C): the schedule a seat file declares is fired on the new start path, as a call of the hire tool made
 * through the engine's one tool door, by the scheduler's own thread with nobody calling it.
 */
class ScheduledHiresTest {

    private Path seats;
    private Path work;

    @BeforeEach
    void setUp() throws Exception {
        seats = Files.createTempDirectory("jawata-scheduled-hires-seats-");
        work = Files.createTempDirectory("jawata-scheduled-hires-work-");
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Path dir : List.of(seats, work)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private Path seat(String file, String front) throws Exception {
        Path path = seats.resolve(file);
        Files.writeString(path, "---\n" + front + "\n---\n\nYou are a seat.\n");
        return path;
    }

    /** A tool door that records each call and answers with the given response. */
    private static final class RecordingDoor implements ToolCalls {
        final List<JsonNode> hires = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        final List<String> tools = new CopyOnWriteArrayList<>();
        final CountDownLatch fired = new CountDownLatch(1);
        final ToolResponse answer;

        RecordingDoor(ToolResponse answer) {
            this.answer = answer;
        }

        @Override
        public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
            tools.add(name);
            hires.add(arguments);
            threads.add(Thread.currentThread().getName());
            fired.countDown();
            return answer;
        }

        @Override
        public List<Map<String, Object>> getToolDefinitions() {
            return List.of();
        }
    }

    private static Clock stoppedAt(int minute) {
        return Clock.fixed(LocalDateTime.of(2026, 11, 1, 6, minute).atZone(ZoneId.systemDefault()).toInstant(),
            ZoneId.systemDefault());
    }

    @Test
    @DisplayName("only a seat that declares a schedule and is switched on is scheduled, and a folder that cannot be listed is reported")
    void onlyAScheduledSeatThatIsOnIsRead() throws Exception {
        seat("architect.md", "name: architect\nschedule: \"0 6 * * *\"");
        seat("adhoc.md", "name: adhoc");
        seat("off.md", "name: off\nschedule: \"* * * * *\"\nenabled: false");
        seat("noname.md", "schedule: \"5 4 * * 1\"");
        Files.writeString(seats.resolve("not-a-seat.txt"), "schedule: \"* * * * *\"");

        ScheduledHires.Reading read = ScheduledHires.read(seats);

        assertAll(
            () -> assertEquals(List.of("architect", "noname"),
                read.seats().stream().map(SeatScheduler.ScheduledSeat::name).sorted().toList(),
                "the seat with a schedule, and a file with no name takes the file's name; ad-hoc and switched-off seats are left out"),
            () -> assertEquals("0 6 * * *", read.seats().stream()
                .filter(s -> s.name().equals("architect")).findFirst().orElseThrow().schedule(),
                "the cron is read without its quotes"),
            () -> assertEquals(seats.resolve("architect.md"), read.files().get("architect")),
            () -> assertTrue(read.unreadable().isEmpty()),
            () -> assertEquals(1, ScheduledHires.read(seats.resolve("no-such-folder")).unreadable().size(),
                "a folder that cannot be listed is reported, not read as 'no seats'"));
    }

    @Test
    @DisplayName("a due seat is hired through the tool door on the scheduler's own thread, with the seat's parent as hirer and the workspace folder as its blast radius")
    void aDueSeatIsHiredThroughTheToolDoor() throws Exception {
        Path file = seat("architect.md", "name: architect\nschedule: \"* * * * *\"");
        seat("adhoc.md", "name: adhoc");
        RecordingDoor door = new RecordingDoor(ToolResponse.success(Map.of("ok", true)));

        Optional<SeatScheduler> started = ScheduledHires.start(seats, work, door, stoppedAt(0), 50);
        try (SeatScheduler scheduler = started.orElseThrow()) {
            assertTrue(door.fired.await(30, TimeUnit.SECONDS), "the schedule never fired a hire");
        }

        JsonNode asked = door.hires.get(0);
        assertAll(
            () -> assertEquals("hire", door.tools.get(0), "through the one tool, which carries the hierarchy, the ceilings and the record"),
            () -> assertEquals("jawata-seat-scheduler", door.threads.get(0), "on the scheduler's thread, so nobody called it"),
            () -> assertEquals("main", asked.path("hirer").asText(), "the architect is hired by main in the signed tree"),
            () -> assertEquals("architect", asked.path("seat").asText()),
            () -> assertEquals(file.toString(), asked.path("seatFile").asText()),
            () -> assertEquals(work.toString(), asked.path("workingDirectory").asText()),
            () -> assertEquals(ScheduledHires.BRIEF, asked.path("brief").asText()),
            () -> assertEquals(1, asked.path("steps").size(), "the hire tool refuses a hire with no gate, so the scheduled one declares exactly one"),
            () -> assertEquals(ScheduledHires.GATE_TOOL, asked.path("steps").path(0).path("tool").asText()),
            () -> assertTrue(asked.path("steps").path(0).path("arguments").path("summary").asBoolean(),
                "and asks it for its counts only"),
            () -> assertEquals(0, door.hires.stream().filter(a -> "adhoc".equals(a.path("seat").asText())).count(),
                "CONTROL: the seat with no schedule was never hired"));
    }

    @Test
    @DisplayName("the hirer is the seat's parent in the signed tree, and main for a seat the tree does not know")
    void theHirerIsTheSeatsParent() throws Exception {
        Path debugger = seat("debugger.md", "name: debugger\nschedule: \"* * * * *\"");
        Path own = seat("project-seat.md", "name: project-seat\nschedule: \"* * * * *\"");
        RecordingDoor door = new RecordingDoor(ToolResponse.success(Map.of()));
        ScheduledHires hires = new ScheduledHires(door, work,
            Map.of("debugger", debugger, "project-seat", own), SeatTree.standard());

        hires.fire(new SeatScheduler.ScheduledSeat("debugger", "* * * * *", "b"));
        hires.fire(new SeatScheduler.ScheduledSeat("project-seat", "* * * * *", "b"));

        assertAll(
            () -> assertEquals("pm", door.hires.get(0).path("hirer").asText(), "the debugger sits below the pm"),
            () -> assertEquals("main", door.hires.get(1).path("hirer").asText(), "a seat outside the tree is hired by main"));
    }

    @Test
    @DisplayName("a seat switched off since the schedule was read does not fire")
    void aSeatSwitchedOffSinceDoesNotFire() throws Exception {
        Path file = seat("architect.md", "name: architect\nschedule: \"* * * * *\"");
        RecordingDoor door = new RecordingDoor(ToolResponse.success(Map.of()));
        ScheduledHires hires = new ScheduledHires(door, work, Map.of("architect", file), SeatTree.standard());
        SeatScheduler.ScheduledSeat due = new SeatScheduler.ScheduledSeat("architect", "* * * * *", "b");

        hires.fire(due);
        assertEquals(1, door.hires.size(), "CONTROL: switched on, it is hired");

        seat("architect.md", "name: architect\nschedule: \"* * * * *\"\nenabled: false");
        hires.fire(due);

        assertEquals(1, door.hires.size(), "the file is the truth at the moment of firing, so the switched-off seat is not hired again");
    }

    @Test
    @DisplayName("a hire the tool refuses is thrown by seat name, so the scheduler reports it and goes on")
    void aRefusedHireIsReportedByName() throws Exception {
        Path file = seat("architect.md", "name: architect\nschedule: \"* * * * *\"");
        RecordingDoor door = new RecordingDoor(ToolResponse.error("PRECONDITION_NOT_MET", "this resident has no journal", "none"));
        ScheduledHires hires = new ScheduledHires(door, work, Map.of("architect", file), SeatTree.standard());

        IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> hires.fire(new SeatScheduler.ScheduledSeat("architect", "* * * * *", "b")));

        assertTrue(refused.getMessage().contains("architect") && refused.getMessage().contains("no journal"),
            "the refusal names the seat and says why: " + refused.getMessage());
    }

    @Test
    @DisplayName("a seat that names schedule_gate is gated by that tool step, with its arguments, and by nothing else")
    void aSeatThatNamesItsGateIsGatedByIt() throws Exception {
        Path file = seat("architect.md",
            "name: architect\nschedule: \"* * * * *\"\nschedule_gate: find_quality_issue {\"kind\":\"naming\",\"summary\":true}");
        RecordingDoor door = new RecordingDoor(ToolResponse.success(Map.of()));
        ScheduledHires hires = new ScheduledHires(door, work, Map.of("architect", file), SeatTree.standard());

        hires.fire(new SeatScheduler.ScheduledSeat("architect", "* * * * *", "b"));

        JsonNode steps = door.hires.get(0).path("steps");
        assertAll(
            () -> assertEquals(1, steps.size(), "the one gate the seat named, not that one plus the default"),
            () -> assertEquals("find_quality_issue", steps.path(0).path("tool").asText()),
            () -> assertEquals("naming", steps.path(0).path("arguments").path("kind").asText(), "with the arguments the seat wrote"),
            () -> assertTrue(steps.path(0).path("arguments").path("summary").asBoolean()));
    }

    @Test
    @DisplayName("a schedule_gate that names only a tool is that tool with no arguments")
    void aGateNamedWithNoArgumentsHasNone() throws Exception {
        Path file = seat("architect.md", "name: architect\nschedule: \"* * * * *\"\nschedule_gate: health_check");
        RecordingDoor door = new RecordingDoor(ToolResponse.success(Map.of()));
        ScheduledHires hires = new ScheduledHires(door, work, Map.of("architect", file), SeatTree.standard());

        hires.fire(new SeatScheduler.ScheduledSeat("architect", "* * * * *", "b"));

        JsonNode steps = door.hires.get(0).path("steps");
        assertAll(
            () -> assertEquals(1, steps.size()),
            () -> assertEquals("health_check", steps.path(0).path("tool").asText()),
            () -> assertTrue(steps.path(0).path("arguments").isObject() && steps.path(0).path("arguments").isEmpty(),
                "an empty argument object, which is what a tool taking none is called with"));
    }

    @Test
    @DisplayName("a seat that names no schedule_gate is gated exactly as before: one compile_workspace step asking for its counts only")
    void aSeatThatNamesNoGateKeepsTheCompileGateUnchanged() throws Exception {
        Path file = seat("architect.md", "name: architect\nschedule: \"* * * * *\"");
        RecordingDoor door = new RecordingDoor(ToolResponse.success(Map.of()));
        ScheduledHires hires = new ScheduledHires(door, work, Map.of("architect", file), SeatTree.standard());

        hires.fire(new SeatScheduler.ScheduledSeat("architect", "* * * * *", "b"));

        assertEquals("[{\"tool\":\"compile_workspace\",\"arguments\":{\"summary\":true}}]", door.hires.get(0).path("steps").toString(),
            "byte for byte the gate every scheduled hire carried before the key existed");
    }

    @Test
    @DisplayName("a malformed schedule_gate is refused with its reason when read, and the seat is not scheduled")
    void aMalformedGateIsRefusedWhenRead() throws Exception {
        seat("empty.md", "name: empty\nschedule: \"* * * * *\"\nschedule_gate:");
        seat("badjson.md", "name: badjson\nschedule: \"* * * * *\"\nschedule_gate: health_check {\"kind\"");
        seat("notobject.md", "name: notobject\nschedule: \"* * * * *\"\nschedule_gate: health_check [1,2]");
        seat("notool.md", "name: notool\nschedule: \"* * * * *\"\nschedule_gate: {\"kind\":\"naming\"}");
        seat("badname.md", "name: badname\nschedule: \"* * * * *\"\nschedule_gate: Not-A-Tool");
        seat("trailing.md", "name: trailing\nschedule: \"* * * * *\"\nschedule_gate: health_check {} extra");
        seat("fine.md", "name: fine\nschedule: \"* * * * *\"\nschedule_gate: health_check");

        ScheduledHires.Reading read = ScheduledHires.read(seats);

        assertAll(
            () -> assertEquals(List.of("fine"), read.seats().stream().map(SeatScheduler.ScheduledSeat::name).toList(),
                "only the seat whose key reads is scheduled; the six malformed ones are not quietly given the default gate"),
            () -> assertEquals(6, read.unreadable().size(), "each malformed key is reported: " + read.unreadable()),
            () -> assertTrue(read.unreadable().stream().allMatch(line -> line.contains("schedule_gate")),
                "and each report names the key: " + read.unreadable()),
            () -> assertTrue(read.unreadable().stream().anyMatch(line -> line.startsWith("badjson.md") && line.contains("arguments")),
                "and the file and the reason: " + read.unreadable()));
    }

    @Test
    @DisplayName("a schedule_gate that became malformed since the schedule was read is refused at the fire and nothing is hired")
    void aGateMalformedSinceReadingIsRefusedAtTheFire() throws Exception {
        Path file = seat("architect.md", "name: architect\nschedule: \"* * * * *\"\nschedule_gate: health_check");
        RecordingDoor door = new RecordingDoor(ToolResponse.success(Map.of()));
        ScheduledHires hires = new ScheduledHires(door, work, Map.of("architect", file), SeatTree.standard());
        SeatScheduler.ScheduledSeat due = new SeatScheduler.ScheduledSeat("architect", "* * * * *", "b");

        hires.fire(due);
        assertEquals(1, door.hires.size(), "CONTROL: a readable key hires");

        seat("architect.md", "name: architect\nschedule: \"* * * * *\"\nschedule_gate: health_check [");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> hires.fire(due));

        assertAll(
            () -> assertTrue(refused.getMessage().contains("schedule_gate"), refused.getMessage()),
            () -> assertEquals(1, door.hires.size(), "no hire was made on a gate nobody could read, and none on the default either"));
    }

    @Test
    @DisplayName("a folder in which no seat declares a schedule starts nothing")
    void nothingIsStartedWhenNoSeatIsScheduled() throws Exception {
        seat("adhoc.md", "name: adhoc");
        RecordingDoor door = new RecordingDoor(ToolResponse.success(Map.of()));

        Optional<SeatScheduler> started = ScheduledHires.start(seats, work, door, stoppedAt(0), 50);

        assertAll(
            () -> assertFalse(started.isPresent(), "no scheduler is running when there is nothing to schedule"),
            () -> assertTrue(door.tools.isEmpty(), "and nothing was hired"));
    }
}
