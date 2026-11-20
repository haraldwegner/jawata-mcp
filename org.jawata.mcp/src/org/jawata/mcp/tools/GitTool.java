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
        "claude|anthropic|cursor|copilot|openai|chatgpt|\\bgpt-|gemini|codex|devin|aider|windsurf|codeium|\\bai agent\\b|noreply@github\\.com.*\\[bot\\]");

    private static final Pattern CO_AUTHOR = Pattern.compile("(?im)^\\s*co-authored-by:\\s*(.+)$");

    private static final long SECONDS = 120;

    private final PlanLedger plans;

    public GitTool(PlanLedger plans) {
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

            commit: only when every check of that hire's plan passed, and only the files the hire reported changed (any other change in the
            tree stays uncommitted); the author is the repository's own configured person.
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
        if (passed < plan.get().steps().size()) {
            return refused("CHECKS_NOT_PASSED", "the step's checks have not all passed (" + passed + " of " + plan.get().steps().size()
                + "), so it is not committed");
        }
        Optional<JsonNode> answer = HireRegistry.shared().answerOfPlan(planId);
        if (answer.isEmpty() || !answer.get().path("changedFiles").isArray()) {
            return refused("CHANGES_UNKNOWN", "the files plan " + planId + " produced are not known to this engine, so nothing is committed");
        }
        List<String> files = new ArrayList<>();
        answer.get().path("changedFiles").forEach(file -> files.add(file.asText()));
        if (files.isEmpty()) {
            return refused("NOTHING_PRODUCED", "plan " + planId + " changed no file, so there is nothing to commit");
        }
        Path top = Path.of(git(folder, "rev-parse", "--show-toplevel"));
        List<String> add = new ArrayList<>(List.of("add", "--"));
        add.addAll(files);
        git(top, add.toArray(String[]::new));
        List<String> commit = new ArrayList<>(List.of("commit", "-q", "-m", message, "--"));
        commit.addAll(files);
        git(top, commit.toArray(String[]::new));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("commit", git(top, "rev-parse", "HEAD"));
        data.put("files", files);
        return ToolResponse.success(data);
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
