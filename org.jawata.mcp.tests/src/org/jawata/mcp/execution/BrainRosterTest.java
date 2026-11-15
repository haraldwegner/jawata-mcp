package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jawata.core.host.HostOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sprint 28k, Amendment 3, Stage 25e: what each agent program says of itself, read from fake programs on a test path. */
class BrainRosterTest {

    private static final String PROMPT = "Reply with the single word OK.";
    /** What Claude Code's own help said when it was measured, 2026-10-12: the apostrophe in "model's" must not pair with the quote after it. */
    private static final String CLAUDE_HELP = """
          --model <model>                       Model for the current session. Provide
                                                an alias for the latest model (e.g.
                                                'fable', 'opus', or 'sonnet') or a
                                                model's full name (e.g.
                                                'claude-fable-5').
          -n, --name <name>                     Set a display name for this session
        """;

    @TempDir
    Path bin;

    @BeforeEach
    void onlyWhereAShellScriptIsAProgram() {
        assumeTrue(!HostOS.current().isWindows(), "the fixtures are POSIX shell scripts");
    }

    private void program(String name, String body) throws IOException {
        program(bin, name, body);
    }

    private static void program(Path folder, String name, String body) throws IOException {
        Path file = folder.resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        assertTrue(file.toFile().setExecutable(true));
    }

    private BrainRoster roster() {
        return new BrainRoster(bin.toString());
    }

    private BrainRoster.Status status(String id) {
        return statusIn(roster(), id);
    }

    private static BrainRoster.Status statusIn(BrainRoster roster, String id) {
        return roster.statuses().stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow();
    }

    /** The arguments one test run really passes, one per line as the fake program received them. */
    private List<String> argumentsOf(String brain, String programName, String model, String answer) throws IOException {
        Path seen = bin.resolve("seen.txt");
        program(programName, "printf '%s\\n' \"$@\" > '" + seen + "'; " + answer);
        roster().probe(brain, model);
        return Files.readAllLines(seen);
    }

    @Test
    void aMissingProgramIsNotInstalledAndNoLoginIsGuessed() {
        BrainRoster.Status claude = status("claude-code");
        assertFalse(claude.installed());
        assertEquals(BrainRoster.Login.UNKNOWN, claude.login());
        assertEquals(Optional.of("`claude` is not installed on this machine."), claude.reason());
        assertEquals("claude auth login", claude.loginCommand());
    }

    @Test
    void everyKnownBrainIsReportedWithWhatAHireCanDoWithIt() {
        assertEquals(List.of("claude-code", "codex", "cursor", "copilot", "grok"), roster().statuses().stream().map(BrainRoster.Status::id).toList());
        assertTrue(status("cursor").writesHeldByInstruction());
        assertTrue(status("cursor").takesModel());
        assertFalse(status("codex").takesModel());
        assertFalse(status("grok").hirable());
        assertTrue(status("copilot").hirable());
    }

    @Test
    void whoseWritesAreHeldOnlyByAnInstructionIsWhatEachDialectAnswers() {
        SelfEndpoint self = new SelfEndpoint("http://127.0.0.1:1/mcp", "t");
        List<BrainRoster.Status> hirable = roster().statuses().stream().filter(BrainRoster.Status::hirable).toList();
        List<String> roster = hirable.stream().filter(BrainRoster.Status::writesHeldByInstruction).map(BrainRoster.Status::id).toList();
        List<String> dialects = hirable.stream()
            .filter(brain -> !SeatHire.read("---\nname: probe\nbrain: " + brain.id() + "\n---\n").dialect(self, List.of()).writeContainment().isContained())
            .map(BrainRoster.Status::id).toList();

        assertEquals(List.of("claude-code", "codex", "cursor", "copilot"), hirable.stream().map(BrainRoster.Status::id).toList(), "the brains asked about");
        assertEquals(dialects, roster, "the roster's brains held only by an instruction are the dialects' own answer");
    }

    @Test
    void aSignedInClaudeSaysSoAndNamesItsModels() throws IOException {
        program("claude", "case \"$1\" in --version) echo 2.1.0;; auth) echo '{\"loggedIn\": true}';; --help) cat <<'EOF'\n" + CLAUDE_HELP + "EOF\n;; esac");
        BrainRoster.Status claude = status("claude-code");
        assertTrue(claude.installed());
        assertEquals(Optional.of("2.1.0"), claude.version());
        assertEquals(BrainRoster.Login.LOGGED_IN, claude.login());
        assertEquals(Optional.of(List.of("fable", "opus", "sonnet", "claude-fable-5")), claude.models());
    }

    @Test
    void aModelNamedTwiceIsListedOnceAndAHelpNamingNoneGivesNoList() throws IOException {
        program("claude", "case \"$1\" in --version) echo 2;; auth) echo '{\"loggedIn\": true}';; --help) echo \"  --model <model>  'opus' or 'opus' again\";; esac");
        assertEquals(Optional.of(List.of("opus")), status("claude-code").models());
        program("claude", "case \"$1\" in --version) echo 2;; auth) echo '{\"loggedIn\": true}';; --help) echo 'no such option here';; esac");
        assertEquals(Optional.empty(), status("claude-code").models());
    }

    @Test
    void aClaudeThatIsNotSignedInSaysWhy() throws IOException {
        program("claude", "case \"$1\" in --version) echo 2;; auth) echo '{\"loggedIn\": false}';; esac");
        BrainRoster.Status claude = status("claude-code");
        assertEquals(BrainRoster.Login.NOT_LOGGED_IN, claude.login());
        assertEquals(Optional.of("Claude Code says it is not logged in."), claude.reason());
    }

    @Test
    void anUnreadableAnswerIsNotALogin() throws IOException {
        program("claude", "case \"$1\" in --version) echo 2;; auth) echo garbage;; esac");
        assertEquals(BrainRoster.Login.UNKNOWN, status("claude-code").login());
    }

    @Test
    void codexIsReadFromItsLoginStatus() throws IOException {
        program("codex", "case \"$1\" in --version) echo 1;; login) echo 'Logged in using ChatGPT';; esac");
        assertEquals(BrainRoster.Login.LOGGED_IN, status("codex").login());
        program("codex", "case \"$1\" in --version) echo 1;; login) echo 'Not logged in';; esac");
        assertEquals(BrainRoster.Login.NOT_LOGGED_IN, status("codex").login());
        assertEquals(Optional.of("Not logged in"), status("codex").reason());
    }

    @Test
    void cursorIsReadFromItsModelList() throws IOException {
        program("cursor-agent", "case \"$1\" in --version) echo 1;; models) echo gpt;; esac");
        assertEquals(BrainRoster.Login.LOGGED_IN, status("cursor").login());
    }

    @Test
    void aCursorThatExitsCleanlyButAsksForALoginIsNotSignedIn() throws IOException {
        program("cursor-agent", "case \"$1\" in --version) echo 1;; models) echo \"Error: Authentication required. Run 'agent login'\";; esac");
        BrainRoster.Status cursor = status("cursor");
        assertEquals(BrainRoster.Login.NOT_LOGGED_IN, cursor.login());
        assertEquals(Optional.of("Error: Authentication required. Run 'agent login'"), cursor.reason());
    }

    @Test
    void aCursorThatFailsForAnyReasonIsNotSignedIn() throws IOException {
        program("cursor-agent", "case \"$1\" in --version) echo 1;; models) echo 'the network is down'; exit 1;; esac");
        assertEquals(BrainRoster.Login.NOT_LOGGED_IN, status("cursor").login());
    }

    @Test
    void copilotCannotSayWithoutStartingASession() throws IOException {
        program("copilot", "echo 1");
        BrainRoster.Status copilot = status("copilot");
        assertEquals(BrainRoster.Login.UNKNOWN, copilot.login());
        assertEquals(Optional.of("Copilot has no command that reports its login without starting a session."), copilot.reason());
    }

    @Test
    void grokListsItsModels() throws IOException {
        program("grok", "case \"$1\" in --version) echo 1;; models) printf 'You are logged in with x\\n* grok-1 (default)\\n- grok-2\\n';; esac");
        assertEquals(BrainRoster.Login.LOGGED_IN, status("grok").login());
        assertEquals(Optional.of(List.of("grok-1", "grok-2")), status("grok").models());
    }

    @Test
    void aGrokThatIsNotSignedInSaysSoAndNamesNoModels() throws IOException {
        program("grok", "case \"$1\" in --version) echo 1;; models) echo 'Please sign in.';; esac");
        BrainRoster.Status grok = status("grok");
        assertEquals(BrainRoster.Login.NOT_LOGGED_IN, grok.login());
        assertEquals(Optional.of("Please sign in."), grok.reason());
        assertEquals(Optional.empty(), grok.models());
    }

    @Test
    void aBrainThatAnswersTheTestWordPasses() throws IOException {
        program("claude", "echo '{\"is_error\": false, \"result\": \"OK\", \"total_cost_usd\": 0.007, \"modelUsage\": {\"cheap\": {\"costUSD\": 0.001}, \"dear\": {\"costUSD\": 0.006}}}'");
        BrainRoster.Probe probe = roster().probe("claude-code", null);
        assertTrue(probe.ok());
        assertEquals(Optional.of("OK"), probe.answer());
        assertEquals(List.of("dear", "cheap"), probe.modelsUsed());
        assertEquals(Optional.of(0.007), probe.costUsd());
    }

    @Test
    void aBrainThatDoesNotAnswerFailsWithItsOwnWords() throws IOException {
        program("claude", "echo '{\"is_error\": true, \"result\": \"credit balance too low\"}'");
        BrainRoster.Probe probe = roster().probe("claude-code", null);
        assertFalse(probe.ok());
        assertEquals(Optional.of("credit balance too low"), probe.error());
    }

    private void assertAnswered(String brain, String answer) {
        BrainRoster.Probe probe = roster().probe(brain, null);
        assertTrue(probe.ok(), brain + ": " + probe);
        assertEquals(Optional.of(answer), probe.answer(), brain);
        assertEquals(Optional.empty(), probe.error(), brain);
    }

    private void assertFailed(String brain, String words) {
        BrainRoster.Probe probe = roster().probe(brain, null);
        assertFalse(probe.ok(), brain + ": " + probe);
        assertEquals(Optional.of(words), probe.error(), brain);
    }

    @Test
    void codexAnswersInItsOwnFormat() throws IOException {
        program("codex", "echo '{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"OK\"}}'");
        assertAnswered("codex", "OK");
    }

    @Test
    void codexFailsInItsOwnWords() throws IOException {
        program("codex", "echo '{\"type\":\"error\",\"message\":\"usage limit reached\"}'");
        assertFailed("codex", "usage limit reached");
        program("codex", "echo '{\"type\":\"turn.failed\",\"error\":{\"message\":\"model refused\"}}'");
        assertFailed("codex", "model refused");
    }

    @Test
    void copilotAnswersInItsOwnFormat() throws IOException {
        program("copilot", "echo '{\"type\":\"assistant.message\",\"data\":{\"content\":\"OK\",\"model\":\"gpt\"}}'");
        assertAnswered("copilot", "OK");
        assertEquals(List.of("gpt"), roster().probe("copilot", null).modelsUsed());
    }

    @Test
    void copilotFailsInItsOwnWords() throws IOException {
        program("copilot", "echo '{\"type\":\"session.error\",\"data\":{\"message\":\"no subscription\"}}'");
        assertFailed("copilot", "no subscription");
    }

    @Test
    void cursorAnswersInJsonOrInPlainText() throws IOException {
        program("cursor-agent", "echo '{\"result\":\"OK\"}'");
        assertAnswered("cursor", "OK");
        program("cursor-agent", "echo plain");
        assertAnswered("cursor", "plain");
    }

    @Test
    void aCursorTestRunThatFailsOrAsksForALoginDidNotPass() throws IOException {
        program("cursor-agent", "echo 'Error: Authentication required.'");
        assertFailed("cursor", "Error: Authentication required.");
        program("cursor-agent", "echo 'the network is down'; exit 1");
        assertFailed("cursor", "the network is down");
    }

    @Test
    void aTestRunOnAMissingProgramSaysItIsNotInstalled() {
        BrainRoster.Probe probe = roster().probe("codex", null);
        assertFalse(probe.ok());
        assertEquals(Optional.of("`codex` is not installed on this machine."), probe.error());
    }

    @Test
    void aTestRunIsRefusedWhereAHireCouldNotDoIt() {
        assertEquals("no brain named 'eliza'", assertThrows(IllegalArgumentException.class, () -> roster().probe("eliza", null)).getMessage());
        assertEquals("'grok' is not a brain a hire can start", assertThrows(IllegalArgumentException.class, () -> roster().probe("grok", null)).getMessage());
        assertEquals("Codex chooses its own model; a hire cannot pass one to it", assertThrows(IllegalArgumentException.class, () -> roster().probe("codex", "x")).getMessage());
    }

    @Test
    void aClaudeTestRunIsStrippedOfEverythingThatCosts() throws IOException {
        assertEquals(List.of("-p", PROMPT, "--output-format", "json", "--max-turns", "1", "--no-session-persistence", "--strict-mcp-config", "--setting-sources", "project",
            "--system-prompt", PROMPT, "--tools", "", "--model", "opus"),
            argumentsOf("claude-code", "claude", "opus", "echo '{\"is_error\": false, \"result\": \"OK\"}'"));
    }

    @Test
    void aCodexTestRunCannotWrite() throws IOException {
        assertEquals(List.of("exec", "--json", "--sandbox", "read-only", "--skip-git-repo-check", PROMPT), argumentsOf("codex", "codex", null, "echo x"));
    }

    @Test
    void aCursorTestRunOnlyPlansAndThePromptComesLast() throws IOException {
        assertEquals(List.of("--print", "--output-format", "json", "--trust", "--mode", "plan", "--model", "fast", PROMPT), argumentsOf("cursor", "cursor-agent", "fast", "echo x"));
    }

    @Test
    void aCopilotTestRunHasNoBuiltInServers() throws IOException {
        assertEquals(List.of("-p", PROMPT, "--output-format", "json", "--disable-builtin-mcps"), argumentsOf("copilot", "copilot", null, "echo x"));
    }

    @Test
    void theTestRunsFolderIsGoneAfterwards() throws IOException {
        Path seen = bin.resolve("where.txt");
        program("codex", "pwd > '" + seen + "'; touch left-behind; echo '{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"OK\"}}'");
        roster().probe("codex", null);
        assertFalse(Files.exists(Path.of(Files.readString(seen).strip())), "the folder the run used was removed");
    }

    @Test
    void aProgramThatHangsIsStoppedAtItsTimeoutAndWhatItStartedWithIt() throws Exception {
        Path child = bin.resolve("child.pid");
        program("claude", "sleep 300 & echo $! > '" + child + "'; wait");
        BrainRoster.Status claude = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> statusIn(new BrainRoster(bin.toString(), Duration.ofSeconds(1)), "claude-code"));
        assertTrue(claude.installed(), "it started, so it is there");
        long pid = Long.parseLong(Files.readString(child).strip());
        for (int i = 0; i < 50 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); i++) {
            Thread.sleep(100);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "the program's child was stopped too");
    }

    @Test
    void aProgramThatLeavesSomethingRunningDoesNotHoldItsAnswerUp() throws Exception {
        program("copilot", "sleep 30 & echo 1");
        BrainRoster.Status copilot = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> status("copilot"));
        assertTrue(copilot.installed());
    }

    @Test
    void aMachineWithCursorsAgentNamesItForAllThreeSettings() throws IOException {
        program("agent", "exit 0");
        BrainRoster.AgentSettings settings = roster().agentSettings();
        assertEquals(Optional.empty(), settings.note());
        String one = "{\"command\":[\"" + bin.resolve("agent") + "\",\"acp\"],\"env\":{}}";
        assertEquals(Map.of("jawata.agent.brain", one, "jawata.agent.hireBrains", "{\"cursor\":" + one + "}", "jawata.agent.reviewer", one), settings.settings());
    }

    @Test
    void whatIsWrittenForAHireIsWhatAHireReads() throws IOException {
        program("agent", "exit 0");
        String registry = roster().agentSettings().settings().get("jawata.agent.hireBrains");
        assertEquals(Optional.of(new AcpBrainSession.Program(List.of(bin.resolve("agent").toString(), "acp"), Map.of())), AcpBrainSession.programFor(registry, "cursor"));
    }

    /** Stage 25m (the studio's ADAPTERS, carried over): each brain whose ACP adapter is on the path is named in the registry, the rest are missing. */
    @Test
    void eachAdapterOnThePathIsNamedInTheRegistryAndTheRestAreMissing() throws IOException {
        program("agent", "exit 0");
        program("claude-code-acp", "exit 0");
        program("copilot", "exit 0");
        String registry = roster().agentSettings().settings().get("jawata.agent.hireBrains");
        // the adapter's folder first on its PATH, so an npm script finds the node beside it; this path holds only that folder, and no empty
        // entry (which would mean the current folder) is added
        Map<String, String> folderFirst = Map.of("PATH", bin.toString());
        assertEquals(Optional.of(new AcpBrainSession.Program(List.of(bin.resolve("claude-code-acp").toString()), folderFirst)),
            AcpBrainSession.programFor(registry, "claude-code"), "the second adapter of a brain serves when the first is missing: " + registry);
        assertEquals(Optional.of(new AcpBrainSession.Program(List.of(bin.resolve("copilot").toString(), "--acp"), folderFirst)),
            AcpBrainSession.programFor(registry, "copilot"), "Copilot's program is started with its ACP argument: " + registry);
        String longer = new BrainRoster("/elsewhere" + java.io.File.pathSeparator + bin).agentSettings().settings().get("jawata.agent.hireBrains");
        assertEquals(bin + java.io.File.pathSeparator + "/elsewhere", AcpBrainSession.programFor(longer, "copilot").orElseThrow().environment().get("PATH"),
            "with more folders on the path, the adapter's folder moves to the front and the rest keep their order");
        assertEquals(Optional.empty(), AcpBrainSession.programFor(registry, "codex"), "codex-acp is not on the path: " + registry);
        assertEquals(List.of(new BrainRoster.MissingAdapter("codex", "npm install -g @zed-industries/codex-acp")), roster().missingAdapters());
    }

    @Test
    void aBrainWithNoAdapterIsMissingOnceWithItsFirstInstallLine() {
        assertEquals(List.of(new BrainRoster.MissingAdapter("claude-code", "npm install -g @agentclientprotocol/claude-agent-acp"),
            new BrainRoster.MissingAdapter("codex", "npm install -g @zed-industries/codex-acp"),
            new BrainRoster.MissingAdapter("copilot", "npm install -g @github/copilot")), roster().missingAdapters());
    }

    @Test
    void theLongNameIsTakenWhereTheShortOneIsMissing() throws IOException {
        program("cursor-agent", "exit 0");
        assertTrue(roster().agentSettings().settings().get("jawata.agent.brain").contains(bin.resolve("cursor-agent").toString()));
    }

    @Test
    void theShortNameWinsOverTheLongOne() throws IOException {
        program("cursor-agent", "exit 0");
        program("agent", "exit 0");
        assertTrue(roster().agentSettings().settings().get("jawata.agent.brain").contains("\"" + bin.resolve("agent") + "\""));
    }

    @Test
    void theEarlierFolderOnThePathWins(@TempDir Path later) throws IOException {
        program("agent", "exit 0");
        program(later, "agent", "exit 0");
        String value = new BrainRoster(bin + java.io.File.pathSeparator + later).agentSettings().settings().get("jawata.agent.brain");
        assertTrue(value.contains(bin.resolve("agent").toString()), value);
    }

    @Test
    void aFileThatCannotBeRunIsNotAProgram() throws IOException {
        program("agent", "exit 0");
        assertTrue(bin.resolve("agent").toFile().setExecutable(false));
        assertEquals(Map.of(), roster().agentSettings().settings());
    }

    @Test
    void aSettingIsNeverWrittenFromARelativeFolderButAStatusCheckStillFindsThere() throws IOException {
        program("agent", "exit 0");
        program("codex", "case \"$1\" in --version) echo 1;; login) echo 'Logged in';; esac");
        BrainRoster relative = new BrainRoster(Path.of("").toAbsolutePath().relativize(bin.toAbsolutePath()).toString());
        assertEquals(Map.of(), relative.agentSettings().settings(), "a relative folder means something else from another directory");
        assertTrue(statusIn(relative, "codex").installed(), "the operating system would find it there");
    }

    @Test
    void anEntryNoPathCanBeMadeOfIsSkipped() throws IOException {
        program("agent", "exit 0");
        BrainRoster roster = new BrainRoster("bad\0entry" + java.io.File.pathSeparator + bin);
        assertEquals(Optional.empty(), roster.agentSettings().note());
    }

    @Test
    void aMachineWithoutItSetsNothingAndSaysWhy() {
        BrainRoster.AgentSettings settings = roster().agentSettings();
        assertEquals(Map.of(), settings.settings());
        assertEquals(Optional.of("no agent brain was configured: Cursor's agent program is not installed on this machine"), settings.note());
        assertEquals(Optional.of("no agent brain was configured: PATH is not set, so `cursor-agent` could not be looked for"), new BrainRoster(null).agentSettings().note());
    }
}
