package org.jawata.mcp.tools;

import java.nio.file.Files;
import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostProcessOutcome;
import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The read-only lookups a brain without a shell still needs (Sprint 28h, D57 (j)): a repository's status, its history and the diff of its work,
 * and the processes running on the machine. Nothing here changes anything, so a seat that changes no code may carry it.
 */
public final class LookupTool implements Tool {

    private static final long SECONDS = 60;
    private static final int LARGEST_ANSWER = 200_000;

    @Override
    public String getName() {
        return "lookup";
    }

    @Override
    public String getDescription() {
        return """
            Read-only lookups: a git repository's status, history and diff, and the running processes. Changes nothing.

            USAGE: lookup(kind="git_status", folder="/abs/repo")
                   lookup(kind="git_log",    folder="/abs/repo" [, max=30])
                   lookup(kind="git_diff",   folder="/abs/repo" [, staged=true])
                   lookup(kind="processes")

            The git kinds answer {output, truncated}: git's own text (status as `git status --short --branch`, log one line per commit). An
            answer over 200,000 characters is cut and says so. processes answers {processes: [{pid, parent, command}]}.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("kind", Map.of("type", "string", "enum", List.of("git_status", "git_log", "git_diff", "processes"), "description", "What to look up."));
        properties.put("folder", Map.of("type", "string", "description", "git kinds: the repository, or a folder inside it, as an absolute path."));
        properties.put("max", Map.of("type", "integer", "description", "git_log: how many commits at most (default 30)."));
        properties.put("staged", Map.of("type", "boolean", "description", "git_diff: the staged change instead of the unstaged one."));
        return Map.of("type", "object", "properties", properties, "required", List.of("kind"));
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        String kind = arguments.path("kind").asText("");
        if ("processes".equals(kind)) {
            return processes();
        }
        List<String> command = switch (kind) {
            case "git_status" -> List.of("status", "--short", "--branch");
            case "git_log" -> List.of("log", "--oneline", "-n", String.valueOf(Math.max(1, arguments.path("max").asInt(30))));
            case "git_diff" -> arguments.path("staged").asBoolean(false) ? List.of("diff", "--staged") : List.of("diff");
            default -> null;
        };
        if (command == null) {
            return ToolResponse.invalidParameter("kind", "kind must be git_status, git_log, git_diff or processes, not '" + kind + "'");
        }
        String folder = arguments.path("folder").asText("");
        if (folder.isBlank() || !Files.isDirectory(Path.of(folder))) {
            return ToolResponse.invalidParameter("folder", "a git lookup needs the repository folder, and it must exist: '" + folder + "'");
        }
        try {
            List<String> line = new ArrayList<>(List.of("git", "--no-pager", "-C", folder));
            line.addAll(command);
            // the host's process boundary starts git, as it does every program the engine runs (HostBoundaryRulesTest)
            HostProcessOutcome ran = HostProcesses.system().run(HostCommand.of(line).waitingAtMost(Duration.ofSeconds(SECONDS)));
            String out = ran.output();
            if (ran instanceof HostProcessOutcome.TimedOut) {
                return ToolResponse.error("TIMEOUT", "git did not answer within " + SECONDS + " s", null);
            }
            if (ran instanceof HostProcessOutcome.CannotLaunch) {
                return ToolResponse.error("GIT_FAILED", ran.describe(), "Is git installed?");
            }
            if (!ran.succeeded()) {
                return ToolResponse.error("GIT_FAILED", out.strip(), "Is the folder inside a git repository?");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("output", out.length() > LARGEST_ANSWER ? out.substring(0, LARGEST_ANSWER) : out);
            data.put("truncated", out.length() > LARGEST_ANSWER);
            return ToolResponse.success(data);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResponse.error("INTERRUPTED", "the lookup was interrupted", null);
        }
    }

    private static ToolResponse processes() {
        List<Map<String, Object>> rows = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(process -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pid", process.pid());
            row.put("parent", process.parent().map(ProcessHandle::pid).orElse(null));
            row.put("command", process.info().commandLine().or(() -> process.info().command()).orElse(""));
            rows.add(row);
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("processes", rows);
        return ToolResponse.success(data);
    }
}
