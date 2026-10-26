package org.jawata.mcp.models;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28k, Stage 8 (contract §14.1, consumer) — <b>the notes an engine running inside an IDE leaves for the machine.</b>
 *
 * <p>An Eclipse engine writes one small file per workspace into {@code <OS cache folder>/jawata-studio/engines/} when it is ready and
 * deletes it at a clean stop. {@link SiblingRegistry} reads them beside {@code residents.json}, so a resident or an outside agent can
 * follow a dependency into a running Eclipse. This class decides what each file is worth: it never connects to anything.</p>
 *
 * <h2>Four outcomes, and why they are four</h2>
 * <ul>
 *   <li>{@link Status#LIVE}: the process that wrote the note is alive and started when the note says. It is addressable.</li>
 *   <li>{@link Status#STALE}: the engine crashed or was killed. Its note stays (only the producer deletes) and must never be connected to,
 *       because the port may now belong to something else.</li>
 *   <li>{@link Status#NEWER_MAJOR}: written by a newer jawata. Reported by name and never guessed at, which is the contract's rule for
 *       a major this reader does not know.</li>
 *   <li>{@link Status#MALFORMED}: unreadable, or lacking what makes it usable (an address). Reported, and the notes beside it are
 *       still read.</li>
 * </ul>
 */
public final class EngineNotes {

    private static final Logger log = LoggerFactory.getLogger(EngineNotes.class);
    private static final ObjectMapper OM = new ObjectMapper();

    /** The prefix of the {@code schema} field: {@code jawata.engine-note/<major>}. */
    static final String SCHEMA_PREFIX = "jawata.engine-note/";

    /** The highest major of the schema this reader understands. */
    static final int SUPPORTED_MAJOR = 1;

    /** The producer writes the start time to the second and the OS reports finer, so a second is the same moment. */
    private static final Duration START_SLACK = Duration.ofSeconds(1);

    /** What a note is worth. */
    public enum Status { LIVE, STALE, NEWER_MAJOR, MALFORMED }

    /**
     * One note, read.
     *
     * @param fileName      the note's file name, which is how a reader finds it again
     * @param status        what the note is worth
     * @param workspaceName the workspace the note names, or the file name when the note is too damaged to say
     * @param pid           the process id the note carries, 0 when it carries none
     * @param sibling       where to ask, present only for a {@link Status#LIVE} note; its key is empty because the contract gives none
     * @param detail        one sentence saying why the note has the status it has
     */
    public record Reading(String fileName, Status status, String workspaceName, long pid, Optional<SiblingRegistry.Sibling> sibling,
                          String detail) {}

    /** Whether the process that wrote a note is still the one running. */
    @FunctionalInterface
    public interface Liveness {

        /**
         * @param pid       the process id from the note
         * @param startedAt the start time from the note, or null when the note carries none
         * @return true when that process is alive and, where the OS reports a start time, started when the note says
         */
        boolean alive(long pid, Instant startedAt);
    }

    /** The real check: the OS is asked whether the process exists and when it started. */
    public static final Liveness OS = EngineNotes::processMatches;

    private EngineNotes() {}

    /**
     * Where the Eclipse engine writes its notes: studio's default data folder, independent of the data root studio's settings name.
     *
     * @return {@code jawata-studio/engines} in the OS cache folder
     */
    public static Path defaultDir() {
        Path home = Path.of(System.getProperty("user.home"));
        Path cache;
        switch (org.jawata.core.host.HostOS.current()) {
            case WINDOWS -> {
                String local = System.getenv("LOCALAPPDATA");
                cache = local == null || local.isBlank() ? home.resolve("AppData").resolve("Local") : Path.of(local);
            }
            case MACOS -> cache = home.resolve("Library").resolve("Caches");
            default -> {
                String xdg = System.getenv("XDG_CACHE_HOME");
                cache = xdg == null || xdg.isBlank() ? home.resolve(".cache") : Path.of(xdg);
            }
        }
        return cache.resolve("jawata-studio").resolve("engines");
    }

    /**
     * Every note in the folder, in file-name order. A folder that is absent, or is not there at all, holds no notes: the ordinary machine
     * without the plug-in.
     *
     * @param dir      the folder of notes, or null
     * @param liveness decides whether the process behind a note still runs
     * @return one reading per {@code .json} file; the half-written temporary files of the producer's atomic write are not notes
     */
    public static List<Reading> read(Path dir, Liveness liveness) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files = listing.filter(p -> p.getFileName().toString().endsWith(".json")).filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            log.warn("The engine notes folder {} could not be listed ({}); answering as if it holds none.", dir, e.toString());
            return List.of();
        }
        List<Reading> readings = new ArrayList<>();
        for (Path file : files) {
            readings.add(readOne(file, liveness));
        }
        return List.copyOf(readings);
    }

    private static Reading readOne(Path file, Liveness liveness) {
        String fileName = file.getFileName().toString();
        JsonNode note;
        try {
            note = OM.readTree(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            return malformed(fileName, fileName, 0, "the file is not readable JSON (" + e.getClass().getSimpleName() + ")");
        }
        if (note == null || !note.isObject()) {
            return malformed(fileName, fileName, 0, "the file holds no JSON object");
        }
        String named = note.path("workspaceName").asText("");
        String workspaceName = named.isBlank() ? fileName : named;
        long pid = note.path("pid").asLong(0);

        String schema = note.path("schema").asText("");
        int major = majorOf(schema);
        if (major < 1) {
            return malformed(fileName, workspaceName, pid, "the schema field '" + schema + "' is not " + SCHEMA_PREFIX + "<major>");
        }
        if (major > SUPPORTED_MAJOR) {
            return new Reading(fileName, Status.NEWER_MAJOR, workspaceName, pid, Optional.empty(),
                "Eclipse workspace (newer jawata): the note is schema major " + major + " and this reader understands "
                    + SUPPORTED_MAJOR + "; it is not read further");
        }
        if (pid <= 0) {
            return malformed(fileName, workspaceName, pid, "the note carries no process id");
        }
        if (named.isBlank()) {
            return malformed(fileName, workspaceName, pid, "the note names no workspace");
        }
        int port = portOf(note.path("endpoint").asText(""));
        if (port <= 0) {
            return malformed(fileName, workspaceName, pid, "the note carries no endpoint to ask at");
        }
        Instant startedAt = instantOf(note.path("processStartedAt").asText(""));
        if (!liveness.alive(pid, startedAt)) {
            return new Reading(fileName, Status.STALE, workspaceName, pid, Optional.empty(),
                "process " + pid + " is gone, or is not the process that wrote the note (a pid reused after a crash has another start time)");
        }
        return new Reading(fileName, Status.LIVE, workspaceName, pid,
            Optional.of(new SiblingRegistry.Sibling(workspaceName, port, "")), "process " + pid + " is alive and started when the note says");
    }

    private static Reading malformed(String fileName, String workspaceName, long pid, String why) {
        return new Reading(fileName, Status.MALFORMED, workspaceName, pid, Optional.empty(), why);
    }

    /** The major of a schema field, or 0 when the field is not {@code jawata.engine-note/<major>}. */
    private static int majorOf(String schema) {
        if (!schema.startsWith(SCHEMA_PREFIX)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(schema.substring(SCHEMA_PREFIX.length()).trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int portOf(String endpoint) {
        try {
            return endpoint.isBlank() ? 0 : URI.create(endpoint).getPort();
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    private static Instant instantOf(String text) {
        try {
            return text.isBlank() ? null : Instant.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Whether two start times are the same moment: the note's is truncated to the second and the OS reports finer.
     *
     * @param noted  the start time the note carries
     * @param actual the start time the OS reports
     * @return true when they differ by no more than a second
     */
    static boolean sameStart(Instant noted, Instant actual) {
        return Duration.between(noted, actual).abs().compareTo(START_SLACK) <= 0;
    }

    private static boolean processMatches(long pid, Instant startedAt) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return false;
        }
        if (startedAt == null) {
            return true;
        }
        // Where the OS does not report a start time, the pid alone has to do: the contract says 'where the OS reports it'.
        Optional<Instant> actual = handle.get().info().startInstant();
        return actual.isEmpty() || sameStart(startedAt, actual.get());
    }
}
