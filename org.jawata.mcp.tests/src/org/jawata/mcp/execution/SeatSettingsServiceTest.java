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

    /** A registry naming one brain, "fake", started as the stand-in ACP agent that offers these models ({@code value=Name,...}). */
    private String fakeAgent(Path dir, String models) throws IOException {
        try (java.io.InputStream in = SeatSettingsServiceTest.class.getResourceAsStream("/test-resources/acp/FakeAcpAgent.java.txt")) {
            Files.write(dir.resolve("FakeAcpAgent.java"), in.readAllBytes());
        }
        com.fasterxml.jackson.databind.node.ObjectNode registry = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode fake = registry.putObject("fake");
        fake.putArray("command").add(Path.of(System.getProperty("java.home"), "bin", "java").toString()).add(dir.resolve("FakeAcpAgent.java").toString());
        fake.putObject("env").put("FAKE_ACP_STATE", dir.toString()).put("FAKE_ACP_MODELS", models);
        return registry.toString();
    }

    /** Stage 25m: the models a conversation brain offers come from its own session, opened once; the answer is kept, so asking again starts nothing. */
    @Test
    void aConversationBrainsModelsAreReadFromItsOwnSessionOnceAndKept(@TempDir Path agentDir) throws IOException {
        stored.put("jawata.agent.hireBrains", fakeAgent(agentDir, "default=Default,opus=Opus " + agentDir.getFileName() + ",sonnet=Sonnet"));

        assertEquals(List.of("Opus " + agentDir.getFileName(), "Sonnet"), door().conversationModels("fake"),
            "the names the session's model option offers, less the brain's own default, which a screen offers first");
        Files.delete(agentDir.resolve("FakeAcpAgent.java"));
        assertEquals(List.of("Opus " + agentDir.getFileName(), "Sonnet"), door().conversationModels("fake"),
            "asked again, the kept answer comes back: the agent's program is gone, so nothing could have been started");
    }

    /**
     * Stage 25m, one catalogue: the models a session of a brain offered as it opened, the way the chat opens its own, are what the Seats page is
     * answered; it starts nothing of its own, as the brain's program is gone when it asks.
     */
    @Test
    void theModelsAChatSessionOfferedAreTheOnesTheSeatsPageReads(@TempDir Path agentDir) throws IOException {
        String registry = fakeAgent(agentDir, "default=Default,haiku=Haiku " + agentDir.getFileName());
        stored.put("jawata.agent.hireBrains", registry);
        AcpBrainSession.Program program = AcpBrainSession.programFor(registry, "fake").orElseThrow();
        AcpBrainSession.Conversation chat = AcpBrainSession.Conversation.start(org.jawata.core.host.HostProcesses.system(), program, HireSite.in(agentDir),
            null, List.of(), Optional.empty(), HiredBrain.Ceilings.of(java.time.Duration.ofSeconds(60)), AcpBrainSession.OwnTools.KEPT);
        chat.close();
        Files.delete(agentDir.resolve("FakeAcpAgent.java"));

        assertEquals(List.of("Haiku " + agentDir.getFileName()), door().conversationModels("fake"),
            "the chat's session's models, less the brain's own default, with no program started for the page");
    }

    /**
     * C25m audit, B6: a resumed session names no models, so it leaves the catalogue as a new session of the brain wrote it; otherwise the chat
     * resuming a conversation would empty the Seats page's list for that brain.
     */
    @Test
    void aResumedSessionLeavesTheModelsTheBrainOffered(@TempDir Path agentDir) throws IOException {
        String registry = fakeAgent(agentDir, "default=Default,sonnet=Sonnet " + agentDir.getFileName());
        stored.put("jawata.agent.hireBrains", registry);
        AcpBrainSession.Program program = AcpBrainSession.programFor(registry, "fake").orElseThrow();
        HiredBrain.Ceilings ceilings = HiredBrain.Ceilings.of(java.time.Duration.ofSeconds(60));
        AcpBrainSession.Conversation.start(org.jawata.core.host.HostProcesses.system(), program, HireSite.in(agentDir), null, List.of(), Optional.empty(),
            ceilings, AcpBrainSession.OwnTools.KEPT).close();
        AcpBrainSession.Conversation resumed = AcpBrainSession.Conversation.start(org.jawata.core.host.HostProcesses.system(), program, HireSite.in(agentDir),
            null, List.of(), Optional.of("an-earlier-session"), ceilings, AcpBrainSession.OwnTools.KEPT);
        assertEquals(List.of(), resumed.models(), "the resumed session named no models, which is the case this test is about");
        resumed.close();
        Files.delete(agentDir.resolve("FakeAcpAgent.java"));

        assertEquals(List.of("Sonnet " + agentDir.getFileName()), door().conversationModels("fake"),
            "the models the new session offered are still the catalogue's after the resumed one opened");
    }

    /**
     * 28h D57 (c): a seat stores the name its session offers ("Haiku 5.5"), and Claude's command line refuses that name (measured 2026-11-17:
     * unrecognized_model) and takes the session's value ("haiku"); the test run is given the value. A name the catalogue does not hold is
     * passed as it is, so a brain with no session of its own keeps the models its check read.
     */
    @Test
    void aTestRunIsGivenTheValueTheSessionOffersTheChosenModelUnder(@TempDir Path agentDir) throws IOException {
        String registry = fakeAgent(agentDir, "default=Default,haiku=Haiku " + agentDir.getFileName() + ",opus=Opus");
        stored.put("jawata.agent.hireBrains", registry);
        door().conversationModels("fake");

        assertEquals("haiku", SeatSettingsService.commandLineModel(registry, "fake", "Haiku " + agentDir.getFileName()));
        assertEquals("opus", SeatSettingsService.commandLineModel(registry, "fake", "opus"), "a value is kept as it is");
        assertEquals("sonnet", SeatSettingsService.commandLineModel(registry, "fake", "sonnet"), "a model the catalogue does not hold is passed as it is");
        assertEquals("haiku", SeatSettingsService.commandLineModel(registry, "claude-code", "haiku"), "a brain with no program in the registry is not translated");
        assertEquals(null, SeatSettingsService.commandLineModel(registry, "fake", null));
    }

    @Test
    void theRegistrysBrainsAreReadyAndAnUnknownOneIsRefused(@TempDir Path agentDir) throws IOException {
        stored.put("jawata.agent.hireBrains", fakeAgent(agentDir, ""));

        List<SeatSettings.ConversationBrain> brains = door().conversationBrains();
        assertEquals(new SeatSettings.ConversationBrain("fake", "fake", true, Optional.empty()), brains.get(0), "the registry's brain first, ready");
        assertTrue(brains.stream().skip(1).noneMatch(brain -> brain.ready() || brain.install().isEmpty()),
            "every other brain is one whose adapter is missing, with its install line: " + brains);
        IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> door().conversationModels("nobody"));
        assertTrue(refused.getMessage().contains("nobody"), refused.getMessage());
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
    void aProposedChangeIsShownAndNothingIsStored() {
        SeatSettings.View held = door().seats(Map.of(SettingKeys.SEATS_OFF.name(), "architect", SettingKeys.SEATS_BRAINS.name(), "developer=codex",
            SettingKeys.SEATS_WORKFLOW.name(), "developer=architect,worker"));
        SeatSettings.Seat architect = held.seats().stream().filter(row -> row.name().equals("architect")).findFirst().orElseThrow();
        SeatSettings.Seat developer = held.seats().stream().filter(row -> row.name().equals("developer")).findFirst().orElseThrow();

        assertFalse(architect.on(), "the held switch is shown");
        assertEquals("codex", developer.brain(), "the held brain is shown");
        assertEquals(Optional.of("architect"), developer.parent(), "the held place is shown");
        assertTrue(seat("architect").on(), "and nothing was stored: the stored view is as it was");
        assertFalse(seat("developer").parent().equals(Optional.of("architect")), "the stored place is not the held one");
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
        // Old assertion: assertEquals("claude-code", seat("developer").brain()). The file names no brain and nothing is stored, so the row is not set.
        assertEquals("", seat("developer").brain());
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
        assertEquals(Optional.of("'eliza' is not a brain a hire can start (known: claude-code, cursor, copilot, codex, antigravity)"), refusal);
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
        assertEquals(List.of("architect: its parent 'nobody' is no seat"), view.refusals(), "a refused tree is named as a refusal");
        assertEquals(List.of(), view.errors(), "and not among the errors, so a screen can say the default tree is used in its place");
        assertEquals(Optional.of("main"), seat("architect").parent(), "the engine uses the signed tree, and the screen says what the engine uses");
    }

    @Test
    void aSeatThePersonChangedIsMarkedSoAScreenCanTakeTheChangeBack() {
        stored.put(SettingKeys.SEATS_BRAINS.name(), "architect=codex");
        stored.put(SettingKeys.SEATS_WORKFLOW.name(), "developer=architect,worker");
        assertTrue(seat("architect").customised(), "a brain the person gave it");
        assertFalse(seat("architect").placed());
        assertTrue(seat("developer").placed(), "a place the person gave it");
        assertFalse(seat("developer").customised());
        stored.put(SettingKeys.SEATS_OFF.name(), "developer");
        assertTrue(seat("developer").customised(), "a switch the person turned off");
    }

    @Test
    void aSeatFileSaysWhetherItAcceptsABrainOnlyAnInstructionHolds() throws IOException {
        Files.writeString(folder.resolve("developer.md"), "---\nname: developer\naccepts_uncontained_writes: true\n---\nbody\n");
        assertTrue(seat("developer").acceptsUncontainedWrites());
        assertFalse(seat("architect").acceptsUncontainedWrites(), "a file that says nothing does not");
    }

    @Test
    void aSeatFileSaysWhetherTheSeatChangesCodeSoAScreenWarnsOnlyForTheOthers() throws IOException {
        Files.writeString(folder.resolve("developer.md"), "---\nname: developer\nchanges_code: true\n---\nbody\n");
        assertTrue(seat("developer").changesCode());
        assertFalse(seat("architect").changesCode(), "a file that says nothing does not change code");
    }

    @Test
    void aChangeTouchesOnlyItsSeatsEntryAndKeepsEveryOtherAsWritten() {
        Map<String, String> held = Map.of(SettingKeys.SEATS_OFF.name(), "developer", SettingKeys.SEATS_BRAINS.name(), "developer=codex",
            SettingKeys.SEATS_WORKFLOW.name(), "");
        SeatSettings.Edited off = door().edit(held, "architect", SeatSettings.Change.switched(false));
        assertEquals("developer,architect", off.values().get(SettingKeys.SEATS_OFF.name()));
        assertEquals("developer=codex", off.values().get(SettingKeys.SEATS_BRAINS.name()), "another key is as it was");
        assertEquals(Optional.empty(), off.refusal());

        SeatSettings.Edited brain = door().edit(off.values(), "architect", SeatSettings.Change.brain("copilot", "gpt-5"));
        assertEquals("developer=codex;architect=copilot/gpt-5", brain.values().get(SettingKeys.SEATS_BRAINS.name()));
        SeatSettings.Edited reset = door().edit(brain.values(), "architect", SeatSettings.Change.reset());
        assertEquals("developer", reset.values().get(SettingKeys.SEATS_OFF.name()), "Reset takes back the switch");
        assertEquals("developer=codex", reset.values().get(SettingKeys.SEATS_BRAINS.name()), "and the brain, and only this seat's");

        SeatSettings.Edited placed = door().edit(reset.values(), "developer", SeatSettings.Change.place("architect", "worker", null));
        assertEquals("developer=architect,worker", placed.values().get(SettingKeys.SEATS_WORKFLOW.name()));
        assertEquals("", door().edit(placed.values(), "developer", SeatSettings.Change.defaultPlace()).values().get(SettingKeys.SEATS_WORKFLOW.name()));
    }

    @Test
    void anEntryTheEngineCannotReadIsKeptAndNamedNotDroppedByAChangeToAnotherSeat() {
        stored.put(SettingKeys.SEATS_BRAINS.name(), "developer=eliza");
        SeatSettings.Edited edited = door().edit(Map.of(), "architect", SeatSettings.Change.brain("codex", null));
        assertEquals("developer=eliza;architect=codex", edited.values().get(SettingKeys.SEATS_BRAINS.name()), "the unreadable entry is still there");
        assertTrue(edited.refusal().orElse("").contains("'eliza' is not a brain a hire can start"), "and the refusal names it: " + edited.refusal());
        assertEquals("", door().edit(Map.of(), "developer", SeatSettings.Change.reset()).values().get(SettingKeys.SEATS_BRAINS.name()),
            "Reset of that seat takes the entry out");
    }

    @Test
    void aMoveTheTreeRefusesComesBackWithTheEnginesWords() {
        SeatSettings.Edited refused = door().edit(Map.of(), "architect", SeatSettings.Change.place("nobody", "reviewer", null));
        assertEquals(Optional.of("the engine would refuse this tree: architect: its parent 'nobody' is no seat"), refused.refusal());
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
        // the seats sealed in the engine bundle (generated by its build from jawata-studio), read from inside it: an engine in Eclipse has no folder
        SeatSettings.View view = SeatSettingsService.forEngine(settings, NO_TOOLS).seats();
        assertEquals(List.of("architect", "btw", "cataloguer", "chief-engineer", "debugger", "developer", "javadoc-writer", "main", "observer", "pm",
            "profiler", "report", "review", "rottenmeier", "senior-developer", "spec-auditor", "spec-editor", "test-manager", "test-writer", "tester", "ux"),
            view.seats().stream().map(SeatSettings.Seat::name).toList());
        assertEquals(List.of(), view.errors(), "every sealed seat is readable and the sealed tree has no refusal");
        assertEquals("on a package nobody has described; resumes where it stopped",
            view.seats().stream().filter(seat -> seat.name().equals("cataloguer")).findFirst().orElseThrow().when(), "a seat says when it is used, in its file's words");
    }

    @Test
    void aBundleWithNoSeatsFolderHasNoSealedSeats() {
        // org.jawata.core carries no seats: an answer of none, not a failure
        SeatFiles.Listing listing = SeatFiles.sealed(org.jawata.core.host.HostProcesses.class);
        assertEquals(List.of(), listing.seats());
        assertEquals(List.of(), listing.unreadable());
    }
}
