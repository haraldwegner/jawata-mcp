package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jawata.mcp.engine.SeatSettings;
import org.jawata.mcp.engine.SettingKeys;
import org.jawata.spi.host.EngineSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sprint 28k, Amendment 3, Stage 25d: the seats a host's screen is shown and the check of a change, over fixture seats. */
class SeatSettingsServiceTest {

    private static final String ARCHITECT = "---\nname: architect\ncommand: refactor\npurpose: Reviews the design.\nbrain: claude-code\n---\nYou are the architect.\n";
    private static final String DEVELOPER = "---\nname: developer\npurpose: Writes the code.\n---\nYou are the developer.\n";

    @TempDir
    Path folder;

    private final Map<String, String> stored = new HashMap<>();
    private final EngineSettings settings = key -> Optional.ofNullable(stored.get(key));

    @BeforeEach
    void seatFiles() throws IOException {
        Files.writeString(folder.resolve("architect.md"), ARCHITECT);
        Files.writeString(folder.resolve("developer.md"), DEVELOPER);
    }

    private SeatSettings door() {
        return SeatSettingsService.overFolder(folder, settings, Set.of("rename_symbol"));
    }

    private SeatSettings.Seat seat(String name) {
        return door().seats().seats().stream().filter(row -> row.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void eachSeatIsListedWithWhatItsFileAndTheSignedTreeSay() {
        SeatSettings.Seat architect = seat("architect");
        assertEquals(Optional.of("refactor"), architect.command());
        assertEquals("Reviews the design.", architect.purpose());
        assertTrue(architect.on());
        assertEquals("claude-code", architect.brain());
        assertEquals(Optional.of("main"), architect.parent());
        assertEquals("reviewer", architect.role());
        assertEquals(Optional.of("senior-developer"), seat("developer").escalatesTo());
        assertEquals(List.of(), door().seats().errors());
    }

    @Test
    void aSeatSwitchedOffIsReportedOffAndTheOtherStaysOn() {
        stored.put(SettingKeys.SEATS_OFF.name(), "architect");
        assertFalse(seat("architect").on());
        assertTrue(seat("developer").on());
    }

    @Test
    void aSeatsFileThatSwitchesItselfOffIsReportedOff() throws IOException {
        Files.writeString(folder.resolve("developer.md"), "---\nname: developer\nenabled: false\n---\nbody\n");
        assertFalse(seat("developer").on());
    }

    @Test
    void aBrainFromTheSettingsIsReportedInsteadOfTheFiles() {
        stored.put(SettingKeys.SEATS_BRAINS.name(), "architect=cursor/gpt-x");
        assertEquals("cursor", seat("architect").brain());
        assertEquals(Optional.of("gpt-x"), seat("architect").model());
        assertEquals("claude-code", seat("developer").brain());
    }

    @Test
    void aPlaceInTheWorkflowFromTheSettingsIsReported() {
        stored.put(SettingKeys.SEATS_WORKFLOW.name(), "architect=pm,reviewer");
        assertEquals(Optional.of("pm"), seat("architect").parent());
    }

    @Test
    void aBadTreeIsRefusedWithTheEnginesOwnText() {
        Optional<String> refusal = door().check(Map.of(SettingKeys.SEATS_WORKFLOW.name(), "architect=nobody,reviewer"));
        assertEquals(Optional.of("the engine would refuse this tree: architect: its parent 'nobody' is no seat"), refusal);
    }

    @Test
    void aGoodChangeIsAccepted() {
        assertEquals(Optional.empty(), door().check(Map.of(SettingKeys.SEATS_OFF.name(), "architect", SettingKeys.SEATS_BRAINS.name(), "developer=codex",
            SettingKeys.SEATS_WORKFLOW.name(), "architect=pm,reviewer")));
    }

    @Test
    void aSeatThatDoesNotExistIsRefused() {
        assertEquals(Optional.of("no seat named 'nobody'"), door().check(Map.of(SettingKeys.SEATS_OFF.name(), "nobody")));
    }

    @Test
    void aBrainNoHireCanStartIsRefused() {
        Optional<String> refusal = door().check(Map.of(SettingKeys.SEATS_BRAINS.name(), "architect=eliza"));
        assertEquals(Optional.of("'eliza' is not a brain a hire can start (known: claude-code, cursor, copilot, codex)"), refusal);
        assertTrue(door().check(Map.of(SettingKeys.SEATS_BRAINS.name(), "architect=script")).isPresent(), "a script brain needs a line only a seat file can give");
    }

    @Test
    void anEmptyModelIsRefused() {
        assertEquals(Optional.of("an empty model name is not a model; leave it out for the default"),
            door().check(Map.of(SettingKeys.SEATS_BRAINS.name(), "architect=cursor/ ")));
    }

    @Test
    void aStoredTreeTheEngineRefusesIsNamedAndTheSignedOneIsShown() {
        stored.put(SettingKeys.SEATS_WORKFLOW.name(), "architect=nobody,reviewer");
        SeatSettings.View view = door().seats();
        assertEquals(List.of("architect: its parent 'nobody' is no seat"), view.errors());
        assertEquals(Optional.of("main"), seat("architect").parent(), "the engine uses the signed tree, and the screen says what the engine uses");
    }

    @Test
    void aCheckLooksAtTheProposalAndLeavesTheStoredValuesForKeysItDoesNotName() {
        stored.put(SettingKeys.SEATS_OFF.name(), "nobody");
        assertEquals(Optional.of("no seat named 'nobody'"), door().check(Map.of()), "a key left out keeps its stored value");
        assertEquals(Optional.empty(), door().check(Map.of(SettingKeys.SEATS_OFF.name(), "")), "a key given replaces it");
    }

    @Test
    void theDoorWritesNoFile() throws IOException {
        byte[] before = Files.readAllBytes(folder.resolve("architect.md"));
        door().seats();
        door().check(Map.of(SettingKeys.SEATS_OFF.name(), "architect", SettingKeys.SEATS_WORKFLOW.name(), "architect=pm,reviewer"));
        assertEquals(new String(before), Files.readString(folder.resolve("architect.md")));
        try (Stream<Path> files = Files.list(folder)) {
            assertEquals(2, files.count(), "no settings file beside the seats");
        }
    }

    @Test
    void sealedSeatsAreReadFromInsideTheJarAndNotExtracted(@TempDir Path sealedHome) throws IOException {
        Path jar = sealedHome.resolve("engine.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("seats/architect.md"));
            zip.write(ARCHITECT.getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("org/jawata/Other.class"));
            zip.closeEntry();
        }
        SeatSettings.View view = SeatSettingsService.sealedIn(jar, settings, Set.of()).seats();
        assertEquals(List.of("architect"), view.seats().stream().map(SeatSettings.Seat::name).toList());
        assertEquals("Reviews the design.", view.seats().get(0).purpose());
        try (Stream<Path> files = Files.walk(sealedHome)) {
            assertEquals(List.of(sealedHome, jar), files.sorted().toList(), "nothing was extracted beside the jar");
        }
    }

    @Test
    void aJarWithoutSeatsIsNamedAsNotReadable(@TempDir Path sealedHome) throws IOException {
        Path jar = sealedHome.resolve("empty.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("other.txt"));
            zip.closeEntry();
        }
        SeatSettings.View view = SeatSettingsService.sealedIn(jar, settings, Set.of()).seats();
        assertEquals(List.of(), view.seats());
        assertEquals(1, view.errors().size(), "not an empty answer that reads as no seats at all: " + view.errors());
    }
}
