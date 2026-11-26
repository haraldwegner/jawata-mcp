package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jawata.mcp.execution.HireRegistry;
import org.jawata.mcp.execution.PersonsWords;
import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.step.GateOutcome;
import org.jawata.mcp.step.PlanId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, D57 (j): jawata, not he, commits, pushes and tags. The pm commits a finished step through this tool, only after that step's
 * checks passed and only what the step produced; main pushes, tags and publishes only on his release word; both refuse an AI agent as author
 * or co-author. Each test runs on a scratch git repository, and the release test pushes to a scratch bare repository standing for the remote.
 */
class GitToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path work;

    private Path repo;
    private Path remote;
    private PlanLedger ledger;
    private GitTool git;

    private static String run(Path dir, String... command) throws IOException, InterruptedException {
        List<String> line = new ArrayList<>(List.of("git", "-C", dir.toString()));
        line.addAll(List.of(command));
        Process process = new ProcessBuilder(line).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), String.join(" ", line) + ": " + out);
        return out.strip();
    }

    @BeforeEach
    void aRepositoryWithARemote() throws Exception {
        repo = Files.createDirectories(work.resolve("repo"));
        remote = Files.createDirectories(work.resolve("remote.git"));
        run(remote, "init", "-q", "--bare");
        run(repo, "init", "-q", "-b", "main");
        run(repo, "config", "user.name", "Person");
        run(repo, "config", "user.email", "person@example.invalid");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        run(repo, "add", "a.txt");
        run(repo, "commit", "-q", "-m", "start");
        run(repo, "remote", "add", "origin", remote.toString());
        run(repo, "push", "-q", "-u", "origin", "main");
        ledger = new PlanLedger();
        git = new GitTool(ledger);
    }

    /** A hire whose plan has one step, passed or not, that reported these files changed. */
    private String hireThatChanged(boolean passed, String... files) {
        ObjectNode none = JSON.createObjectNode();
        PlanId plan = ledger.open(List.of(new GatedStep(0, "compile_workspace", none)));
        if (passed) {
            ledger.advanceIfGatePassed(plan, new GateOutcome("compile_workspace", JSON.createObjectNode(), 0, true));
        }
        ObjectNode answer = JSON.createObjectNode().put("plan", plan.toString()).put("cleanExit", true);
        var changed = answer.putArray("changedFiles");
        for (String file : files) {
            changed.add(file);
        }
        HireRegistry.shared().record("developer", "brief for " + plan, answer);
        return plan.toString();
    }

    private ToolResponse commit(String plan, String message) {
        return git.execute(JSON.createObjectNode().put("action", "commit").put("folder", repo.toString()).put("planId", plan).put("message", message));
    }

    @Test
    @DisplayName("28h D57 (j): a step whose checks passed is committed, with only the files it produced; another changed file stays out")
    void aPassedStepIsCommittedWithOnlyItsFiles() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "two\n");
        Files.writeString(repo.resolve("other.txt"), "the person's own work\n");
        String plan = hireThatChanged(true, "a.txt");

        ToolResponse answer = commit(plan, "Step 1: a.txt says two");

        assertTrue(answer.isSuccess(), String.valueOf(answer.getError()));
        assertEquals("Step 1: a.txt says two", run(repo, "log", "-1", "--format=%s"));
        assertEquals("a.txt", run(repo, "show", "--name-only", "--format=", "HEAD"), "the commit held more than the step's file");
        assertEquals("?? other.txt", run(repo, "status", "--porcelain"), "the person's own file was committed or lost");
        assertEquals("Person", run(repo, "log", "-1", "--format=%an"), "the commit is not the person's");
    }

    @Test
    @DisplayName("28h D57 (j): a step whose checks did not pass is not committed")
    void aFailedStepIsNotCommitted() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "two\n");
        String plan = hireThatChanged(false, "a.txt");

        assertFalse(commit(plan, "Step 1").isSuccess());
        assertEquals("start", run(repo, "log", "-1", "--format=%s"), "a commit was made");
    }

    @Test
    @DisplayName("z103, z119: a refused commit names the check that did not pass, with its arguments and why")
    void aRefusedCommitNamesTheFailedCheck() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "two\n");
        PlanId plan = ledger.open(List.of(new GatedStep(0, "run_tests", JSON.createObjectNode().put("targetClasses", "X"))));
        ObjectNode answer = JSON.createObjectNode().put("plan", plan.toString());
        answer.putArray("changedFiles").add("a.txt");
        answer.putArray("gates").addObject().put("tool", "run_tests").put("answered", false)
            .put("notAnsweredBecause", "Invalid parameter 'projectKey'");
        HireRegistry.shared().record("developer", "brief for " + plan, answer);

        ToolResponse refused = commit(plan.toString(), "Step 1");

        String said = refused.getError().getMessage();
        assertTrue(said.contains("run_tests") && said.contains("targetClasses") && said.contains("projectKey"), said);
    }

    @Test
    @DisplayName("z125: a plan whose check did not pass is committed with recheck once the check passes on the files as they stand, and not while it fails")
    void aRecheckThatPassesCommitsThePlan() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "two\n");
        String plan = hireThatChanged(false, "a.txt");
        int[] errors = {1};
        ToolRegistry tools = new ToolRegistry();
        tools.register(new Tool() {
            @Override public String getName() {
                return "compile_workspace";
            }
            @Override public String getDescription() {
                return "a compile stand-in";
            }
            @Override public java.util.Map<String, Object> getInputSchema() {
                return java.util.Map.of();
            }
            @Override public ToolResponse execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                return ToolResponse.success(java.util.Map.of("errorCount", errors[0]));
            }
        });
        git = new GitTool(ledger, () -> tools);
        ObjectNode commit = JSON.createObjectNode().put("action", "commit").put("folder", repo.toString()).put("planId", plan)
            .put("message", "Step 1").put("recheck", true);

        ToolResponse whileFailing = git.execute(commit);
        errors[0] = 0;
        ToolResponse oncePassing = git.execute(commit);

        assertFalse(whileFailing.isSuccess(), "a failing check was committed on recheck");
        assertTrue(oncePassing.isSuccess(), String.valueOf(oncePassing.getError()));
        assertEquals("a.txt", run(repo, "show", "--name-only", "--format=", "HEAD"));
    }

    @Test
    @DisplayName("28h D57 (j): a commit message naming an AI agent as co-author is refused, and nothing is committed")
    void anAgentCoAuthorIsRefused() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "two\n");
        String plan = hireThatChanged(true, "a.txt");

        ToolResponse answer = commit(plan, "Step 1\n\nCo-Authored-By: Claude <noreply@anthropic.com>");

        assertFalse(answer.isSuccess());
        assertEquals("start", run(repo, "log", "-1", "--format=%s"));
        assertTrue(commit(plan, "Step 1\n\nCo-Authored-By: Ana Person <ana@example.invalid>").isSuccess(), "CONTROL: a person as co-author was refused");
    }

    @Test
    @DisplayName("28h-C26-28 (21): every agent the studio's commit hook names is refused here too, so the two guards do not disagree about who an AI agent is")
    void everyAgentTheCommitHookNamesIsRefusedHereToo() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "two\n");
        String plan = hireThatChanged(true, "a.txt");

        for (String agent : new String[] {"Aider", "Windsurf", "Codeium", "Gemini", "Google Gemini", "Codex", "Devin", "Copilot", "ChatGPT", "GPT-4", "OpenAI",
                "dependabot[bot]"}) {
            assertFalse(commit(plan, "Step 1\n\nCo-Authored-By: " + agent + " <bot@example.invalid>").isSuccess(), agent + " was accepted as a co-author");
        }
        assertEquals("start", run(repo, "log", "-1", "--format=%s"), "a commit was made");
    }

    private ToolResponse release(String caller, String tag) {
        ObjectNode arguments = JSON.createObjectNode().put("action", "release").put("folder", repo.toString()).put("tag", tag)
            .put("notes", "notes").put("publish", false);
        return CallerIdentity.supplied(caller).speaking(() -> git.execute(arguments));
    }

    @Test
    @DisplayName("28h D57 (j): nothing is pushed or tagged before his release word; on it the branch and the tag reach the remote")
    void aReleaseWaitsForHisWord() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "two\n");
        run(repo, "commit", "-q", "-am", "work");
        String caller = "principal-" + System.nanoTime();
        PersonsWords.said(caller, "looks good, carry on");

        assertFalse(release(caller, "v1.0.1").isSuccess(), "released without his word");
        assertEquals("", run(remote, "tag"), "a tag reached the remote");

        PersonsWords.said(caller, "Release it");
        ToolResponse answer = release(caller, "v1.0.1");

        assertTrue(answer.isSuccess(), String.valueOf(answer.getError()));
        assertEquals("v1.0.1", run(remote, "tag"));
        assertEquals(run(repo, "rev-parse", "HEAD"), run(remote, "rev-parse", "main"), "the branch was not pushed");
    }

    @Test
    @DisplayName("28h D57 (j): a release whose unpushed commits name an AI agent as author or co-author is refused, and nothing is pushed")
    void aReleaseCarryingAgentAttributionIsRefused() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "two\n");
        run(repo, "commit", "-q", "-am", "work\n\nCo-Authored-By: Claude <noreply@anthropic.com>");
        String caller = "principal-" + System.nanoTime();
        PersonsWords.said(caller, "release");

        assertFalse(release(caller, "v1.0.2").isSuccess());
        assertEquals("", run(remote, "tag"));
        assertTrue(!run(repo, "rev-parse", "HEAD").equals(run(remote, "rev-parse", "main")), "the attributed commit was pushed");
    }
}
