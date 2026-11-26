package org.jawata.mcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostProcessOutcome;
import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.execution.HireRegistry;
import org.jawata.mcp.execution.PersonsWords;
import org.jawata.mcp.execution.ToolGateRunner;
import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Committing a finished step and releasing (Sprint 28h, D57 (j)). His words: <i>"Commit, push or tag code changes -> Who does this? I do not
 * want to use gh by myself?"</i> The pm commits each step through this tool, only after the step's checks passed and only the files the step's
 * hire produced; main releases through it, only on his release word: the branch is pushed, the tag made and pushed, and the release published.
 * Both refuse an AI agent as author or co-author, because an attribution that reaches the remote cannot be taken back.
 */
public final class GitTool implements Tool {

    /** Words that name an AI agent in an author or co-author line. */
    private static final Pattern AGENT = Pattern.compile(
        "claude|anthropic|cursor|copilot|openai|chatgpt|\\bgpt-|gemini|codex|devin|aider|windsurf|codeium|\\bai agent\\b|\\[bot\\]");

    private static final Pattern CO_AUTHOR = Pattern.compile("(?im)^\\s*co-authored-by:\\s*(.+)$");

    private static final long SECONDS = 120;

    private final PlanLedger plans;

    /** The engine's tools, which a commit with recheck runs a plan's open checks through; answers null where no tools are wired. */
    private final java.util.function.Supplier<? extends org.jawata.mcp.engine.ToolCalls> engineTools;

    public GitTool(PlanLedger plans) {
        this(plans, () -> null);
    }

    public GitTool(PlanLedger plans, java.util.function.Supplier<? extends org.jawata.mcp.engine.ToolCalls> engineTools) {
        this.engineTools = engineTools;
        this.plans = plans;
    }

    @Override
    public String getName() {
        return "git";
    }

    @Override
    public String getDescription() {
        return """
            Commit a step that passed its checks, or release on the person's release word. No other git write.

            USAGE: git(action="commit",  folder="/abs/repo", planId="<the hire's plan id>", message="...")
                   git(action="release", folder="/abs/repo", tag="v1.2.3", notes="..." [, publish=true])

            commit: only when every check of that hire's plan passed, and only the files the hire reported changed that it is also recorded
            as writing (its jawata calls and its brain's editor, or those of a hire under it); any other change stays uncommitted, one
            another hire wrote listed under leftOut and one nobody claims (a shell's writes) under unclaimed. One that another hire still
            running also wrote refuses the commit. The author is the repository's own configured person. A refusal names each check that
            did not pass; recheck=true runs the open checks again on the files as they stand and commits when they pass.
            release: only when the person's own last message is the order, "/release" (with a tag or notes after it) or the word "release"
            alone (at most with "please", "it" or "now"), never a sentence that only mentions a release; refused while any unpushed commit names an AI agent as author
            or co-author. Pushes the branch to its upstream, makes the annotated tag and pushes it, and with publish (default true) publishes
            the GitHub release marked latest through gh.
            Both refuse a message naming an AI agent as co-author.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of("type", "string", "enum", List.of("commit", "release"), "description", "What to do."));
        properties.put("folder", Map.of("type", "string", "description", "The repository, or a folder inside it, as an absolute path."));
        properties.put("planId", Map.of("type", "string", "description", "commit: the plan id of the hire whose step is committed."));
        properties.put("message", Map.of("type", "string", "description", "commit: the commit message."));
        properties.put("recheck", Map.of("type", "boolean", "description",
            "commit: when checks of the plan did not pass, run those checks again on the files as they stand; commit if they all pass."));
        properties.put("tag", Map.of("type", "string", "description", "release: the tag to make, such as v1.2.3."));
        properties.put("notes", Map.of("type", "string", "description", "release: the release notes, also the tag's message."));
        properties.put("publish", Map.of("type", "boolean", "description", "release: publish the GitHub release through gh (default true)."));
        return Map.of("type", "object", "properties", properties, "required", List.of("action", "folder"));
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        String folder = arguments.path("folder").asText("");
        if (folder.isBlank() || !Files.isDirectory(Path.of(folder))) {
            return ToolResponse.invalidParameter("folder", "the repository folder is needed and must exist: '" + folder + "'");
        }
        try {
            return switch (arguments.path("action").asText("")) {
                case "commit" -> commit(Path.of(folder), arguments);
                case "release" -> release(Path.of(folder), arguments);
                default -> ToolResponse.invalidParameter("action", "action must be commit or release");
            };
        } catch (IOException e) {
            return ToolResponse.error("GIT_FAILED", e.getMessage(), "Nothing after the failing git command was run.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResponse.error("INTERRUPTED", "the git command was interrupted", null);
        }
    }

    private ToolResponse commit(Path folder, JsonNode arguments) throws IOException, InterruptedException {
        String planId = arguments.path("planId").asText("");
        String message = arguments.path("message").asText("");
        if (planId.isBlank() || message.isBlank()) {
            return ToolResponse.invalidParameter(planId.isBlank() ? "planId" : "message", "a commit needs the hire's plan id and a message");
        }
        Optional<String> attributed = agentIn(message);
        if (attributed.isPresent()) {
            return refused("AGENT_ATTRIBUTION", "the message names an AI agent as co-author: " + attributed.get());
        }
        Optional<String> author = agentAuthor(folder);
        if (author.isPresent()) {
            return refused("AGENT_ATTRIBUTION", "the repository's configured author is an AI agent: " + author.get());
        }
        Optional<PlanLedger.PlanView> plan = plans.view().stream().filter(view -> view.id().toString().equals(planId)).findFirst();
        if (plan.isEmpty()) {
            return refused("NO_SUCH_PLAN", "no plan " + planId + " is held by this engine, so whether its checks passed cannot be read");
        }
        // passedStep is the index of the last step whose gate passed, -1 for none, so every step has passed at the last index
        int passed = plan.get().passedStep() + 1;
        Optional<JsonNode> answer = HireRegistry.shared().answerOfPlan(planId);
        if (passed < plan.get().steps().size()) {
            // 28h z103, z125: the refusal names what did not pass, and recheck runs the open checks on the files as they stand.
            boolean recheck = arguments.path("recheck").asBoolean(false);
            List<String> open = recheck ? stillFailing(plan.get(), passed) : List.of(lastFailure(plan.get(), passed, answer));
            if (!open.isEmpty()) {
                return refused("CHECKS_NOT_PASSED", "the step's checks have not all passed (" + passed + " of " + plan.get().steps().size()
                    + "), so it is not committed. " + String.join("; ", open)
                    + (recheck ? "" : ". commit with recheck=true runs the open checks again on the files as they stand"));
            }
        }
        if (answer.isEmpty() || !answer.get().path("changedFiles").isArray()) {
            return refused("CHANGES_UNKNOWN", "the files plan " + planId + " produced are not known to this engine, so nothing is committed");
        }
        List<String> files = new ArrayList<>();
        answer.get().path("changedFiles").forEach(file -> files.add(file.asText()));
        if (files.isEmpty()) {
            return refused("NOTHING_PRODUCED", "plan " + planId + " changed no file, so there is nothing to commit");
        }
        Path top = Path.of(git(folder, "rev-parse", "--show-toplevel"));
        // 28h z120: the change set is what changed in the tree while the hire ran, which holds what hires beside it wrote. A commit takes only
        // what this hire and those under it are recorded as writing; another hire's file stays out, and one nobody claims is listed.
        List<String> own = new ArrayList<>();
        Map<String, String> leftOut = new LinkedHashMap<>();
        List<String> unclaimed = new ArrayList<>();
        for (String file : files) {
            java.util.Set<String> writers = org.jawata.mcp.execution.WrittenFiles.shared().writersOf(top, file);
            List<String> others = writers.stream().filter(writer -> !HireRegistry.shared().isOfAccount(writer, planId)).toList();
            if (writers.isEmpty()) {
                unclaimed.add(file);
            } else if (others.isEmpty()) {
                own.add(file);
            } else if (others.size() == writers.size()) {
                leftOut.put(file, "written by " + String.join(", ", others.stream().map(HireRegistry.shared()::labelOf).toList()));
            } else {
                Optional<String> working = others.stream().filter(HireRegistry.shared()::isRunningPlan).findFirst();
                if (working.isPresent()) {
                    return refused("WRITTEN_BY_ANOTHER_HIRE", file + " was also written by " + HireRegistry.shared().labelOf(working.get())
                        + ", which still runs, so nothing is committed; commit when it has ended");
                }
                own.add(file);
            }
        }
        if (own.isEmpty()) {
            return refused("NOTHING_PRODUCED", "no changed file is recorded as written by plan " + planId + " or a hire under it, so nothing is committed."
                + (leftOut.isEmpty() ? "" : " Written by other hires: " + leftOut + ".")
                + (unclaimed.isEmpty() ? "" : " Claimed by nobody (a shell, the person or the main chat wrote them, or the engine restarted): " + unclaimed));
        }
        List<String> add = new ArrayList<>(List.of("add", "--"));
        add.addAll(own);
        git(top, add.toArray(String[]::new));
        List<String> commit = new ArrayList<>(List.of("commit", "-q", "-m", message, "--"));
        commit.addAll(own);
        git(top, commit.toArray(String[]::new));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("commit", git(top, "rev-parse", "HEAD"));
        data.put("files", own);
        if (!leftOut.isEmpty()) {
            data.put("leftOut", leftOut);
        }
        if (!unclaimed.isEmpty()) {
            data.put("unclaimed", unclaimed);
        }
        return ToolResponse.success(data);
    }

    /** The first check that did not pass, as the hire's own record has it: its step, tool, arguments and why. */
    private static String lastFailure(PlanLedger.PlanView plan, int index, Optional<JsonNode> answer) {
        GatedStep step = plan.steps().get(index);
        String why = "it has not been run";
        for (JsonNode gate : answer.map(a -> a.path("gates")).orElse(com.fasterxml.jackson.databind.node.MissingNode.getInstance())) {
            if (!step.tool().equals(gate.path("tool").asText())) {
                continue;
            }
            if (!gate.path("answered").asBoolean(false)) {
                why = "it did not answer: " + gate.path("notAnsweredBecause").asText("no reason given");
            } else if (gate.path("errorCount").asInt() > 0) {
                why = "it reported " + gate.path("errorCount").asInt() + " error(s)";
            }
        }
        return "Check " + (index + 1) + ", " + step.tool() + " " + step.gateArguments() + ": " + why;
    }

    /** Runs the plan's open checks again, in order, and names each that still does not pass; none means the files as they stand pass them. */
    private List<String> stillFailing(PlanLedger.PlanView plan, int from) {
        org.jawata.mcp.engine.ToolCalls tools = engineTools.get();
        List<String> open = new ArrayList<>();
        for (int index = from; index < plan.steps().size(); index++) {
            GatedStep step = plan.steps().get(index);
            String name = "Check " + (index + 1) + ", " + step.tool() + " " + step.gateArguments() + ": ";
            if (tools == null || step.tool().startsWith(PlanLedger.ANSWER_GATE_PREFIX)) {
                open.add(name + "it cannot be run again here");
                continue;
            }
            org.jawata.mcp.step.GateOutcome outcome = new ToolGateRunner(tools).run(step);
            if (!outcome.succeeded()) {
                open.add(name + "re-run did not answer: " + outcome.notAnsweredBecause());
            } else if (outcome.errorCount() > 0) {
                open.add(name + "re-run reported " + outcome.errorCount() + " error(s)");
            }
        }
        return open;
    }

    private ToolResponse release(Path folder, JsonNode arguments) throws IOException, InterruptedException {
        String tag = arguments.path("tag").asText("");
        String notes = arguments.path("notes").asText("");
        if (tag.isBlank() || notes.isBlank()) {
            return ToolResponse.invalidParameter(tag.isBlank() ? "tag" : "notes", "a release needs a tag and its notes");
        }
        String caller = CallerIdentity.serving().map(CallerIdentity::id).orElse("");
        if (!PersonsWords.gaveTheReleaseWord(caller)) {
            return refused("NO_RELEASE_WORD", "the person's last message is not the release order (\"/release\", or the word \"release\" alone, at most with \"please\", \"it\" or \"now\"): a release is his decision and is made only on his"
                + " word" + PersonsWords.lastSaid(caller).map(text -> " (his last message: \"" + clip(text) + "\")").orElse(" (no message of his is known)"));
        }
        String upstream = git(folder, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}");
        String unpushed = git(folder, "log", upstream + "..HEAD", "--format=%H%x1f%an <%ae>%x1f%B%x1e");
        List<String> attributed = new ArrayList<>();
        for (String entry : unpushed.split("\u001e")) {
            String[] parts = entry.strip().split("\u001f", 3);
            if (parts.length < 3) {
                continue;
            }
            boolean byAgent = AGENT.matcher(parts[1].toLowerCase(Locale.ROOT)).find() || agentIn(parts[2]).isPresent();
            if (byAgent) {
                attributed.add(parts[0].substring(0, Math.min(12, parts[0].length())));
            }
        }
        if (!attributed.isEmpty()) {
            return refused("AGENT_ATTRIBUTION", "unpushed commits name an AI agent as author or co-author: " + attributed
                + "; they are not pushed, and rewriting them is the person's decision");
        }
        String remote = upstream.contains("/") ? upstream.substring(0, upstream.indexOf('/')) : "origin";
        git(folder, "push", "-q");
        git(folder, "tag", "-a", tag, "-m", notes);
        git(folder, "push", "-q", remote, tag);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pushed", git(folder, "rev-parse", "HEAD"));
        data.put("tag", tag);
        if (arguments.path("publish").asBoolean(true)) {
            data.put("published", run(folder, List.of("gh", "release", "create", tag, "--title", tag, "--notes", notes, "--latest")));
        }
        return ToolResponse.success(data);
    }

    /** The co-author line naming an AI agent, if the message holds one. */
    static Optional<String> agentIn(String message) {
        var lines = CO_AUTHOR.matcher(message);
        while (lines.find()) {
            if (AGENT.matcher(lines.group(1).toLowerCase(Locale.ROOT)).find()) {
                return Optional.of(lines.group(1).strip());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> agentAuthor(Path folder) throws IOException, InterruptedException {
        String who = git(folder, "var", "GIT_AUTHOR_IDENT");
        return AGENT.matcher(who.toLowerCase(Locale.ROOT)).find() ? Optional.of(who) : Optional.empty();
    }

    private static ToolResponse refused(String code, String why) {
        return ToolResponse.error(code, why, "Nothing was committed, pushed or tagged.");
    }

    private static String clip(String text) {
        String line = text.strip().replace('\n', ' ');
        return line.length() > 120 ? line.substring(0, 120) + "…" : line;
    }

    private static String git(Path folder, String... command) throws IOException, InterruptedException {
        List<String> line = new ArrayList<>(List.of("git", "-C", folder.toString()));
        line.addAll(List.of(command));
        return run(folder, line);
    }

    private static String run(Path folder, List<String> command) throws IOException, InterruptedException {
        // the host's process boundary starts every program the engine runs (HostBoundaryRulesTest)
        HostProcessOutcome ran = HostProcesses.system().run(HostCommand.of(command).in(folder).waitingAtMost(Duration.ofSeconds(SECONDS)));
        String text = ran.output().strip();
        if (ran instanceof HostProcessOutcome.TimedOut) {
            throw new IOException(String.join(" ", command) + " did not end within " + SECONDS + " s");
        }
        if (!ran.succeeded()) {
            throw new IOException(String.join(" ", command) + " failed (" + ran.describe() + "): " + text);
        }
        return text;
    }
}
