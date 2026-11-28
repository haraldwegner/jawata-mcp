package org.jawata.mcp.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostProcessOutcome;
import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.access.TestCommands;
import org.jawata.mcp.execution.HireRegistry;
import org.jawata.mcp.execution.Hirer;
import org.jawata.mcp.execution.MachineMemory;
import org.jawata.mcp.execution.SeatTree;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A hired worker's shell (28h z31, ruled 2026-11-27: a worker does not run a shell itself; it hands over the command and jawata runs it). So
 * jawata sees who runs what: main and the leads have no shell, and a command that runs more than a single test is the test staff's (z27).
 * It is no sandbox: a command can do what the person can.
 */
public final class ShellTool implements Tool {

    private static final int DEFAULT_SECONDS = 120;
    private static final int MOST_SECONDS = 600;
    private static final int LARGEST_ANSWER = 30_000;
    /** As for a hire (z29): under this much free memory no run of many tests is started. */
    private static final long LEAST_FREE_MB = 1024;

    private final Supplier<OptionalLong> freeMemoryMb;

    public ShellTool() {
        this(MachineMemory::availableMb);
    }

    ShellTool(Supplier<OptionalLong> freeMemoryMb) {
        this.freeMemoryMb = freeMemoryMb;
    }

    @Override
    public String getName() {
        return "shell";
    }

    @Override
    public String getDescription() {
        return """
            Runs a command line in a folder (bash; cmd on Windows) and answers {exitCode, output, truncated, seconds}.
            A command that runs more than a single test is the test staff's; run one test, or ask the test-manager.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("command", Map.of("type", "string", "description", "The command line."));
        properties.put("folder", Map.of("type", "string", "description", "Absolute folder it runs in."));
        properties.put("timeoutSeconds", Map.of("type", "integer", "description", "Time limit, default 120, at most 600."));
        return Map.of("type", "object", "properties", properties, "required", List.of("command", "folder"));
    }

    @Override
    public boolean changesCode() {
        return true;
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        String command = arguments.path("command").asText("");
        if (command.isBlank()) {
            return ToolResponse.invalidParameter("command", "a command line is needed");
        }
        String folder = arguments.path("folder").asText("");
        if (folder.isBlank() || !Path.of(folder).isAbsolute() || !Files.isDirectory(Path.of(folder))) {
            return ToolResponse.invalidParameter("folder", "an existing folder, as an absolute path, is needed: '" + folder + "'");
        }
        Optional<String> seat = CallerIdentity.serving().flatMap(caller -> HireRegistry.shared().seatOfCaller(caller.id()));
        if (seat.filter(SeatTree.standard()::isLead).isPresent()) {
            return ToolResponse.error("SHELL_NOT_ALLOWED", seat.get() + " has no shell: it instructs and does not run",
                "hire a worker for what has to be run");
        }
        // a hired worker reads Java through jawata's tools, in this shell as in its own (the same rule the engine puts to its brain's shell)
        Optional<String> search = seat.isPresent() ? org.jawata.mcp.access.LookupDriver.refusal(arguments) : Optional.empty();
        if (search.isPresent()) {
            return ToolResponse.error("JAVA_BY_JAWATA", search.get(), "search_symbols, find_references, analyze, inspect");
        }
        Optional<String> write = seat.isPresent() ? org.jawata.mcp.access.JavaWrites.refusal(command) : Optional.empty();
        if (write.isPresent()) {
            return ToolResponse.error("JAVA_BY_JAWATA", write.get(), "rename_symbol, extract, move, refactoring");
        }
        Optional<String> record = seat.isPresent() ? org.jawata.mcp.access.GitCommands.refusal(command) : Optional.empty();
        if (record.isPresent()) {
            return ToolResponse.error("GIT_BY_JAWATA", record.get(), "report what you changed; the lead commits with the git tool");
        }
        if (TestCommands.runsMoreThanOneTest(command)) {
            // in a shell a full run is the tester's alone: the test writer runs the tests it wrote one at a time or through run_tests (z31)
            Optional<String> refused = seat.filter("test-writer"::equals)
                .map(writer -> writer + " does not start a full test run in a shell: the tester does")
                .or(() -> new Hirer(SeatTree.standard(), Map.of(), java.util.Set.of()).refusePackageRun(seat));
            if (refused.isPresent()) {
                return ToolResponse.error("PACKAGE_RUN_NOT_ALLOWED", refused.get(), "run the one test your change reaches, or ask the test-manager");
            }
            OptionalLong free = freeMemoryMb.get();
            if (free.isPresent() && free.getAsLong() < LEAST_FREE_MB) {
                return ToolResponse.error("TOO_LITTLE_MEMORY", "this machine has " + free.getAsLong() + " MB of memory available and a run of "
                    + "many tests is not started under " + LEAST_FREE_MB + " MB", "wait for what runs to end");
            }
        }
        return run(command, Path.of(folder), Math.max(1, Math.min(MOST_SECONDS, arguments.path("timeoutSeconds").asInt(DEFAULT_SECONDS))));
    }

    private static ToolResponse run(String command, Path folder, int seconds) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        // a login shell, so the build tools the person's shell finds are found (z21)
        List<String> argv = windows ? List.of("cmd.exe", "/c", command) : List.of("bash", "-lc", command);
        long started = System.nanoTime();
        try {
            HostProcessOutcome ran = HostProcesses.system().run(HostCommand.of(argv).in(folder).waitingAtMost(Duration.ofSeconds(seconds)));
            if (ran instanceof HostProcessOutcome.TimedOut) {
                return ToolResponse.error("TIMEOUT", "the command was ended after " + seconds + " s: " + end(ran.output(), 2_000),
                    "give timeoutSeconds, at most " + MOST_SECONDS);
            }
            if (!(ran instanceof HostProcessOutcome.Completed completed)) {
                return ToolResponse.error("CANNOT_LAUNCH", ran.describe(), null);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("exitCode", completed.exitCode());
            data.put("output", end(completed.output(), LARGEST_ANSWER));
            data.put("truncated", completed.output().length() > LARGEST_ANSWER);
            data.put("seconds", Duration.ofNanos(System.nanoTime() - started).toSeconds());
            return ToolResponse.success(data);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResponse.error("INTERRUPTED", "the command was interrupted", null);
        }
    }

    /** The end of an output, where a build or a test run says how it went. */
    private static String end(String output, int most) {
        return output.length() > most ? output.substring(output.length() - most) : output;
    }
}
