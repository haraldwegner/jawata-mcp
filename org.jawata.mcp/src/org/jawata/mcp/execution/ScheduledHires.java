package org.jawata.mcp.execution;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What a seat's schedule does when it is due: the seat is hired through the engine's one tool door, exactly as a lead would hire it
 * (Sprint 28h, S12, D29 category C).
 *
 * <p>{@link SeatScheduler} decides WHEN and had no caller in production, so the schedule the studio's runner kept in Rust ran nowhere on
 * the new start path. This is the caller: it reads the seat files of one folder, hands the ones that declare a {@code schedule:} to a
 * scheduler, and fires each as a call of the {@code hire} tool. There is no second way to start a hire here. The hierarchy check, the
 * vendor rule, the seat's own {@code enabled:} switch, the ceilings, the gates, the journal row and the proposal record are all the hire
 * tool's, so a scheduled run cannot be hired on terms an ordinary one would be refused.</p>
 *
 * <h2>Three decisions, said once</h2>
 *
 * <ul>
 *   <li><b>The hirer is the seat's parent in the signed tree</b>, and {@code main} for a seat the tree does not know (a project's own).
 *       The hierarchy refuses a hire by anyone else, and a schedule is the standing instruction of whoever hires that seat.</li>
 *   <li><b>The working directory is the workspace's own folder</b>, the one place the engine is given to work in. It is never the engine's
 *       launch directory, which the hire tool refuses to inherit.</li>
 *   <li><b>The list of scheduled seats is read when the scheduler starts; each seat's file is read again when it fires.</b> A seat the person
 *       switched off since ({@code enabled: false}) does not fire, and a changed brain is the new one. A schedule newly written into
 *       a file, or a new seat file, is seen at the next start of the engine, and that is the edge, not an oversight.</li>
 * </ul>
 */
public final class ScheduledHires implements SeatScheduler.Firing {

    private static final Logger log = Logger.getLogger(ScheduledHires.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The setting that names the folder of seat files whose schedules the engine fires. The studio does NOT write it yet: that is owed to S15
     * (the plan's Stage 15 carries it), and until then only a person or a test that sets it makes seats run on a schedule.
     */
    public static final String SEATS_SETTING = "jawata.agent.seatsDir";

    /** The tool a scheduled hire is gated by, because the hire tool refuses a hire with no gate. */
    static final String GATE_TOOL = "compile_workspace";

    /** The brief a due seat is asked: its own rules are sent ahead of it, so the brief only says that this is the scheduled run. */
    static final String BRIEF = "This is your scheduled run. Do your standing duty as your rules describe it, within your ceilings,"
        + " and answer with what you did and what you found.";

    /**
     * What reading a folder of seat files found.
     *
     * @param seats      the seats that declare a schedule, which are the ones the scheduler is given
     * @param files      each of those seats' own file, by the seat's name
     * @param unreadable a line for each seat file that could not be read, so an unreadable file is reported and never skipped quietly
     */
    public record Reading(List<SeatScheduler.ScheduledSeat> seats, Map<String, Path> files, List<String> unreadable) {

        public Reading {
            seats = List.copyOf(seats);
            files = Map.copyOf(files);
            unreadable = List.copyOf(unreadable);
        }
    }

    private final ToolCalls tools;
    private final Path workingDirectory;
    private final Map<String, Path> files;
    private final SeatTree tree;

    ScheduledHires(ToolCalls tools, Path workingDirectory, Map<String, Path> files, SeatTree tree) {
        this.tools = Objects.requireNonNull(tools, "a scheduled hire has to be made through the tool door");
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "a hire's blast radius is stated, never inherited");
        this.files = Map.copyOf(files);
        this.tree = Objects.requireNonNull(tree, "who may hire the seat is read from the tree");
    }

    /**
     * Read the seat files of a folder: every {@code *.md} that declares a {@code schedule:} and is not switched off.
     *
     * @param seatsDirectory the folder holding the seat files
     */
    public static Reading read(Path seatsDirectory) {
        List<SeatScheduler.ScheduledSeat> seats = new ArrayList<>();
        Map<String, Path> files = new LinkedHashMap<>();
        SeatFiles.Listing listing = SeatFiles.read(seatsDirectory);
        List<String> unreadable = new ArrayList<>(listing.unreadable());
        for (SeatFiles.SeatFile seat : listing.seats()) {
            try {
                String schedule = Frontmatter.emptyToNull(seat.front().get("schedule"));
                if (schedule == null || !Frontmatter.readSwitch("enabled", seat.front().get("enabled"), true)) {
                    continue;
                }
                seats.add(new SeatScheduler.ScheduledSeat(seat.name(), schedule, BRIEF));
                files.put(seat.name(), seat.file());
            } catch (RuntimeException e) {
                unreadable.add(seat.file().getFileName() + ": " + e.getMessage());
            }
        }
        return new Reading(seats, files, unreadable);
    }

    /**
     * Start the scheduler over a folder of seat files, firing through the engine's tool door.
     *
     * @return the running scheduler, or empty when no seat in the folder declares a schedule (said in the log, never silent)
     */
    public static Optional<SeatScheduler> start(Path seatsDirectory, Path workingDirectory, ToolCalls tools) {
        return start(seatsDirectory, workingDirectory, tools, Clock.systemDefaultZone(), 5_000);
    }

    /** The test door: an injected clock and tick, so a due seat can be observed without waiting for a real minute. */
    static Optional<SeatScheduler> start(Path seatsDirectory, Path workingDirectory, ToolCalls tools, Clock clock, long tickMillis) {
        Reading read = read(seatsDirectory);
        for (String line : read.unreadable()) {
            log.warning(() -> "a seat file could not be read, so its schedule is NOT running: " + line);
        }
        if (read.seats().isEmpty()) {
            log.info(() -> "no seat in " + seatsDirectory + " declares a schedule, so nothing is scheduled");
            return Optional.empty();
        }
        ScheduledHires firing = new ScheduledHires(tools, workingDirectory, read.files(), SeatTree.standard());
        SeatScheduler scheduler = new SeatScheduler(read.seats(), firing, clock, tickMillis);
        scheduler.start();
        return Optional.of(scheduler);
    }

    @Override
    public void fire(SeatScheduler.ScheduledSeat seat) throws Exception {
        Path file = files.get(seat.name());
        if (file == null) {
            throw new IllegalStateException("seat '" + seat.name() + "' is not one this scheduler read");
        }
        // The file is the truth at the moment of firing: a seat the person has since switched off does not run.
        Map<String, String> front = Frontmatter.frontmatter(Files.readString(file));
        if (!Frontmatter.readSwitch("enabled", front.get("enabled"), true)) {
            log.info(() -> "seat '" + seat.name() + "' is switched off, so its scheduled run is skipped");
            return;
        }
        ObjectNode arguments = JSON.createObjectNode();
        arguments.put("hirer", tree.parentOf(seat.name()).orElse("main"));
        arguments.put("seat", seat.name());
        arguments.put("seatFile", file.toString());
        arguments.put("brief", seat.brief());
        // THE HIRE TOOL REFUSES A HIRE WITH NO GATE, and a scheduled run has no lead to declare one, so it carries the one gate that holds for
        // any seat: the workspace still compiles after the run. A per-seat gate is a decision for the person, recorded in the plan.
        ObjectNode gate = arguments.putArray("steps").addObject();
        gate.put("tool", GATE_TOOL);
        gate.putObject("arguments").put("summary", true);
        arguments.put("workingDirectory", workingDirectory.toString());
        ToolResponse answer = tools.callTool("hire", arguments);
        if (!answer.isSuccess()) {
            // Thrown so the scheduler logs it by seat name and goes on to the other seats, as it does for every failed fire.
            throw new IllegalStateException("the hire of '" + seat.name() + "' was refused: " + answer.getError());
        }
        log.info(() -> "seat '" + seat.name() + "' fired on its schedule and was hired");
    }
}
