package org.jawata.mcp.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.engine.EngineApi;
import org.jawata.mcp.execution.AcpBrainSession;
import org.jawata.mcp.execution.HireSite;
import org.jawata.mcp.execution.HiredBrain;
import org.jawata.mcp.execution.Hirer;
import org.jawata.mcp.execution.SeatTree;
import org.jawata.mcp.execution.SelfEndpoint;
import org.jawata.spi.host.McpEndpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What answers a host's prompt (Sprint 28h, S7a, D1): the principal seat, main, run in a hired brain through the engine's ACP client. The
 * prompt goes to the brain, the brain is given the engine as its one tool server and the allowlist the hirer builds for main, and what the
 * brain says reaches the host as it is said. Each answered prompt leaves a hire record; the brain's session id is kept, so the next prompt
 * of the same connection continues the same brain session.
 *
 * <p>The brain is named by one host setting, {@value #SETTING}: a JSON object {@code {"command":[...],"env":{...}}}, the command line the
 * brain's own installation defines. With none, the agent says so and stays silent otherwise, as before.</p>
 */
public final class PrincipalBrain implements AgentBrain {

    /** The host setting that names the brain to hire for the principal seat. */
    public static final String SETTING = "jawata.agent.brain";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One answered prompt, as the hire record says it.
     *
     * @param seat           the seat that ran
     * @param command        the program that was started
     * @param brainSessionId the brain's own session id, if it opened one
     * @param toolSteps      the tool calls the brain made
     * @param clean          whether the brain ended cleanly
     * @param stoppedBy      the ceiling that stopped it, if one did
     * @param elapsed        how long the prompt took
     */
    public record HireRecord(String seat, List<String> command, Optional<String> brainSessionId, int toolSteps, boolean clean,
            Optional<String> stoppedBy, Duration elapsed) {
    }

    private final HostProcesses host;
    private final Optional<AcpBrainSession.Program> program;
    private final HireSite site;
    private final SelfEndpoint self;
    private final List<String> engineTools;
    private final Hirer hirer;
    private final HiredBrain.Ceilings ceilings;
    private final List<HireRecord> records = new ArrayList<>();
    private volatile Optional<String> resume = Optional.empty();

    /**
     * The session id this brain presents to the resident on every tool call (Sprint 28h, S3, D30), so the resident keeps its calls in a
     * history of their own and not in the one shared by callers that send none. One per brain, so two connections do not share one.
     */
    private final String sessionKey = "principal-" + java.util.UUID.randomUUID();

    public PrincipalBrain(HostProcesses host, Optional<AcpBrainSession.Program> program, HireSite site, SelfEndpoint self,
            List<String> engineTools, Hirer hirer, HiredBrain.Ceilings ceilings) {
        this.host = host;
        this.program = program;
        this.site = site;
        this.self = self;
        this.engineTools = List.copyOf(engineTools);
        this.hirer = hirer;
        this.ceilings = ceilings;
    }

    /**
     * The principal brain for what the engine offers at this moment: the brain the host setting names, the engine's MCP address as its tool
     * server, and the engine's published tools. A connection opened later asks again, so a changed setting takes effect at the next
     * connection.
     */
    public static PrincipalBrain over(EngineApi engine, Path workingDirectory) {
        Optional<AcpBrainSession.Program> program = engine.settings().get(SETTING).flatMap(PrincipalBrain::parseProgram);
        SelfEndpoint self = engine.mcp().map(PrincipalBrain::endpointOf).orElse(null);
        List<Map<String, Object>> definitions = engine.tools().getToolDefinitions();
        List<String> tools = definitions.stream().map(d -> String.valueOf(d.get("name"))).toList();
        Hirer hirer = new Hirer(SeatTree.standard(), Map.of(), codeChangingTools(definitions));
        return new PrincipalBrain(HostProcesses.system(), program, HireSite.in(workingDirectory), self, tools, hirer,
            HiredBrain.Ceilings.of(Duration.ofMinutes(30)));
    }

    /**
     * The published tools that change code, read from the flag each tool publishes of itself ({@code _meta."jawata/changesCode"}, the
     * engine's own answer to "did this call change code?") instead of from a list kept here. The annotations cannot answer it:
     * {@code destructiveHint} is absent for {@code format}, {@code quick_fix}, {@code refactoring} and {@code dependency}, which change
     * code. The principal is a lead: it carries every other tool and none of these, so the code is changed by the seats that are allowed
     * to, never by the one that decides, and a tool added later is held back from it the day it says it changes code.
     */
    @SuppressWarnings("unchecked")
    static Set<String> codeChangingTools(List<Map<String, Object>> definitions) {
        Set<String> names = new java.util.TreeSet<>();
        for (Map<String, Object> definition : definitions) {
            Object meta = definition.get("_meta");
            if (meta instanceof Map<?, ?> published && Boolean.TRUE.equals(((Map<String, Object>) published).get("jawata/changesCode"))) {
                names.add(String.valueOf(definition.get("name")));
            }
        }
        return names;
    }

    private static SelfEndpoint endpointOf(McpEndpoint endpoint) {
        return new SelfEndpoint(endpoint.url().toString(), endpoint.key().orElse(""));
    }

    /** The program a setting names, or none when the text is not a command line (a setting that is wrong names no brain). */
    static Optional<AcpBrainSession.Program> parseProgram(String setting) {
        try {
            JsonNode root = JSON.readTree(setting);
            List<String> command = new ArrayList<>();
            root.path("command").forEach(part -> command.add(part.asText()));
            if (command.isEmpty()) {
                return Optional.empty();
            }
            Map<String, String> env = new LinkedHashMap<>();
            root.path("env").properties().forEach(entry -> env.put(entry.getKey(), entry.getValue().asText()));
            return Optional.of(new AcpBrainSession.Program(command, env));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void answer(String prompt, Consumer<String> chunks) {
        answer(prompt, chunks, null);
    }

    @Override
    public void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls) {
        if (program.isEmpty()) {
            chunks.accept(AgentFace.NO_BRAIN);
            return;
        }
        long started = System.nanoTime();
        boolean[] streamed = {false};
        HiredBrain.Outcome outcome;
        try {
            outcome = AcpBrainSession.run(host, program.get(), site, prompt, self == null ? null : self.forSession(sessionKey),
                hirer.allowlist("main", engineTools), resume, ceilings,
                piece -> {
                    streamed[0] = true;
                    chunks.accept(piece);
                }, toolCalls);
        } catch (IOException e) {
            chunks.accept("jawata's agent could not start its brain: " + e.getMessage());
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            chunks.accept("jawata's agent was interrupted while its brain was working.");
            return;
        }
        outcome.brainSessionId().ifPresent(id -> resume = Optional.of(id));
        if (!streamed[0] && !outcome.text().isBlank()) {
            chunks.accept(outcome.text());
        }
        synchronized (records) {
            records.add(new HireRecord("main", program.get().command(), outcome.brainSessionId(), outcome.toolSteps(), outcome.clean(),
                outcome.stoppedBy(), Duration.ofNanos(System.nanoTime() - started)));
        }
    }

    /** The hire records of the prompts answered so far, oldest first. */
    public List<HireRecord> records() {
        synchronized (records) {
            return List.copyOf(records);
        }
    }
}
