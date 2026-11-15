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

import org.jawata.mcp.engine.SeatSettings;
import org.jawata.mcp.engine.SettingKeys;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.spi.host.EngineSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

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

    private static final ToolCalls NO_TOOLS = new ToolCalls() {
        @Override
        public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
            throw new AssertionError("the door calls no tool");
        }

        @Override
        public List<Map<String, Object>> getToolDefinitions() {
            return List.of();
        }
    };

    @Test
    void anEngineWithASeatsFolderReadsThatFolder() {
        stored.put(ScheduledHires.SEATS_SETTING, folder.toString());
        List<String> names = SeatSettingsService.forEngine(settings, NO_TOOLS).seats().seats().stream().map(SeatSettings.Seat::name).toList();
        assertEquals(List.of("architect", "developer"), names);
    }

    @Test
    void anEngineWithoutASeatsFolderReadsTheSeatsSealedInItsBundle() {
        // the seats sealed in the engine bundle (org.jawata.mcp/resources/seats), read from inside it: an engine in Eclipse has no folder
        SeatSettings.View view = SeatSettingsService.forEngine(settings, NO_TOOLS).seats();
        assertEquals(List.of("architect", "btw", "cataloguer", "chief-engineer", "debugger", "developer", "javadoc-writer", "main", "observer", "pm",
            "profiler", "report", "review", "rottenmeier", "senior-developer", "spec-auditor", "spec-editor", "test-manager", "test-writer", "ux"),
            view.seats().stream().map(SeatSettings.Seat::name).toList());
        assertEquals(List.of(), view.errors(), "every sealed seat is readable and the sealed tree has no refusal");
    }
}
