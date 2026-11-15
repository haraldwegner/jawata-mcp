package org.jawata.mcp.execution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostProcessOutcome;
import org.jawata.core.host.HostProcesses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Which brains this machine actually has, asked of each agent program itself (Sprint 28k, Amendment 3, Stage 25e), so that an engine inside
 * Eclipse answers what the studio's {@code brains.rs} answers without the studio. Nothing here is a list somebody typed: is the program
 * installed (it answers {@code --version}), is it logged in (its own status command), and which models does it name. A program that cannot
 * answer is reported as unable to, never given a guessed answer. jawata never handles a credential: a brain that is not logged in is
 * reported with the program's own words and the command to run in a terminal.
 */
public final class BrainRoster {

    /** Whether a program says its account is logged in. */
    public enum Login { LOGGED_IN, NOT_LOGGED_IN, UNKNOWN }

    /**
     * One agent program, as this machine has it now.
     *
     * @param id                      the brain's name
     * @param label                   the name a person reads
     * @param installed               whether the program answered {@code --version}
     * @param version                 the first line it printed for that
     * @param login                   whether it says it is logged in
     * @param reason                  its own words when it is not, or why the check could not answer
     * @param loginCommand            what to type in a terminal to log in
     * @param models                  the models it names for itself; absent where it names none, which is not an empty list
     * @param hirable                 whether a hire can start this brain
     * @param takesModel              whether a hire passes this brain a model
     * @param writesHeldByInstruction whether its writes are held only by an instruction to the model
     */
    public record Status(String id, String label, boolean installed, Optional<String> version, Login login, Optional<String> reason, String loginCommand,
        Optional<List<String>> models, boolean hirable, boolean takesModel, boolean writesHeldByInstruction) {
    }

    /**
     * What one test run through a brain showed.
     *
     * @param brain        the brain
     * @param model        the model asked for
     * @param ok           whether it answered
     * @param answer       what it answered
     * @param modelsUsed   the models the run reported using, most expensive first; empty where the brain reports none
     * @param costUsd      the cost it reported, where it reports one
     * @param seconds      how long the run took
     * @param error        its own words when the run failed
     * @param loginCommand what to type in a terminal to log in
     */
    public record Probe(String brain, Optional<String> model, boolean ok, Optional<String> answer, List<String> modelsUsed, Optional<Double> costUsd,
        double seconds, Optional<String> error, String loginCommand) {
    }

    /**
     * What the three agent settings would be on this machine.
     *
     * @param settings the values by setting name, empty or exactly {@code jawata.agent.brain}, {@code .hireBrains} and {@code .reviewer}
     * @param note     why nothing can be set, when nothing can
     */
    public record AgentSettings(Map<String, String> settings, Optional<String> note) {
    }

    private record Spec(String id, String label, String program, String loginCommand) {
    }

    private record Outcome(boolean success, String text) {
    }

    private static final List<Spec> SPECS = List.of(new Spec("claude-code", "Claude Code", "claude", "claude auth login"),
        new Spec("codex", "Codex", "codex", "codex login"), new Spec("cursor", "Cursor", "cursor-agent", "cursor-agent login"),
        new Spec("copilot", "Copilot", "copilot", "copilot login"), new Spec("grok", "Grok", "grok", "grok login"));
    /** What a hire can start and which of those take a model are the engine's own answers, not a second list. */
    private static final List<String> HIRABLE = Arrays.stream(SeatHire.Brain.values()).filter(brain -> brain != SeatHire.Brain.SCRIPT).map(SeatHire.Brain::label).toList();
    private static final List<String> TAKES_MODEL = Arrays.stream(SeatHire.Brain.values()).filter(SeatHire.Brain::takesAModel).map(SeatHire.Brain::label).toList();
    /** Each dialect holds this answer, which needs a running resident's address to ask; BrainRosterTest pins this line to it. */
    private static final List<String> HELD_BY_AN_INSTRUCTION = List.of("cursor");
    private static final ExecutorService WORK = Executors.newVirtualThreadPerTaskExecutor();
    /** The one place a process is launched and an executable is spelled for this operating system. */
    private static final HostProcesses HOST = HostProcesses.system();
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(120);
    private static final String PROBE_PROMPT = "Reply with the single word OK.";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String path;
    private final Duration checkTimeout;

    /**
     * A roster that looks for programs on the given path.
     *
     * @param path the value of the program search path, as {@code PATH} holds it; null when it is not set
     */
    public BrainRoster(String path) {
        this(path, CHECK_TIMEOUT);
    }

    /** A roster whose status checks give up sooner, for a test of a program that hangs. */
    BrainRoster(String path, Duration checkTimeout) {
        this.path = path;
        this.checkTimeout = checkTimeout;
    }

    /** A roster over this process's own {@code PATH}. */
    public static BrainRoster onThisMachine() {
        return new BrainRoster(System.getenv("PATH"));
    }

    /** Every known brain, checked in parallel. */
    public List<Status> statuses() {
        List<CompletableFuture<Status>> checks = SPECS.stream().map(spec -> CompletableFuture.supplyAsync(() -> check(spec), WORK)).toList();
        return checks.stream().map(CompletableFuture::join).toList();
    }

    private Status check(Spec spec) {
        boolean hirable = HIRABLE.contains(spec.id());
        boolean takesModel = TAKES_MODEL.contains(spec.id());
        boolean held = HELD_BY_AN_INSTRUCTION.contains(spec.id());
        Optional<Outcome> version = run(spec.program(), List.of("--version"), null, checkTimeout);
        if (version.isEmpty()) {
            return new Status(spec.id(), spec.label(), false, Optional.empty(), Login.UNKNOWN, Optional.of("`" + spec.program() + "` is not installed on this machine."),
                spec.loginCommand(), Optional.empty(), hirable, takesModel, held);
        }
        Login login = Login.UNKNOWN;
        Optional<String> reason = Optional.empty();
        Optional<List<String>> models = Optional.empty();
        switch (spec.id()) {
            case "claude-code" -> {
                Optional<Outcome> answer = run(spec.program(), List.of("auth", "status"), null, checkTimeout);
                if (answer.isPresent()) {
                    Optional<Boolean> loggedIn = parsed(answer.get().text()).map(tree -> tree.get("loggedIn")).filter(JsonNode::isBoolean).map(JsonNode::asBoolean);
                    login = loggedIn.map(yes -> yes ? Login.LOGGED_IN : Login.NOT_LOGGED_IN).orElse(Login.UNKNOWN);
                    reason = loggedIn.isEmpty() ? firstLine(answer.get().text()) : loggedIn.get() ? Optional.empty() : Optional.of("Claude Code says it is not logged in.");
                    models = run(spec.program(), List.of("--help"), null, checkTimeout).map(help -> claudeModelNames(help.text())).filter(names -> !names.isEmpty());
                }
            }
            case "codex" -> {
                Optional<Outcome> answer = run(spec.program(), List.of("login", "status"), null, checkTimeout);
                if (answer.isPresent()) {
                    boolean in = answer.get().text().lines().anyMatch(line -> line.stripLeading().startsWith("Logged in"));
                    login = in ? Login.LOGGED_IN : Login.NOT_LOGGED_IN;
                    reason = in ? Optional.empty() : firstLine(answer.get().text());
                }
            }
            case "cursor" -> {
                Optional<Outcome> answer = run(spec.program(), List.of("models"), null, checkTimeout);
                if (answer.isPresent()) {
                    boolean in = answer.get().success() && !answer.get().text().contains("Authentication required");
                    login = in ? Login.LOGGED_IN : Login.NOT_LOGGED_IN;
                    reason = in ? Optional.empty() : firstLine(answer.get().text());
                }
            }
            case "grok" -> {
                Optional<Outcome> answer = run(spec.program(), List.of("models"), null, checkTimeout);
                if (answer.isPresent() && answer.get().text().contains("You are logged in")) {
                    login = Login.LOGGED_IN;
                    models = Optional.of(answer.get().text().lines().map(String::strip).filter(line -> line.startsWith("* ") || line.startsWith("- "))
                        .map(line -> line.substring(2).replace("(default)", "").strip()).filter(name -> !name.isEmpty()).toList());
                } else if (answer.isPresent()) {
                    login = Login.NOT_LOGGED_IN;
                    reason = firstLine(answer.get().text());
                }
            }
            default -> reason = Optional.of(spec.label() + " has no command that reports its login without starting a session.");
        }
        return new Status(spec.id(), spec.label(), true, firstLine(version.get().text()), login, reason, spec.loginCommand(), models, hirable, takesModel, held);
    }

    /** The model names Claude Code's own {@code --help} gives for {@code --model}: the quoted single words in that option's description. */
    private static List<String> claudeModelNames(String help) {
        StringBuilder block = new StringBuilder();
        boolean inside = false;
        for (String line : help.split("\n")) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("--model ")) {
                inside = true;
            } else if (inside && trimmed.startsWith("-")) {
                break;
            }
            if (inside) {
                block.append(trimmed).append(' ');
            }
        }
        List<String> names = new ArrayList<>();
        int i = 0;
        while (i < block.length()) {
            if (block.charAt(i) == '\'') {
                int close = block.indexOf("'", i + 1);
                if (close > 0) {
                    String name = block.substring(i + 1, close);
                    if (!name.isEmpty() && name.chars().allMatch(c -> Character.isLetterOrDigit(c) && c < 128 || "-._[]".indexOf(c) >= 0)) {
                        if (!names.contains(name)) {
                            names.add(name);
                        }
                        i = close + 1;
                        continue;
                    }
                }
            }
            i++;
        }
        return names;
    }

    /**
     * One test run: the brain answers one word, in an empty folder, as cheaply as each program allows. Its cost lands on the account the
     * program is logged into.
     *
     * @param brain the brain
     * @param model the model to ask for, or null for the brain's own
     * @return what the run showed; a refusal to run is reported through {@link IllegalArgumentException}
     * @throws IllegalArgumentException when no such brain exists, a hire cannot start it, or it takes no model and one was named
     */
    public Probe probe(String brain, String model) {
        Spec spec = SPECS.stream().filter(s -> s.id().equals(brain)).findFirst().orElseThrow(() -> new IllegalArgumentException("no brain named '" + brain + "'"));
        if (!HIRABLE.contains(brain)) {
            throw new IllegalArgumentException("'" + brain + "' is not a brain a hire can start");
        }
        String wanted = model == null || model.isBlank() ? null : model;
        if (wanted != null && !TAKES_MODEL.contains(brain)) {
            throw new IllegalArgumentException(spec.label() + " chooses its own model; a hire cannot pass one to it");
        }
        List<String> args = probeArguments(brain, wanted);
        long started = System.nanoTime();
        Path folder;
        try {
            folder = Files.createTempDirectory("jawata-brain-probe-");
        } catch (IOException cannot) {
            throw new IllegalStateException("cannot create a folder for the test run: " + cannot.getMessage(), cannot);
        }
        Optional<Outcome> outcome;
        try {
            outcome = run(spec.program(), args, folder, PROBE_TIMEOUT);
        } finally {
            remove(folder);
        }
        double seconds = (System.nanoTime() - started) / 1e9;
        if (outcome.isEmpty()) {
            return new Probe(brain, Optional.ofNullable(wanted), false, Optional.empty(), List.of(), Optional.empty(), seconds,
                Optional.of("`" + spec.program() + "` is not installed on this machine."), spec.loginCommand());
        }
        return interpreted(brain, wanted, outcome.get(), seconds, spec.loginCommand());
    }

    /**
     * DELIBERATE DUPLICATE of what each {@link BrainDialect} knows about starting its program: a hire's command gives the brain tools and the
     * resident's address, and this one exists to take them away, because the same Claude run with its tools costs thirty times as much. A flag a
     * dialect changes is not followed here, and the test of this list is what shows it.
     */
    private static List<String> probeArguments(String brain, String model) {
        List<String> args = new ArrayList<>(switch (brain) {
            case "claude-code" -> List.of("-p", PROBE_PROMPT, "--output-format", "json", "--max-turns", "1", "--no-session-persistence", "--strict-mcp-config",
                "--setting-sources", "project", "--system-prompt", PROBE_PROMPT, "--tools", "");
            case "codex" -> List.of("exec", "--json", "--sandbox", "read-only", "--skip-git-repo-check", PROBE_PROMPT);
            case "cursor" -> List.of("--print", "--output-format", "json", "--trust", "--mode", "plan");
            default -> List.of("-p", PROBE_PROMPT, "--output-format", "json", "--disable-builtin-mcps");
        });
        if (model != null) {
            args.add("--model");
            args.add(model);
        }
        if ("cursor".equals(brain)) {
            args.add(PROBE_PROMPT);
        }
        return args;
    }

    private static Probe interpreted(String brain, String model, Outcome outcome, double seconds, String loginCommand) {
        String text = outcome.text();
        boolean ok = false;
        String answer = null;
        List<String> used = List.of();
        Double cost = null;
        String error = null;
        switch (brain) {
            case "claude-code" -> {
                Optional<JsonNode> result = parsed(text.strip());
                if (result.isEmpty()) {
                    error = firstLine(text).orElse(null);
                } else {
                    JsonNode tree = result.get();
                    answer = textOf(tree.get("result"));
                    ok = tree.path("is_error").isBoolean() && !tree.path("is_error").asBoolean() && answer != null;
                    used = claudeModelsUsed(tree.path("modelUsage"));
                    cost = tree.path("total_cost_usd").isNumber() ? tree.path("total_cost_usd").asDouble() : null;
                    if (!ok) {
                        error = Optional.ofNullable(textOf(tree.get("result"))).or(() -> Optional.ofNullable(textOf(tree.get("subtype")))).orElse("Claude Code returned no answer");
                    }
                }
            }
            case "codex" -> {
                String failure = null;
                for (String line : text.split("\n")) {
                    Optional<JsonNode> event = parsed(line.strip());
                    if (event.isEmpty()) {
                        continue;
                    }
                    String type = event.get().path("type").asText("");
                    if ("item.completed".equals(type) && "agent_message".equals(event.get().path("item").path("type").asText())) {
                        answer = textOf(event.get().path("item").get("text"));
                    } else if ("error".equals(type) || "turn.failed".equals(type)) {
                        failure = Optional.ofNullable(textOf(event.get().get("message"))).orElseGet(() -> textOf(event.get().path("error").get("message")));
                    }
                }
                ok = answer != null && failure == null;
                if (!ok) {
                    error = Optional.ofNullable(failure).or(() -> firstLine(text)).orElse("Codex returned no answer");
                }
            }
            case "cursor" -> {
                if (!outcome.success() || text.contains("Authentication required")) {
                    error = firstLine(text).orElse(null);
                } else {
                    answer = parsed(text.strip()).map(tree -> textOf(tree.get("result"))).or(() -> firstLine(text)).orElse(null);
                    ok = answer != null;
                }
            }
            default -> {
                String failure = null;
                String named = null;
                for (String line : text.split("\n")) {
                    Optional<JsonNode> event = parsed(line.strip());
                    if (event.isEmpty()) {
                        continue;
                    }
                    String kind = event.get().path("type").asText("");
                    if ("assistant.message".equals(kind)) {
                        answer = textOf(event.get().path("data").get("content"));
                        named = textOf(event.get().path("data").get("model"));
                    } else if (kind.contains("error")) {
                        failure = Optional.ofNullable(textOf(event.get().path("data").get("message"))).orElseGet(() -> textOf(event.get().path("data").get("error")));
                    }
                }
                ok = answer != null && failure == null;
                used = named == null ? List.of() : List.of(named);
                if (!ok) {
                    error = Optional.ofNullable(failure).or(() -> firstLine(text)).orElse("Copilot returned no answer");
                }
            }
        }
        return new Probe(brain, Optional.ofNullable(model), ok, Optional.ofNullable(answer), used, Optional.ofNullable(cost), seconds, Optional.ofNullable(error), loginCommand);
    }

    private static List<String> claudeModelsUsed(JsonNode usage) {
        if (!usage.isObject()) {
            return List.of();
        }
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        usage.fields().forEachRemaining(entries::add);
        entries.sort(Comparator.comparingDouble((Map.Entry<String, JsonNode> entry) -> entry.getValue().path("costUSD").asDouble(0)).reversed());
        return entries.stream().map(Map.Entry::getKey).toList();
    }

    /**
     * What this machine has for the three agent settings: when Cursor's agent program is on the path, all three name it with the argument
     * {@code acp}; when it is not, nothing is set and the note says why. A credential is never written: the program uses its own login.
     *
     * @return the settings, or the reason for none
     */
    public AgentSettings agentSettings() {
        if (path == null) {
            return new AgentSettings(Map.of(), Optional.of("no agent brain was configured: PATH is not set, so `cursor-agent` could not be looked for"));
        }
        Optional<Path> found = locate("agent", true).or(() -> locate("cursor-agent", true));
        if (found.isEmpty()) {
            return new AgentSettings(Map.of(), Optional.of("no agent brain was configured: Cursor's agent program is not installed on this machine"));
        }
        String one = JSON.createObjectNode().<com.fasterxml.jackson.databind.node.ObjectNode>set("command", JSON.createArrayNode().add(found.get().toString()).add("acp"))
            .<com.fasterxml.jackson.databind.node.ObjectNode>set("env", JSON.createObjectNode()).toString();
        String registry = "{\"cursor\":" + one + "}";
        return new AgentSettings(Map.of("jawata.agent.brain", one, "jawata.agent.hireBrains", registry, "jawata.agent.reviewer", one), Optional.empty());
    }

    /**
     * Finds a program on the path, in the order the path gives.
     *
     * @param name         the program
     * @param absoluteOnly true for a value that is written down and read later: a relative folder means something else from another directory.
     *                     A status check looks where the operating system would, a relative folder included.
     */
    private Optional<Path> locate(String name, boolean absoluteOnly) {
        if (path == null) {
            return Optional.empty();
        }
        List<String> files = HOST.executableCandidates(name);
        for (String entry : path.split(java.io.File.pathSeparator)) {
            Optional<Path> folder = folderOf(entry).filter(found -> !absoluteOnly || found.isAbsolute());
            for (String file : folder.isEmpty() ? List.<String>of() : files) {
                Path candidate = folder.get().resolve(file);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(candidate.toAbsolutePath());
                }
            }
        }
        return Optional.empty();
    }

    /** One entry of the path as a folder; empty for a blank entry or one no path can be made of, such as a quoted entry on Windows. */
    private static Optional<Path> folderOf(String entry) {
        if (entry.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(entry));
        } catch (InvalidPathException illegal) {
            return Optional.empty();
        }
    }

    /** Runs one command. Empty means the program could not be started: it is not installed. */
    private Optional<Outcome> run(String program, List<String> args, Path folder, Duration timeout) {
        Optional<Path> found = locate(program, false);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        List<String> command = new ArrayList<>();
        command.add(found.get().toString());
        command.addAll(args);
        // the host boundary launches, gives the program no input, kills it and what it started at the timeout, and reads
        // its output on its own thread; stderr is merged, as the program's own words are on either stream
        HostCommand launch = HostCommand.of(command).waitingAtMost(timeout);
        HostProcessOutcome outcome;
        try {
            outcome = HOST.run(folder == null ? launch : launch.in(folder));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.of(new Outcome(false, ""));
        }
        if (outcome instanceof HostProcessOutcome.CannotLaunch) {
            return Optional.empty();
        }
        return Optional.of(new Outcome(outcome.succeeded(), outcome.output()));
    }

    private static void remove(Path folder) {
        try (Stream<Path> files = Files.walk(folder)) {
            files.sorted(Comparator.reverseOrder()).forEach(file -> file.toFile().delete());
        } catch (IOException ignored) {
            // the folder is empty or gone; nothing holds on to it
        }
    }

    private static Optional<JsonNode> parsed(String text) {
        try {
            JsonNode tree = JSON.readTree(text);
            return tree != null && tree.isObject() ? Optional.of(tree) : Optional.empty();
        } catch (IOException notJson) {
            return Optional.empty();
        }
    }

    private static String textOf(JsonNode node) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText().strip();
    }

    private static Optional<String> firstLine(String text) {
        return text.lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst();
    }
}
