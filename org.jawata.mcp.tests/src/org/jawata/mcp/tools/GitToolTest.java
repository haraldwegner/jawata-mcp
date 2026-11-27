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

    /** A hire whose plan has one step, passed or not, that reported these files changed and is recorded as writing them. */
    private String hireThatChanged(boolean passed, String... files) {
        String plan = hireThatReported(passed, files);
        org.jawata.mcp.execution.WrittenFiles.shared().record(plan, java.util.Arrays.stream(files).map(file -> repo.resolve(file).toString()).toList());
        return plan;
    }

    /** The same hire with no write recorded: the tree changed while it ran, and nothing says who wrote what. */
    private String hireThatReported(boolean passed, String... files) {
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
    @DisplayName("28h z120: a commit holds only the files recorded for the hire; another hire's file and one recorded for nobody stay out and are listed")
    void aFileAnotherHireWroteIsLeftOut() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "two\n");
        Files.writeString(repo.resolve("theirs.txt"), "another hire's work\n");
        Files.writeString(repo.resolve("shell.txt"), "written by a shell, recorded for nobody\n");
        String plan = hireThatReported(true, "a.txt", "theirs.txt", "shell.txt");
        org.jawata.mcp.execution.WrittenFiles.shared().record(plan, List.of(repo.resolve("a.txt").toString()));
        org.jawata.mcp.execution.WrittenFiles.shared().record("plan-z120-other", List.of(repo.resolve("theirs.txt").toString()));

        ToolResponse answer = commit(plan, "Step 1");

        assertTrue(answer.isSuccess(), String.valueOf(answer.getError()));
        assertEquals("a.txt", run(repo, "show", "--name-only", "--format=", "HEAD"), "the commit held a file the hire is not recorded for");
        assertEquals("?? shell.txt\n?? theirs.txt", run(repo, "status", "--porcelain"), "a file left out was committed or lost");
        String told = String.valueOf(answer.getData());
        assertTrue(told.contains("theirs.txt") && told.contains("unclaimed=[shell.txt]"), "the answer does not list what it left out: " + told);
    }

    @Test
    @DisplayName("28h z120: a plan with no file recorded for it commits nothing, and the refusal names the files nobody claims")
    void aPlanThatWroteNothingRecordedIsRefused() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "two\n");
        String plan = hireThatReported(true, "a.txt");

        ToolResponse answer = commit(plan, "Step 1");

        assertFalse(answer.isSuccess(), "a change nobody claims was committed");
        assertTrue(answer.getError().getMessage().contains("a.txt"), answer.getError().getMessage());
        assertEquals("start", run(repo, "log", "-1", "--format=%s"), "a commit was made");
    }

    @Test
    @DisplayName("28h z120: a file that a hire still running also wrote is not committed, and the refusal names the file")
    void aFileARunningHireAlsoWroteIsRefused() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "two\n");
        String plan = hireThatChanged(true, "a.txt");
        try (HireRegistry.Registration other = HireRegistry.shared().begin("developer", "z120 still running", "")) {
            HireRegistry.shared().planOpened("plan-z120-running");
            org.jawata.mcp.execution.WrittenFiles.shared().record(plan, List.of(repo.resolve("a.txt").toString()));
            org.jawata.mcp.execution.WrittenFiles.shared().record("plan-z120-running", List.of(repo.resolve("a.txt").toString()));

            ToolResponse answer = commit(plan, "Step 1");

            assertFalse(answer.isSuccess(), "a file two hires wrote was committed while one still works on it");
            assertTrue(answer.getError().getMessage().contains("a.txt"), answer.getError().getMessage());
        }
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

    /** One entry of a hire's answer under `changes`: oldText and newText may be null (a file the hire created has no oldText). */
    private static ObjectNode change(String path, String oldText, String newText, boolean cleanBefore) {
        return JSON.createObjectNode().put("path", path).put("oldText", oldText).put("newText", newText).put("cleanBefore", cleanBefore);
    }

    /** A hire's recorded answer that carries `changes` and `changesOmitted`, as HireTool writes them; answers the plan's id. */
    private String hireThatCarriedChanges(List<ObjectNode> changes, String... omitted) {
        PlanId plan = ledger.open(List.of(new GatedStep(0, "compile_workspace", JSON.createObjectNode())));
        ObjectNode answer = JSON.createObjectNode().put("plan", plan.toString()).put("cleanExit", true);
        var changedFiles = answer.putArray("changedFiles");
        var carried = answer.putArray("changes");
        for (ObjectNode one : changes) {
            changedFiles.add(one.get("path").asText());
            carried.add(one);
        }
        var left = answer.putArray("changesOmitted");
        for (String file : omitted) {
            changedFiles.add(file);
            left.add(file);
        }
        HireRegistry.shared().record("developer", "brief for " + plan, answer);
        return plan.toString();
    }

    /** The discard call for a plan; run as the given caller, or with no caller identity at all when callerId is null. */
    private ToolResponse discard(String callerId, String plan, Path folder, String... files) {
        ObjectNode arguments = JSON.createObjectNode().put("action", "discard").put("folder", folder.toString()).put("planId", plan);
        var named = arguments.putArray("files");
        for (String file : files) {
            named.add(file);
        }
        if (callerId == null) {
            return git.execute(arguments);
        }
        return CallerIdentity.supplied(callerId).speaking(() -> git.execute(arguments));
    }

    /** The strings a successful answer holds under a key; a refusal fails here with its own message. */
    private static List<String> listed(ToolResponse answer, String key) {
        assertTrue(answer.isSuccess(), String.valueOf(answer.getError()));
        Object value = ((java.util.Map<?, ?>) answer.getData()).get(key);
        return ((List<?>) value).stream().map(String::valueOf).toList();
    }

    /** A refusal's code and message on one line; fails when the call was not refused. */
    private static String refusedWith(ToolResponse answer) {
        assertFalse(answer.isSuccess(), "the call was not refused");
        return answer.getError().getCode() + ": " + answer.getError().getMessage();
    }

    @Test
    @DisplayName("28h Stage 28 (h): discard puts a tracked file back to its committed bytes exactly, and deletes a file the hire created")
    void discardPutsBackExactBytesAndDeletesWhatTheHireCreated() throws Exception {
        run(repo, "config", "core.autocrlf", "false");
        Files.write(repo.resolve("b.txt"), "b\r\n".getBytes(StandardCharsets.UTF_8));
        run(repo, "add", "b.txt");
        run(repo, "commit", "-q", "-m", "b");
        Files.write(repo.resolve("b.txt"), "bb\r\n".getBytes(StandardCharsets.UTF_8));
        Files.writeString(repo.resolve("new.txt"), "made by the hire\n");
        String plan = hireThatCarriedChanges(List.of(change("b.txt", "b\r\n", "bb\r\n", true), change("new.txt", null, "made by the hire\n", true)));
        String caller = "discard-t1-" + System.nanoTime();
        HireRegistry.shared().startedBy(plan, caller);

        ToolResponse answer = discard(caller, plan, repo, "b.txt", "new.txt");

        List<String> thrownAway = listed(answer, "thrownAway");
        assertTrue(java.util.Arrays.equals("b\r\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(repo.resolve("b.txt"))), "b.txt is not back to its committed bytes");
        assertFalse(Files.exists(repo.resolve("new.txt")), "the file the hire created was not deleted");
        assertTrue(thrownAway.contains("b.txt") && thrownAway.contains("new.txt"), "thrownAway is " + thrownAway);
    }

    @Test
    @DisplayName("28h Stage 28 (h): discard leaves a file untouched that the person had changed before the hire ran, and says why")
    void discardKeepsAFileThePersonChangedBeforeTheHire() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "two\n");
        String plan = hireThatCarriedChanges(List.of(change("a.txt", "one\n", "two\n", false)));
        String caller = "discard-t2-" + System.nanoTime();
        HireRegistry.shared().startedBy(plan, caller);

        ToolResponse answer = discard(caller, plan, repo, "a.txt");

        assertEquals(List.of("a.txt (you had changes in it before the hire ran)"), listed(answer, "kept"));
        assertEquals(List.of(), listed(answer, "thrownAway"));
        assertEquals("two\n", Files.readString(repo.resolve("a.txt")), "the file was touched");
    }

    @Test
    @DisplayName("28h Stage 28 (h): discard leaves a file untouched whose text is no longer the hire's, and says it changed since")
    void discardKeepsAFileThatChangedSinceTheHire() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "three\n");
        String plan = hireThatCarriedChanges(List.of(change("a.txt", "one\n", "two\n", true)));
        String caller = "discard-t3-" + System.nanoTime();
        HireRegistry.shared().startedBy(plan, caller);

        ToolResponse answer = discard(caller, plan, repo, "a.txt");

        assertEquals(List.of("a.txt (it has changed since the hire)"), listed(answer, "kept"));
        assertEquals("three\n", Files.readString(repo.resolve("a.txt")), "the file was touched");
    }

    @Test
    @DisplayName("28h Stage 28 (h): a hire that ran in a subfolder has its file put back, the path counted from the repository top")
    void discardWorksForAHireThatRanInASubfolder() throws Exception {
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/B.java"), "class B {}\n");
        run(repo, "add", "src/B.java");
        run(repo, "commit", "-q", "-m", "B");
        Files.writeString(repo.resolve("src/B.java"), "class B { int x; }\n");
        String plan = hireThatCarriedChanges(List.of(change("src/B.java", "class B {}\n", "class B { int x; }\n", true)));
        String caller = "discard-t4-" + System.nanoTime();
        HireRegistry.shared().startedBy(plan, caller);

        ToolResponse answer = discard(caller, plan, repo.resolve("src"), "src/B.java");

        List<String> thrownAway = listed(answer, "thrownAway");
        assertEquals("class B {}\n", Files.readString(repo.resolve("src/B.java")), "the file is not back to its committed text");
        assertEquals(List.of("src/B.java"), thrownAway);
    }

    @Test
    @DisplayName("28h Stage 28 (h): a file whose path leaves the hire's folder is left untouched, and says so")
    void discardKeepsAFileOutsideTheHiresFolder() throws Exception {
        Files.writeString(work.resolve("outside.txt"), "new\n");
        String plan = hireThatCarriedChanges(List.of(change("../outside.txt", "old\n", "new\n", true)));
        String caller = "discard-t5-" + System.nanoTime();
        HireRegistry.shared().startedBy(plan, caller);

        ToolResponse answer = discard(caller, plan, repo, "../outside.txt");

        assertEquals(List.of("../outside.txt (its path leaves the hire's folder)"), listed(answer, "kept"));
        assertEquals("new\n", Files.readString(work.resolve("outside.txt")), "the file outside the folder was touched");
    }

    @Test
    @DisplayName("28h Stage 28 (h): a file the plan did not change refuses the whole call, naming it, and touches no other file")
    void discardRefusesAFileThePlanDidNotChange() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "two\n");
        String plan = hireThatCarriedChanges(List.of(change("a.txt", "one\n", "two\n", true)));
        String caller = "discard-t6-" + System.nanoTime();
        HireRegistry.shared().startedBy(plan, caller);

        ToolResponse answer = discard(caller, plan, repo, "a.txt", "ghost.txt");

        String said = refusedWith(answer);
        assertTrue(said.startsWith("FILE_NOT_CHANGED_BY_PLAN") && said.contains("ghost.txt"), said);
        assertEquals("two\n", Files.readString(repo.resolve("a.txt")), "a file was put back although the call was refused");
    }

    @Test
    @DisplayName("28h Stage 28 (h): discard is refused for a caller who did not issue the plan, and for a call with no caller, and touches nothing")
    void discardIsRefusedForSomeoneElsesHire() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "two\n");
        String plan = hireThatCarriedChanges(List.of(change("a.txt", "one\n", "two\n", true)));
        HireRegistry.shared().startedBy(plan, "discard-t7-issuer-" + System.nanoTime());

        String noCaller = refusedWith(discard(null, plan, repo, "a.txt"));
        String stranger = refusedWith(discard("discard-t7-stranger-" + System.nanoTime(), plan, repo, "a.txt"));

        assertTrue(noCaller.startsWith("NOT_YOUR_HIRE"), "no caller: " + noCaller);
        assertTrue(stranger.startsWith("NOT_YOUR_HIRE"), "a stranger: " + stranger);
        assertEquals("two\n", Files.readString(repo.resolve("a.txt")), "a file was put back although the call was refused");
    }

    @Test
    @DisplayName("28h Stage 28 (h): a file too large for the hire to carry is kept, and the answer says to put it back yourself")
    void discardKeepsAFileTooLargeToCompare() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "two\n");
        Files.writeString(repo.resolve("big.txt"), "very large\n");
        String plan = hireThatCarriedChanges(List.of(change("a.txt", "one\n", "two\n", true)), "big.txt");
        String caller = "discard-t8-" + System.nanoTime();
        HireRegistry.shared().startedBy(plan, caller);

        ToolResponse answer = discard(caller, plan, repo, "big.txt");

        assertEquals(List.of("big.txt (too large to compare, put it back yourself)"), listed(answer, "kept"));
        assertEquals(List.of(), listed(answer, "thrownAway"));
    }
}
