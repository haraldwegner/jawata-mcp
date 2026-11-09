package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sprint 28k, Amendment 3, Stage 25e: what each agent program says of itself, read from fake programs on a test path. */
class BrainRosterTest {

    private static final String CLAUDE_HELP = "Usage\n  --model <model>  'fable', 'opus' or a model's full name\n  --other  x\n";

    @TempDir
    Path bin;

    private void program(String name, String body) throws IOException {
        Path file = bin.resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        assertTrue(file.toFile().setExecutable(true));
    }

    private BrainRoster roster() {
        return new BrainRoster(bin.toString());
    }

    private BrainRoster.Status status(String id) {
        return roster().statuses().stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow();
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
    }

    @Test
    void aSignedInClaudeSaysSoAndNamesItsModels() throws IOException {
        program("claude", "case \"$1\" in --version) echo 2.1.0;; auth) echo '{\"loggedIn\": true}';; --help) cat <<'EOF'\n" + CLAUDE_HELP + "EOF\n;; esac");
        BrainRoster.Status claude = status("claude-code");
        assertTrue(claude.installed());
        assertEquals(Optional.of("2.1.0"), claude.version());
        assertEquals(BrainRoster.Login.LOGGED_IN, claude.login());
        assertEquals(Optional.of(List.of("fable", "opus")), claude.models());
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
        program("cursor-agent", "case \"$1\" in --version) echo 1;; models) echo \"Error: Authentication required. Run 'agent login'\"; exit 1;; esac");
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

    @Test
    void codexAndCopilotAndCursorAreReadFromTheirOwnFormats() throws IOException {
        program("codex", "echo '{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"OK\"}}'");
        assertTrue(roster().probe("codex", null).ok());
        program("copilot", "echo '{\"type\":\"assistant.message\",\"data\":{\"content\":\"OK\",\"model\":\"gpt\"}}'");
        assertEquals(List.of("gpt"), roster().probe("copilot", null).modelsUsed());
        program("cursor-agent", "echo 'Error: Authentication required.'; exit 1");
        assertFalse(roster().probe("cursor", null).ok());
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
    void theModelReachesTheBrainThatTakesOne() throws IOException {
        Path seen = bin.resolve("seen.txt");
        program("claude", "echo \"$@\" > '" + seen + "'; echo '{\"is_error\": false, \"result\": \"OK\"}'");
        roster().probe("claude-code", "opus");
        assertTrue(Files.readString(seen).contains("--model opus"), Files.readString(seen));
    }

    @Test
    void theTestRunsFolderIsGoneAfterwards() throws IOException {
        Path seen = bin.resolve("where.txt");
        program("codex", "pwd > '" + seen + "'; touch left-behind; echo '{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"OK\"}}'");
        roster().probe("codex", null);
        assertFalse(Files.exists(Path.of(Files.readString(seen).strip())), "the folder the run used was removed");
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
    void aMachineWithoutItSetsNothingAndSaysWhy() {
        BrainRoster.AgentSettings settings = roster().agentSettings();
        assertEquals(Map.of(), settings.settings());
        assertEquals(Optional.of("no agent brain was configured: Cursor's agent program is not installed on this machine"), settings.note());
        assertEquals(Optional.of("no agent brain was configured: PATH is not set, so `cursor-agent` could not be looked for"), new BrainRoster(null).agentSettings().note());
    }
}
