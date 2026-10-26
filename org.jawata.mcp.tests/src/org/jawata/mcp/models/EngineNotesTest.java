package org.jawata.mcp.models;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sprint 28k, Stage 8 (contract §14.1, consumer side) — reading the notes an Eclipse engine leaves for the machine.
 *
 * <p>The five fixture files under {@code test-resources/engine-notes} are the canonical set (R13): studio (Stage 20) and the plug-in
 * (Stage 16) test against byte-identical copies, which {@code SHA256SUMS} lets them verify. A fixture cannot hold a live process id, so
 * the cases that need one patch a copy of the fixture with this process's id; the shape under test is still the fixture's.</p>
 */
class EngineNotesTest {

    private static final ObjectMapper OM = new ObjectMapper();
    static final String LIVE = "eclipse-1111111111111111.json";
    static final String STALE = "eclipse-2222222222222222.json";
    static final String REUSED = "eclipse-3333333333333333.json";
    static final String MALFORMED = "eclipse-4444444444444444.json";
    static final String NEWER_MAJOR = "eclipse-5555555555555555.json";

    static String fixture(String name) throws IOException {
        try (InputStream in = EngineNotesTest.class.getResourceAsStream("/test-resources/engine-notes/" + name)) {
            assertNotNull(in, "fixture " + name + " is not on the test bundle's classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Copies a fixture into {@code dir}, changing fields of its top level first when a case needs it. */
    static void place(Path dir, String name, Consumer<ObjectNode> patch) throws Exception {
        ObjectNode note = (ObjectNode) OM.readTree(fixture(name));
        patch.accept(note);
        Files.writeString(dir.resolve(name), OM.writeValueAsString(note));
    }

    static void placeAsIs(Path dir, String name) throws IOException {
        Files.writeString(dir.resolve(name), fixture(name));
    }

    /** The fixture's note, but with the process id and start time of the JVM running this test. */
    static void placeAsThisProcess(Path dir, String name) throws Exception {
        place(dir, name, note -> {
            note.put("pid", ProcessHandle.current().pid());
            ProcessHandle.current().info().startInstant().ifPresent(started -> note.put("processStartedAt", started.toString()));
        });
    }

    @Test
    @DisplayName("a note whose process is alive and started when the note says is LIVE, and names where to ask")
    void aLiveNoteIsLiveAndAddressable(@TempDir Path dir) throws Exception {
        placeAsThisProcess(dir, LIVE);

        List<EngineNotes.Reading> readings = EngineNotes.read(dir, EngineNotes.OS);

        assertEquals(1, readings.size(), "got: " + readings);
        EngineNotes.Reading live = readings.get(0);
        assertAll(
            () -> assertEquals(EngineNotes.Status.LIVE, live.status(), "got: " + live),
            () -> assertEquals("live", live.workspaceName()),
            // The key is absent by contract (D-2: no password protection), so the sibling carries none.
            () -> assertEquals(new SiblingRegistry.Sibling("live", 43123, ""), live.sibling().orElseThrow()),
            () -> assertEquals(ProcessHandle.current().pid(), live.pid()));
    }

    @Test
    @DisplayName("a note whose process is gone is STALE and never joins the siblings")
    void aDeadPidIsStale(@TempDir Path dir) throws Exception {
        placeAsIs(dir, STALE);

        EngineNotes.Reading stale = EngineNotes.read(dir, EngineNotes.OS).get(0);

        assertAll(
            () -> assertEquals(EngineNotes.Status.STALE, stale.status(), "got: " + stale),
            () -> assertTrue(stale.sibling().isEmpty(), "a stale note must not be addressable: " + stale),
            // A stale note is shown as 'not running', so the workspace it names is still known.
            () -> assertEquals("stale", stale.workspaceName()));
    }

    @Test
    @DisplayName("a REUSED pid is stale: the process is alive but started at another time than the note says")
    void aReusedPidIsStale(@TempDir Path dir) throws Exception {
        assumeTrue(ProcessHandle.current().info().startInstant().isPresent(), "this OS does not report a process start time");
        place(dir, REUSED, note -> note.put("pid", ProcessHandle.current().pid()));

        EngineNotes.Reading reused = EngineNotes.read(dir, EngineNotes.OS).get(0);

        assertAll(
            () -> assertEquals(EngineNotes.Status.STALE, reused.status(), "got: " + reused),
            () -> assertTrue(reused.sibling().isEmpty()),
            () -> assertTrue(reused.detail().contains("start"), "the reading says why: " + reused.detail()));
    }

    @Test
    @DisplayName("THE CONTROL — the same reused-pid note with the right start time is live, so the check above can fail")
    void theSameNoteWithTheRightStartTimeIsLive(@TempDir Path dir) throws Exception {
        assumeTrue(ProcessHandle.current().info().startInstant().isPresent(), "this OS does not report a process start time");
        placeAsThisProcess(dir, REUSED);

        assertEquals(EngineNotes.Status.LIVE, EngineNotes.read(dir, EngineNotes.OS).get(0).status());
    }

    @Test
    @DisplayName("a malformed note is reported as malformed; it neither throws nor hides the notes beside it")
    void aMalformedNoteDoesNotHideTheOthers(@TempDir Path dir) throws Exception {
        placeAsIs(dir, MALFORMED);
        placeAsThisProcess(dir, LIVE);

        List<EngineNotes.Reading> readings = EngineNotes.read(dir, EngineNotes.OS);

        assertEquals(2, readings.size(), "got: " + readings);
        assertAll(
            () -> assertEquals(EngineNotes.Status.MALFORMED,
                readings.stream().filter(r -> r.fileName().equals(MALFORMED)).findFirst().orElseThrow().status()),
            () -> assertEquals(EngineNotes.Status.LIVE,
                readings.stream().filter(r -> r.fileName().equals(LIVE)).findFirst().orElseThrow().status()));
    }

    @Test
    @DisplayName("a note of a NEWER major is reported as such, with no projects and no address: never guessed")
    void aNewerMajorIsReportedNotGuessed(@TempDir Path dir) throws Exception {
        placeAsIs(dir, NEWER_MAJOR);

        EngineNotes.Reading newer = EngineNotes.read(dir, (pid, startedAt) -> true).get(0);

        assertAll(
            // 'Alive' was forced true above: even a live process behind a note this reader does not understand is not addressed.
            () -> assertEquals(EngineNotes.Status.NEWER_MAJOR, newer.status(), "got: " + newer),
            () -> assertTrue(newer.sibling().isEmpty(), "an endpoint of a schema we do not know is not ours to use: " + newer),
            () -> assertEquals("newer", newer.workspaceName()),
            () -> assertTrue(newer.detail().contains("newer jawata"), "says so in the words studio shows: " + newer.detail()));
    }

    @Test
    @DisplayName("a note with no usable endpoint is malformed, because a live engine we cannot address is of no use as a sibling")
    void aNoteWithoutAnEndpointIsMalformed(@TempDir Path dir) throws Exception {
        place(dir, LIVE, note -> note.remove("endpoint"));

        EngineNotes.Reading reading = EngineNotes.read(dir, (pid, startedAt) -> true).get(0);

        assertEquals(EngineNotes.Status.MALFORMED, reading.status(), "got: " + reading);
    }

    @Test
    @DisplayName("an absent folder is no notes: the ordinary machine without the Eclipse plug-in")
    void anAbsentFolderIsEmpty(@TempDir Path dir) {
        assertAll(
            () -> assertEquals(List.of(), EngineNotes.read(dir.resolve("engines"), EngineNotes.OS)),
            () -> assertEquals(List.of(), EngineNotes.read(null, EngineNotes.OS)));
    }

    @Test
    @DisplayName("only .json files are notes: the producer's half-written temp file and stray files are not read")
    void onlyJsonFilesAreRead(@TempDir Path dir) throws Exception {
        placeAsThisProcess(dir, LIVE);
        Files.writeString(dir.resolve("eclipse-1111111111111111.json.tmp"), "{ half written");
        Files.writeString(dir.resolve("readme.txt"), "not a note");

        assertEquals(1, EngineNotes.read(dir, EngineNotes.OS).size());
    }

    @Test
    @DisplayName("the five fixtures are the canonical bytes: they match SHA256SUMS, which studio and the plug-in verify theirs against")
    void theFixturesMatchTheirDigests() throws Exception {
        List<String> sums = fixture("SHA256SUMS").lines().filter(l -> !l.isBlank()).toList();
        assertEquals(5, sums.size(), "one line per fixture: " + sums);
        for (String line : sums) {
            String[] parts = line.split("\\s+", 2);
            byte[] bytes = fixture(parts[1].trim()).getBytes(StandardCharsets.UTF_8);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertEquals(parts[0], digest, parts[1] + " changed: studio and the plug-in hold copies of the old bytes");
        }
    }

    @Test
    @DisplayName("the start time the note carries is compared as an instant, not as text")
    void startTimeIsComparedAsAnInstant() {
        // The producer truncates to seconds and the OS reports finer; one second of slack is the contract's 'where the OS reports it'.
        Instant started = Instant.parse("2026-10-22T09:00:00Z");
        assertAll(
            () -> assertTrue(EngineNotes.sameStart(started, Instant.parse("2026-10-22T09:00:00.700Z"))),
            () -> assertTrue(EngineNotes.sameStart(started, Instant.parse("2026-10-22T08:59:59.500Z"))),
            () -> assertFalse(EngineNotes.sameStart(started, Instant.parse("2026-10-22T09:00:05Z"))),
            () -> assertFalse(EngineNotes.sameStart(started, Instant.parse("2001-01-01T00:00:00Z"))));
    }

    @Test
    @DisplayName("the default folder is jawata-studio/engines in the OS cache folder, whatever studio's data root is set to")
    void theDefaultFolderIsStudiosDefault() {
        Path dir = EngineNotes.defaultDir();
        assertAll(
            () -> assertEquals("engines", dir.getFileName().toString()),
            () -> assertEquals("jawata-studio", dir.getParent().getFileName().toString()));
    }
}
