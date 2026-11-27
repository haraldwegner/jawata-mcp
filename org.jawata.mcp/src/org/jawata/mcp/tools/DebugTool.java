package org.jawata.mcp.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.DevSimPreset;
import org.jawata.mcp.runtime.JvmTargets;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
import org.jawata.mcp.runtime.RuntimeSession;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.runtime.debug.DebugController;
import org.jawata.mcp.runtime.debug.JdiValues;
import org.jawata.spi.host.DebugTurn;
import org.jawata.spi.host.HostRuns;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sprint 24 — the {@code debug} front door: one tool, the whole interactive
 * debugger behind it (Stage 7 opens the session; Stages 8–11 add breakpoints,
 * inspection, mutation, probes and replay).
 *
 * <p><b>What this can do to a JVM.</b> A debug session can suspend threads and
 * change state in the program it attaches to. It is meant for a development or
 * simulation machine. Attaching it to anything else — a shared test environment,
 * or worse — is the operator's professional judgment, not a thing jawata gates:
 * production runs no agent and exposes no debug channel, so the dangerous action
 * is not refused, it is unreachable. (D17 states this in the README too.)</p>
 */
public class DebugTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(DebugTool.class);

    /**
     * A debugger needs a JVM, not a loaded project (mcp#28).
     *
     * <p>{@code discover} lists the local JVMs and {@code attach} takes a pid;
     * neither reads the Java model. Refusing them on an empty or failed
     * workspace would remove the one tool family that still works when the
     * model does not.
     */
    @Override
    protected boolean requiresLoadedProject() {
        return false;
    }

    private static final List<String> ACTIONS = List.of(
        "discover", "launch", "attach", "status", "detach", "cancel",
        "preset_args",
        "breakpoint_set", "breakpoint_clear", "breakpoint_list",
        "wait", "threads", "snapshot", "evaluate", "step", "resume", "instances",
        "set_value", "force_return", "pop_frame", "redefine", "mutations",
        "probe_set", "probe_clear", "probe_list", "probe_read",
        "replay", "artifacts", "artifact_delete");

    private static final List<String> PROBE_KINDS =
        List.of("field_watch", "method_trace", "logpoint");

    private static final List<String> BREAKPOINT_KINDS = List.of(
        "line", "method", "conditional", "hit_count", "exception",
        "field_access", "field_write");

    /**
     * How long {@code wait} may hold the call open.
     *
     * <p><b>Blocking IS the wait primitive.</b> An agent is only ever driven by tool
     * results: nothing an MCP server pushes can re-invoke an agent that is not already
     * in a turn. So "notify me when it hits" cannot exist — the call that waits is the
     * call that returns the hit.</p>
     *
     * <p>The ceiling is set by the transport's idle window, not by us: an HTTP/SSE MCP
     * server may hold a call open for about five minutes before the client aborts it as
     * idle (stdio allows far longer). Four minutes leaves headroom. A wait that needs
     * longer simply calls again — <b>and loses nothing</b>, because the breakpoint keeps
     * the thread suspended until somebody resumes it. The program sits there waiting for
     * you, which is precisely what a notification could not promise.</p>
     */
    private static final long MAX_WAIT_MILLIS = 240_000;
    private static final long DEFAULT_WAIT_MILLIS = 30_000;

    private final RuntimeSessionRegistry sessions;
    private final RuntimeArtifactStore artifacts;
    /** Optional: when present, a hit RECALLS what is already known about its symbol (D15). */
    private final org.jawata.mcp.knowledge.ExperienceRetrieval experience;
    /** Whose turn it is to debug: when the person has a launch of their own, the agent neither starts nor attaches. */
    private final DebugTurn debugTurn;
    /** The host's own way of running a program for jawata, when it has one (contract §6.1). */
    private final Optional<HostRuns> hostRuns;

    public DebugTool(Supplier<IJdtService> serviceSupplier, RuntimeSessionRegistry sessions) {
        this(serviceSupplier, sessions,
            new RuntimeArtifactStore(org.jawata.mcp.engine.EngineEnvironment.directories().runtimeDir()), null, org.jawata.spi.host.DebugTurn.FREE);
    }

    public DebugTool(Supplier<IJdtService> serviceSupplier, RuntimeSessionRegistry sessions,
                     RuntimeArtifactStore artifacts) {
        this(serviceSupplier, sessions, artifacts, null, org.jawata.spi.host.DebugTurn.FREE);
    }

    public DebugTool(Supplier<IJdtService> serviceSupplier, RuntimeSessionRegistry sessions,
                     RuntimeArtifactStore artifacts,
                     org.jawata.mcp.knowledge.ExperienceRetrieval experience, DebugTurn debugTurn) {
        this(serviceSupplier, sessions, artifacts, experience, debugTurn, Optional.empty());
    }

    /**
     * @param hostRuns how the host starts a program from the person's run configurations ("Run for jawata"), or empty when the host runs
     *                 none and the agent starts a program from a main class and a classpath
     */
    public DebugTool(Supplier<IJdtService> serviceSupplier, RuntimeSessionRegistry sessions,
                     RuntimeArtifactStore artifacts,
                     org.jawata.mcp.knowledge.ExperienceRetrieval experience, DebugTurn debugTurn,
                     Optional<HostRuns> hostRuns) {
        super(serviceSupplier);
        this.sessions = sessions;
        this.artifacts = artifacts;
        this.experience = experience;
        this.debugTurn = debugTurn;
        this.hostRuns = hostRuns;
    }

    /**
     * The agent does not start or attach to a program while the person has a debug launch of their own: the person wins (contract A3-2),
     * and two debuggers on one program help nobody.
     *
     * @return the refusal, or null when it is the agent's turn
     */
    private ToolResponse refusedWhilePersonDebugs() {
        if (!debugTurn.personIsDebugging()) {
            return null;
        }
        // The person's own debugger reaches this tool as an editor caller (Stage 25, F2); the rule keeps the agent out of the person's turn,
        // and the person is not the agent.
        if (org.jawata.mcp.models.CallerIdentity.serving().filter(c -> c.origin() == org.jawata.mcp.models.CallerIdentity.Origin.EDITOR).isPresent()) {
            return null;
        }
        return ToolResponse.error("PERSON_IS_DEBUGGING",
            "The person has a debug launch of their own running, and it is their turn: the agent does not start or attach to a program now.",
            "Ask the person to finish or stop their debug session, then try again.");
    }

    @Override
    public String getName() {
        return "debug";
    }

    @Override
    public String getDescription() {
        return """
            Debug a local JVM live. It SUSPENDS THREADS AND CHANGES STATE: for a DEVELOPMENT OR SIMULATION machine.

            USAGE: debug(action="<action>", ...); sessionId needed unless noted (*).
            - discover*, preset_args* (JVM flags), attach* (pid).
            - replay* (mainClass, classpath, invariant, className, line; capture): stop at the first violation.
            - artifacts*, artifact_delete* (artifactId).
            - launch* (mainClass, classpath; args, jvmArgs): HELD before the first instruction - arm, then resume.
              Or launcherPath (Eclipse RCP), or configuration=<IDE run configuration> (not held).
            - status; detach / cancel (kills a launched JVM, releases an attached one).
            - breakpoint_set: kind line|method|conditional|hit_count|exception|field_access|field_write, className,
              + line / method / field / condition / hitCount. breakpoint_list; breakpoint_clear (breakpointId).
            - wait (timeoutMillis): next hit.
            - threads. snapshot (threadId; frameIndex, depth, maxItems, maxBytes). evaluate (threadId, expression).
            - step (threadId, mode in|over|out|to_line; to_line: className, line). resume (threadId or all).
            - instances (className; offset, limit).
            - set_value (threadId, name, expression), force_return, pop_frame, redefine (className, classFile:
              bodies only), mutations (what you changed).
            - probe_set (kind field_watch|method_trace|logpoint; budget): the program keeps running, except a
              logpoint with capture stops the thread each pass. probe_read (values also go to hitStream),
              probe_list, probe_clear (probeId).

            NOTHING IS EVER MISSED: a hit keeps the thread suspended. For a rare one, hand the sessionId to a
            SUBAGENT to loop wait. A JVM started without a debug agent is unreachable; debugging any other JVM that
            has one is your PROFESSIONAL JUDGMENT.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "string");
        action.put("enum", ACTIONS);
        action.put("description", "See the tool description.");
        properties.put("action", action);

        properties.put("pid", Map.of("type", "integer",
            "description", "attach: from discover."));
        properties.put("sessionId", Map.of("type", "string",
            "description", "Session id."));
        properties.put("mainClass", Map.of("type", "string",
            "description", "launch/replay: class to run."));
        properties.put("classpath", Map.of("type", "string",
            "description", "launch/replay."));
        properties.put("args", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "Program arguments."));
        properties.put("jvmArgs", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "Extra JVM flags."));
        properties.put("workingDirectory", Map.of("type", "string",
            "description", "launch."));

        properties.put("configuration", Map.of("type", "string",
            "description", "launch: an IDE run configuration's name; exclusive with mainClass/classpath/launcherPath."));
        properties.put("launcherPath", Map.of("type", "string",
            "description", "launch Eclipse RCP: native launcher; exclusive with mainClass/classpath."));
        properties.put("iniPath", Map.of("type", "string",
            "description", "RCP: .ini (default beside launcher)."));
        properties.put("vmargsExtra", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "RCP: extra VM args."));
        properties.put("configArea", Map.of("type", "string",
            "description", "RCP: -configuration (use a scratch dir)."));
        properties.put("workspaceArea", Map.of("type", "string",
            "description", "RCP: -data (use a scratch dir)."));

        properties.put("kind", Map.of("type", "string", "enum", BREAKPOINT_KINDS,
            "description", "breakpoint_set kind."));
        properties.put("className", Map.of("type", "string",
            "description", "Class FQN (nested: Outer$Inner); for kind=exception, the exception."));
        properties.put("line", Map.of("type", "integer",
            "description", "Source line."));
        properties.put("method", Map.of("type", "string",
            "description", "Method name."));
        properties.put("field", Map.of("type", "string",
            "description", "Field name."));
        properties.put("condition", Map.of("type", "string",
            "description", "kind=conditional: boolean Java expression."));
        properties.put("hitCount", Map.of("type", "integer",
            "description", "kind=hit_count: Nth occurrence."));
        properties.put("breakpointId", Map.of("type", "string",
            "description", "breakpoint_clear."));
        properties.put("threadId", Map.of("type", "integer",
            "description", "Suspended thread; resume: omit for all."));
        properties.put("frameIndex", Map.of("type", "integer",
            "description", "Stack frame (default 0, top)."));
        properties.put("expression", Map.of("type", "string",
            "description", "Java expression."));
        properties.put("mode", Map.of("type", "string",
            "enum", List.of("in", "over", "out", "to_line"),
            "description", "step."));
        properties.put("timeoutMillis", Map.of("type", "integer",
            "description", "wait: default 30000, max 240000. replay: default 60000."));
        properties.put("depth", Map.of("type", "integer",
            "description", "Object expansion depth (default 3)."));
        properties.put("maxItems", Map.of("type", "integer",
            "description", "Per level (default 20)."));
        properties.put("maxBytes", Map.of("type", "integer",
            "description", "String clip (default 4096)."));
        properties.put("offset", Map.of("type", "integer",
            "description", "instances: page offset."));
        properties.put("limit", Map.of("type", "integer",
            "description", "instances: page size (default 10, max 50)."));
        properties.put("artifactId", Map.of("type", "string",
            "description", "artifact_delete."));
        properties.put("name", Map.of("type", "string",
            "description", "set_value: local or field."));
        properties.put("classFile", Map.of("type", "string",
            "description", "redefine: compiled .class path."));
        properties.put("probeId", Map.of("type", "string",
            "description", "probe_clear/probe_read."));
        properties.put("capture", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "Expressions to record; on a logpoint this stops the thread each pass (perturbs)."));
        properties.put("invariant", Map.of("type", "string",
            "description", "replay: boolean expression that must always hold."));
        properties.put("budget", Map.of("type", "integer",
            "description", "probe_set: max events (default 1000)."));

        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String action = getStringParam(arguments, "action");
        if (action == null || action.isBlank()) {
            return ToolResponse.invalidParameter("action", "action is required; one of " + ACTIONS);
        }
        try {
            return switch (action) {
                case "discover" -> discover();
                case "attach" -> attach(arguments);
                case "launch" -> launch(arguments, projectNameOf(service, arguments));
                case "status" -> status(arguments);
                case "detach", "cancel" -> detach(arguments);
                case "preset_args" -> presetArgs();
                case "breakpoint_set" -> breakpointSet(arguments);
                case "breakpoint_clear" -> breakpointClear(arguments);
                case "breakpoint_list" -> breakpointList(arguments);
                case "wait" -> awaitHit(arguments);
                case "threads" -> threads(arguments);
                case "snapshot" -> snapshot(arguments);
                case "evaluate" -> evaluate(arguments);
                case "step" -> step(arguments);
                case "resume" -> resume(arguments);
                case "instances" -> instances(arguments);
                case "set_value" -> setValue(arguments);
                case "force_return" -> forceReturn(arguments);
                case "pop_frame" -> popFrame(arguments);
                case "redefine" -> redefine(arguments);
                case "mutations" -> mutations(arguments);
                case "probe_set" -> probeSet(arguments);
                case "probe_clear" -> probeClear(arguments);
                case "probe_list" -> probeList(arguments);
                case "probe_read" -> probeRead(arguments);
                case "replay" -> replay(arguments, projectNameOf(service, arguments));
                case "artifacts" -> listArtifacts(arguments);
                case "artifact_delete" -> deleteArtifact(arguments);
                default -> ToolResponse.invalidParameter("action",
                    "Unknown action '" + action + "'. Allowed: " + ACTIONS);
            };
        } catch (DebugController.DebugException e) {
            // A debugger's "no" is a RESULT — the caller acts on the code, not on a
            // stack trace. THREAD_NOT_SUSPENDED, CAPABILITY_ABSENT and the rest are
            // facts about the target, not failures of ours.
            return ToolResponse.error(e.code, e.getMessage(), hintFor(e.code));
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    private static String hintFor(String code) {
        return switch (code) {
            case "THREAD_NOT_SUSPENDED" -> "Set a breakpoint, then debug(action=wait) for a "
                + "hit. The hit names the thread you can then read.";
            case "CAPABILITY_ABSENT" -> "The capability report on the session (action=status) "
                + "says what this JVM can actually do.";
            case "SESSION_TARGET_GONE" -> "The target JVM is no longer there. Launch or "
                + "attach again.";
            case "EVALUATION_AMBIGUOUS_OVERLOAD" -> "Cast an argument to pick the overload.";
            case "EVALUATION_UNSUPPORTED" -> "Rewrite the expression using the supported "
                + "forms, or read the value with action=snapshot instead.";
            case "BREAKPOINT_NO_CODE_AT_LINE" -> "Pick a line with a statement on it; "
                + "jawata's analyze/inspect tools give you the exact lines of a method.";
            default -> null;
        };
    }

    /** Every interactive action needs a live session — resolved once, honestly. */
    private DebugController debuggerOf(JsonNode arguments) throws DebugController.DebugException {
        String sessionId = getStringParam(arguments, "sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            throw new DebugController.DebugException("SESSION_REQUIRED",
                "This action needs a sessionId (from debug(action=launch|attach)).");
        }
        RuntimeSession session = sessions.get(sessionId)
            .orElseThrow(() -> new DebugController.DebugException("SESSION_UNKNOWN",
                "No debug session '" + sessionId + "'. Open one with "
                    + "debug(action=launch) or debug(action=attach)."));
        return debuggerOf(session, getStringParam(arguments, "projectKey"));
    }

    /** Every session journals its hits, from the first one — see {@link #hitStreamOf}. */
    private DebugController debuggerOf(RuntimeSession session) {
        return debuggerOf(session, null);
    }

    private DebugController debuggerOf(RuntimeSession session, String projectKey) {
        DebugController debugger = session.debugger();
        debugger.journalHitsTo(hitStreamOf(session));

        // Stamp every event with where it came from. A hit that says
        // {class, method, projectKey} can be handed STRAIGHT to
        // get_call_hierarchy(symbol="Class#method") — no search, no guessing the workspace.
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("sessionId", session.id);
        if (projectKey != null && !projectKey.isBlank()) {
            context.put("projectKey", projectKey);
        }
        debugger.setContext(context);
        return debugger;
    }

    private Path hitStreamOf(RuntimeSession session) {
        return artifacts.root().resolve("sessions").resolve(session.id).resolve("hits.jsonl");
    }

    private ToolResponse breakpointSet(JsonNode arguments) throws Exception {
        // Parameter validation FIRST — it needs no session, and an invalid
        // combination must be refused identically whether or not the session is live.
        String kind = getStringParam(arguments, "kind");
        if (kind == null || !BREAKPOINT_KINDS.contains(kind)) {
            return ToolResponse.invalidParameter("kind",
                "breakpoint_set needs a kind: " + BREAKPOINT_KINDS);
        }
        String className = getStringParam(arguments, "className");
        if (className == null || className.isBlank()) {
            return ToolResponse.invalidParameter("className",
                "breakpoint_set needs the fully-qualified class (Outer$Inner for a nested one).");
        }
        Integer line = optionalInt(arguments, "line");
        Integer hitCount = optionalInt(arguments, "hitCount");
        String method = getStringParam(arguments, "method");
        String field = getStringParam(arguments, "field");
        String condition = getStringParam(arguments, "condition");

        // v2.12.1 (release-day dogfood): a kind whose defining parameter is missing must be
        // REFUSED HERE. It used to be accepted and reported as a successful "pending"
        // breakpoint with a misleading reason ("class not loaded yet") — an armed-looking
        // breakpoint that could never bind, and a wait on it then claimed nothingLost:true.
        if ("method".equals(kind) && (method == null || method.isBlank())) {
            return ToolResponse.invalidParameter("method",
                "breakpoint_set kind=method needs 'method' (the method to break on entry of). "
                    + "Without it the breakpoint could never bind.");
        }
        if (("field_access".equals(kind) || "field_write".equals(kind))
                && (field == null || field.isBlank())) {
            return ToolResponse.invalidParameter("field",
                "breakpoint_set kind=" + kind + " needs 'field' (the field to watch). "
                    + "Without it the breakpoint could never bind.");
        }
        if ("conditional".equals(kind) && (condition == null || condition.isBlank())) {
            return ToolResponse.invalidParameter("condition",
                "breakpoint_set kind=conditional needs 'condition' (a boolean Java expression).");
        }
        if ("hit_count".equals(kind) && hitCount == null) {
            return ToolResponse.invalidParameter("hitCount",
                "breakpoint_set kind=hit_count needs 'hitCount' (stop on the Nth occurrence).");
        }

        DebugController debugger = debuggerOf(arguments);
        Map<String, Object> breakpoint = debugger.setBreakpoint(kind, className, line,
            method, field, condition, hitCount);

        return ToolResponse.success(breakpoint, ResponseMeta.builder()
            .steering(Boolean.TRUE.equals(breakpoint.get("bound"))
                ? "Armed. debug(action=wait) blocks up to timeoutMillis for the hit."
                : "DEFERRED, not failed — the class is not loaded yet. It will bind when it "
                    + "loads; breakpoint_list shows when 'bound' flips to true.")
            .build());
    }

    private ToolResponse breakpointClear(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        String breakpointId = getStringParam(arguments, "breakpointId");
        if (breakpointId == null || breakpointId.isBlank()) {
            return ToolResponse.invalidParameter("breakpointId",
                "breakpoint_clear needs a breakpointId (see breakpoint_list).");
        }
        boolean cleared = debugger.clearBreakpoint(breakpointId);
        if (!cleared) {
            return ToolResponse.symbolNotFound("No breakpoint '" + breakpointId + "'.");
        }
        return ToolResponse.success(Map.of("breakpointId", breakpointId, "cleared", true),
            ResponseMeta.builder().build());
    }

    private ToolResponse breakpointList(JsonNode arguments) throws Exception {
        List<Map<String, Object>> breakpoints = debuggerOf(arguments).listBreakpoints();
        return ToolResponse.success(
            Map.of("breakpoints", breakpoints, "count", breakpoints.size()),
            ResponseMeta.builder().returnedCount(breakpoints.size()).build());
    }

    private ToolResponse awaitHit(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        long timeout = Math.min(MAX_WAIT_MILLIS,
            Math.max(0, getIntParam(arguments, "timeoutMillis", (int) DEFAULT_WAIT_MILLIS)));
        Optional<Map<String, Object>> hit = debugger.awaitHit(timeout);

        if (hit.isEmpty()) {
            // Not an error. Nothing has hit yet, and saying so is the honest answer.
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("hit", false);
            data.put("waitedMillis", timeout);
            data.put("breakpointsArmed", debugger.listBreakpoints().size());
            data.put("targetAlive", debugger.isLive());
            data.put("nothingLost", true);
            return ToolResponse.success(data, ResponseMeta.builder()
                .steering("Nothing hit in that window — and nothing was lost: a hit suspends "
                    + "the thread and holds it, so calling wait again picks it up whenever it "
                    + "comes. If the condition is rare, hand this sessionId to a subagent and "
                    + "let it wait. If it should have hit by now, check breakpoint_list — one "
                    + "showing bound:false has not armed yet.")
                .build());
        }
        Map<String, Object> data = new LinkedHashMap<>(hit.get());
        data.put("hit", true);
        data.put("morePending", debugger.pendingHits());

        // THE LOOP CLOSES HERE. The hit already names the class and the method — which is
        // exactly the key every static tool takes. No search is needed, and none should be
        // run: a search would only re-derive what the running program just told us.
        String symbol = data.get("class") + "#" + data.get("method");
        data.put("symbol", symbol);

        // RECALL BEFORE YOU PROBE (D15). The moment the runtime names a symbol, surface what
        // is ALREADY on record about it — a prior incident, a lesson, a known hazard — so the
        // agent is reminded before it investigates from scratch. The spec's measure is exactly
        // this: "a diagnosis session demonstrably recalls a previously recorded incident
        // first." Sprint-24 audit (T2.5) — this was proven nowhere and wired nowhere in v2.13.0.
        Map<String, Object> recalled = recallFor(symbol);
        List<Map<String, Object>> priorKnowledge = entriesOf(recalled);
        String recallSteering = "";
        // #37: an unavailable knowledge layer is not "nothing on record". Without this,
        // the debugger seat is told nothing and investigates from scratch believing the
        // store was consulted — the defect #37 names, at a consumer in this same repo.
        if (org.jawata.mcp.knowledge.ExperienceRetrieval.RESULT_UNAVAILABLE
                .equals(recalled.get("result"))) {
            recallSteering = " KNOWLEDGE LAYER UNAVAILABLE (" + recalled.get("reason") + ") — "
                + "prior notes on this symbol could NOT be read. Nothing is established either "
                + "way; do not treat this as 'nothing on record'.";
        } else if (!priorKnowledge.isEmpty()) {
            data.put("priorKnowledge", priorKnowledge);
            recallSteering = " YOU HAVE " + priorKnowledge.size() + " PRIOR NOTE(S) on this "
                + "symbol (priorKnowledge above) — read them BEFORE probing; the last session "
                + "to stop here may already have found what you are about to look for.";
        }

        return ToolResponse.success(data, ResponseMeta.builder()
            .steering("The thread is SUSPENDED here. Read it with action=snapshot (threadId "
                + "above), then step or resume; every other thread is still running. AND YOU "
                + "ALREADY HAVE THE KEY: symbol=\"" + symbol + "\" goes straight into "
                + "get_call_hierarchy / find_references / analyze — do NOT search for it, the "
                + "running program has just told you exactly where it is." + recallSteering)
            .build());
    }

    /**
     * The recall RESULT for a symbol the runtime just named — the whole document, not
     * only its entries.
     *
     * <p>#37: this used to return the entry list alone, which collapsed "the store holds
     * nothing about this symbol" and "the store could not be read" into the same empty
     * list. The caller needs to tell them apart, so the caller gets the result word.
     * An empty map means the recall was never attempted (no store wired, no symbol).</p>
     */
    private Map<String, Object> recallFor(String symbol) {
        if (experience == null || symbol == null || symbol.isBlank()) {
            return Map.of();
        }
        try {
            return experience.recall(
                new org.jawata.mcp.knowledge.RecallQuery(symbol, null, null, null, null,
                    org.jawata.mcp.knowledge.LaneScope.of(
                        org.jawata.mcp.knowledge.KnowledgeLane.EXPERIENCE,
                        org.jawata.mcp.knowledge.KnowledgeLane.DOMAIN)));
        } catch (Exception e) {
            log.debug("recall-before-probe for {} failed: {}", symbol, e.getMessage());
            return Map.of();
        }
    }

    /** The entries of a recall result, or empty when it carries none. */
    private static List<Map<String, Object>> entriesOf(Map<String, Object> recalled) {
        Object entries = recalled.get("entries");
        if (!(entries instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object e : list) {
            if (e instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) m;
                out.add(entry);
            }
        }
        return out;
    }

    private ToolResponse threads(JsonNode arguments) throws Exception {
        return ToolResponse.success(debuggerOf(arguments).threads(),
            ResponseMeta.builder().build());
    }

    private ToolResponse snapshot(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        long threadId = longParam(arguments, "threadId", -1);
        if (threadId < 0) {
            return ToolResponse.invalidParameter("threadId",
                "snapshot needs the threadId of a SUSPENDED thread (a hit names one; "
                    + "action=threads lists them).");
        }
        Map<String, Object> snapshot = debugger.snapshot(threadId,
            getIntParam(arguments, "frameIndex", 0),
            getIntParam(arguments, "depth", JdiValues.DEFAULT_DEPTH),
            getIntParam(arguments, "maxItems", JdiValues.DEFAULT_MAX_ITEMS),
            getIntParam(arguments, "maxBytes", JdiValues.DEFAULT_MAX_BYTES));
        return ToolResponse.success(snapshot, ResponseMeta.builder()
            .steering("Anything marked truncated was cut by a bound you can raise "
                + "(depth / maxItems / maxBytes) — it is not the end of the object.")
            .build());
    }

    private ToolResponse evaluate(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        long threadId = longParam(arguments, "threadId", -1);
        String expression = getStringParam(arguments, "expression");
        if (threadId < 0) {
            return ToolResponse.invalidParameter("threadId",
                "evaluate needs the threadId of a SUSPENDED thread.");
        }
        if (expression == null || expression.isBlank()) {
            return ToolResponse.invalidParameter("expression",
                "evaluate needs a Java expression.");
        }
        Map<String, Object> evaluated = debugger.evaluate(threadId,
            getIntParam(arguments, "frameIndex", 0), expression);
        return ToolResponse.success(evaluated, ResponseMeta.builder()
            .steering("Evaluated IN the target JVM — a method call here really ran there.")
            .build());
    }

    private ToolResponse step(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        long threadId = longParam(arguments, "threadId", -1);
        if (threadId < 0) {
            return ToolResponse.invalidParameter("threadId",
                "step needs the threadId of a SUSPENDED thread.");
        }
        String mode = getStringParam(arguments, "mode");
        if (mode == null || mode.isBlank()) {
            return ToolResponse.invalidParameter("mode", "step needs a mode: in, over, out, "
                + "or to_line.");
        }
        Map<String, Object> stepped;
        if ("to_line".equals(mode)) {
            String className = getStringParam(arguments, "className");
            Integer line = optionalInt(arguments, "line");
            if (className == null || line == null) {
                return ToolResponse.invalidParameter("className",
                    "step(mode=to_line) needs className and line.");
            }
            stepped = debugger.stepToLine(threadId, className, line);
        } else {
            stepped = debugger.step(threadId, mode);
        }
        return ToolResponse.success(stepped, ResponseMeta.builder()
            .steering("Poll debug(action=wait) for where the thread lands.")
            .build());
    }

    private ToolResponse resume(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        long threadId = longParam(arguments, "threadId", -1);
        return ToolResponse.success(debugger.resume(threadId < 0 ? null : threadId),
            ResponseMeta.builder().build());
    }

    private ToolResponse instances(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        String className = getStringParam(arguments, "className");
        if (className == null || className.isBlank()) {
            return ToolResponse.invalidParameter("className",
                "instances needs a fully-qualified class (Outer$Inner for a nested one).");
        }
        Map<String, Object> found = debugger.instances(className,
            getIntParam(arguments, "offset", 0),
            getIntParam(arguments, "limit", 10),
            getIntParam(arguments, "depth", 2));
        return ToolResponse.success(found, ResponseMeta.builder()
            .steering("'countSource' says how the count was obtained — a live enumeration is "
                + "exact up to the cap; past it the exact number comes from the heap "
                + "histogram. A floor is labelled as one.")
            .build());
    }

    private ToolResponse setValue(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        long threadId = longParam(arguments, "threadId", -1);
        String name = getStringParam(arguments, "name");
        String expression = getStringParam(arguments, "expression");
        if (threadId < 0 || name == null || expression == null) {
            return ToolResponse.invalidParameter("name",
                "set_value needs threadId, name (a local or field), and expression (the new "
                    + "value).");
        }
        Map<String, Object> mutation = debugger.setValue(threadId,
            getIntParam(arguments, "frameIndex", 0), name, expression);
        return ToolResponse.success(mutation, ResponseMeta.builder()
            .steering("The program has been CHANGED. Whatever it does from here, it does as an "
                + "edited program — say so when you report what you found. "
                + "debug(action=mutations) lists everything this session changed.")
            .build());
    }

    private ToolResponse forceReturn(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        long threadId = longParam(arguments, "threadId", -1);
        String expression = getStringParam(arguments, "expression");
        if (threadId < 0 || expression == null) {
            return ToolResponse.invalidParameter("expression",
                "force_return needs threadId and expression (the value to return).");
        }
        return ToolResponse.success(debugger.forceReturn(threadId, expression),
            ResponseMeta.builder()
                .steering("The rest of that method did NOT run. The thread is suspended in the "
                    + "caller — snapshot it to see what the caller now believes.")
                .build());
    }

    private ToolResponse popFrame(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        long threadId = longParam(arguments, "threadId", -1);
        if (threadId < 0) {
            return ToolResponse.invalidParameter("threadId",
                "pop_frame needs the threadId of a SUSPENDED thread.");
        }
        return ToolResponse.success(
            debugger.popFrame(threadId, getIntParam(arguments, "frameIndex", 0)),
            ResponseMeta.builder()
                .steering("The stack is rewound — the world is not. Anything that frame already "
                    + "wrote, sent or printed has still happened.")
                .build());
    }

    private ToolResponse redefine(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        String className = getStringParam(arguments, "className");
        String classFile = getStringParam(arguments, "classFile");
        if (className == null || classFile == null) {
            return ToolResponse.invalidParameter("classFile",
                "redefine needs className and classFile (a compiled .class holding the new "
                    + "method bodies).");
        }
        Path file = Path.of(classFile);
        if (!Files.isRegularFile(file)) {
            return ToolResponse.invalidParameter("classFile",
                "No such .class file: " + classFile);
        }
        return ToolResponse.success(debugger.redefine(className, Files.readAllBytes(file)),
            ResponseMeta.builder()
                .steering("Methods already on the stack keep their OLD code until they are "
                    + "re-entered — so the change shows up on the next call, not this one.")
                .build());
    }

    private ToolResponse mutations(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        List<Map<String, Object>> changed = debugger.mutations();
        List<Map<String, Object>> ran = debugger.evaluations();
        long invoking = ran.stream()
            .filter(e -> Boolean.TRUE.equals(e.get("invokedMethod")))
            .count();
        boolean unmodified = debugger.programIsUnmodified();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mutations", changed);
        data.put("count", changed.size());
        // An evaluation that ran the target's own code is part of what we did to it. It was
        // reported nowhere, so `programIsUnmodified: true` could be said of a program we had
        // just told to clear its own queue. Sprint-24 audit (T1.15).
        data.put("evaluations", ran);
        data.put("evaluationsThatInvokedMethods", invoking);
        data.put("programIsUnmodified", unmodified);
        return ToolResponse.success(data, ResponseMeta.builder()
            .returnedCount(changed.size())
            .steering(unmodified
                ? "Nothing has been changed — what this program does, it does on its own."
                : changed.isEmpty()
                    ? "No state was overwritten, but " + invoking + " evaluation(s) RAN THIS "
                        + "PROGRAM'S OWN CODE in it. We cannot prove those calls were pure, so "
                        + "this is no longer provably the program you started: say so when you "
                        + "report what you found."
                    : "This program has been EDITED " + changed.size() + " time(s). Any finding "
                        + "from here on describes the edited program; report it that way.")
            .build());
    }

    private ToolResponse probeSet(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        String kind = getStringParam(arguments, "kind");
        if (kind == null || !PROBE_KINDS.contains(kind)) {
            return ToolResponse.invalidParameter("kind", "probe_set needs a kind: " + PROBE_KINDS);
        }
        String className = getStringParam(arguments, "className");
        if (className == null || className.isBlank()) {
            return ToolResponse.invalidParameter("className",
                "probe_set needs the fully-qualified class to watch.");
        }
        Map<String, Object> probe = debugger.setProbe(kind, className,
            optionalInt(arguments, "line"), getStringParam(arguments, "method"),
            getStringParam(arguments, "field"), stringList(arguments, "capture"),
            optionalInt(arguments, "budget"));

        if (!Boolean.TRUE.equals(probe.get("bound"))) {
            // Same shape as breakpoint_set's: a deferred probe is not a failed one, and the
            // difference has to be visible in the ANSWER, not only in a field of the payload.
            return ToolResponse.success(probe, ResponseMeta.builder()
                .steering("DEFERRED, not failed — the class is not loaded yet. The probe "
                    + "attaches when it loads; probe_list shows when 'bound' flips to true. "
                    + "Nothing before that moment is observed.")
                .build());
        }

        return ToolResponse.success(probe, ResponseMeta.builder()
            .steering(Boolean.TRUE.equals(probe.get("perturbs"))
                ? "NOTE: this probe captures expressions, which means reading a frame, which "
                    + "means STOPPING the thread — briefly, resuming itself at once, but it "
                    + "perturbs. If you are hunting a race, prefer field_watch or "
                    + "method_trace: they read what the event already carries and stop nothing."
                : "The program keeps running at full speed — nothing is suspended. Read the "
                    + "values with probe_read, or watch `hitStream` and be notified as they "
                    + "stream.")
            .build());
    }

    private ToolResponse probeClear(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        String probeId = getStringParam(arguments, "probeId");
        if (probeId == null || probeId.isBlank()) {
            return ToolResponse.invalidParameter("probeId", "probe_clear needs a probeId.");
        }
        if (!debugger.clearProbe(probeId)) {
            return ToolResponse.symbolNotFound("No probe '" + probeId + "'.");
        }
        return ToolResponse.success(Map.of("probeId", probeId, "cleared", true),
            ResponseMeta.builder().build());
    }

    private ToolResponse probeList(JsonNode arguments) throws Exception {
        List<Map<String, Object>> all = debuggerOf(arguments).listProbes();
        return ToolResponse.success(Map.of("probes", all, "count", all.size()),
            ResponseMeta.builder().returnedCount(all.size()).build());
    }

    private ToolResponse probeRead(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        String probeId = getStringParam(arguments, "probeId");
        List<Map<String, Object>> events = debugger.probeEvents(probeId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("events", events);
        data.put("returned", events.size());
        // Say what this window IS. The probe may have seen far more than we kept.
        debugger.listProbes().stream()
            .filter(p -> probeId == null || probeId.equals(p.get("probeId")))
            .findFirst()
            .ifPresent(p -> {
                data.put("totalSeen", p.get("events"));
                if (Boolean.TRUE.equals(p.get("stopped"))) {
                    data.put("probeStopped", p.get("stoppedReason"));
                }
            });
        return ToolResponse.success(data, ResponseMeta.builder()
            .returnedCount(events.size())
            .steering("`totalSeen` is how many events the probe saw; `returned` is how many we "
                + "kept. If they differ, this is a WINDOW on the stream, not the stream.")
            .build());
    }

    /**
     * Launch a replay, declare an invariant, and capture the FIRST moment it breaks.
     *
     * <p>The invariant is armed as its own negation on a conditional breakpoint: the program
     * runs at speed and stops only where it is actually broken. That is the difference
     * between "here is a log of everything that happened" and "here is the moment it went
     * wrong, with the program still standing in it".</p>
     */
    private ToolResponse replay(JsonNode arguments, String projectName) throws Exception {
        String mainClass = getStringParam(arguments, "mainClass");
        String classpath = getStringParam(arguments, "classpath");
        String invariant = getStringParam(arguments, "invariant");
        String className = getStringParam(arguments, "className");
        Integer line = optionalInt(arguments, "line");

        if (mainClass == null || classpath == null || invariant == null
                || className == null || line == null) {
            return ToolResponse.invalidParameter("invariant",
                "replay needs mainClass, classpath, invariant (a boolean expression), and "
                    + "className + line (where to check it).");
        }

        List<String> command = new ArrayList<>(List.of("-cp", classpath, mainClass));
        command.addAll(stringList(arguments, "args"));
        RuntimeSession session = sessions.launch(projectName, command, null, List.of());
        DebugController debugger = debuggerOf(session);

        // Armed as the NEGATION: we do not want every event, we want the broken one.
        Map<String, Object> armed = debugger.setBreakpoint("conditional", className, line,
            null, null, "!(" + invariant + ")", null);

        debugger.resume(null);   // start the held program

        long timeout = Math.min(MAX_WAIT_MILLIS,
            Math.max(1_000, getIntParam(arguments, "timeoutMillis", 60_000)));
        Optional<Map<String, Object>> violation = debugger.awaitHit(timeout);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.id);
        result.put("invariant", invariant);
        result.put("checkedAt", className + ":" + line);

        if (violation.isEmpty()) {
            // The invariant HELD (or the run did not reach a violation in time). Both are
            // answers — and they are DIFFERENT answers, so we do not merge them.
            boolean ended = !debugger.isLive();
            result.put("violated", false);
            result.put("programEnded", ended);
            result.put("conclusion", ended
                ? "The replay ran to completion and the invariant held at every check."
                : "No violation within " + timeout + "ms, and the program is STILL RUNNING — "
                    + "so this is not yet 'the invariant holds'. Wait longer, or check that "
                    + "the invariant and its location are what you meant.");
            return ToolResponse.success(result, ResponseMeta.builder()
                .steering(ended
                    ? "The invariant held. The session is still open if you want to look."
                    : "Inconclusive, not clean — say so if you report it.")
                .build());
        }

        // THE FIRST VIOLATION. Disarm at once: every later event would break it too, and a
        // second capture would make "the first" a lie.
        debugger.clearBreakpoint((String) armed.get("breakpointId"));

        Map<String, Object> hit = violation.get();
        long threadId = ((Number) hit.get("threadId")).longValue();

        Map<String, Object> capture = new LinkedHashMap<>(hit);
        capture.put("state", debugger.snapshot(threadId, 0,
            getIntParam(arguments, "depth", JdiValues.DEFAULT_DEPTH),
            JdiValues.DEFAULT_MAX_ITEMS, JdiValues.DEFAULT_MAX_BYTES));

        Map<String, Object> captured = new LinkedHashMap<>();
        for (String expression : stringList(arguments, "capture")) {
            try {
                captured.put(expression, debugger.evaluate(threadId, 0, expression).get("summary"));
            } catch (DebugController.DebugException e) {
                captured.put(expression, e.code + ": " + e.getMessage());
            }
        }
        capture.put("captured", captured);

        // Store it with its provenance — a capture whose origin is unknown is evidence of
        // nothing.
        String artifactId = artifacts.newArtifactId("replay");
        Path dir = artifacts.createArtifactDir(artifactId);
        Files.writeString(dir.resolve("capture.json"),
            new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(capture));
        artifacts.writeManifest(artifactId, Map.of(
            "kind", "replay",
            "sessionId", session.id,
            "target", mainClass,
            "invariant", invariant,
            "checkedAt", className + ":" + line,
            "violated", true,
            "files", List.of("capture.json")));

        result.put("violated", true);
        result.put("firstViolation", capture);
        result.put("artifactId", artifactId);
        result.put("threadId", threadId);
        return ToolResponse.success(result, ResponseMeta.builder()
            .steering("This is the FIRST violation — the breakpoint was disarmed the moment it "
                + "fired, so no later one can be mistaken for it. The thread is SUSPENDED "
                + "right there: snapshot, evaluate and step from the moment it broke, rather "
                + "than reasoning backwards from the wreckage. sessionId " + session.id + ".")
            .build());
    }

    private ToolResponse listArtifacts(JsonNode arguments) {
        Map<String, Object> data = artifacts.listing(getIntParam(arguments, "offset", 0),
            getIntParam(arguments, "limit", RuntimeArtifactStore.LISTING_DEFAULT));
        return ToolResponse.success(data, ResponseMeta.builder()
            .returnedCount((Integer) data.get("returned"))
            .steering("Newest first, " + data.get("returned") + " of " + data.get("count") + ": page with offset and limit (most "
                + RuntimeArtifactStore.LISTING_MAX + "). These are on your disk and some are large. Delete with "
                + "debug(action=artifact_delete, artifactId=…); expired ones are pruned.")
            .build());
    }

    private ToolResponse deleteArtifact(JsonNode arguments) {
        String artifactId = getStringParam(arguments, "artifactId");
        if (artifactId == null || artifactId.isBlank()) {
            return ToolResponse.invalidParameter("artifactId",
                "artifact_delete needs an artifactId (see action=artifacts).");
        }
        boolean deleted = artifacts.delete(artifactId);
        if (!deleted) {
            return ToolResponse.symbolNotFound("No artifact '" + artifactId + "'.");
        }
        return ToolResponse.success(Map.of("artifactId", artifactId, "deleted", true),
            ResponseMeta.builder().build());
    }

    private Integer optionalInt(JsonNode arguments, String field) {
        JsonNode node = arguments.get(field);
        return node == null || node.isNull() ? null : node.asInt();
    }

    private long longParam(JsonNode arguments, String field, long fallback) {
        JsonNode node = arguments.get(field);
        return node == null || node.isNull() ? fallback : node.asLong();
    }

    private ToolResponse discover() {
        List<Map<String, Object>> jvms = JvmTargets.discover();
        labelProgramsStartedForJawata(jvms);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jvms", jvms);
        data.put("count", jvms.size());
        return ToolResponse.success(data, ResponseMeta.builder()
            .returnedCount(jvms.size())
            .steering(jvms.isEmpty()
                ? "No other local JVMs are running. Use action=launch to start one."
                : "Attach with debug(action=attach, pid=…) — but only where debuggable=true. "
                    + "A JVM that started without a debug agent can NEVER be given one; "
                    + "debuggability is decided at launch and cannot be retrofitted.")
            .build());
    }

    /**
     * Marks the programs the host started for jawata (path A by the agent, path B by the person) so the agent knows which one to attach
     * to: each carries the person's run configuration, who started it, and a sentence saying so.
     */
    private void labelProgramsStartedForJawata(List<Map<String, Object>> jvms) {
        for (HostRuns.RunForJawata run : hostRuns.map(HostRuns::runningForJawata).orElse(List.of())) {
            for (Map<String, Object> jvm : jvms) {
                if (jvm.get("pid") instanceof Number pid && pid.longValue() == run.pid()) {
                    jvm.put("startedForJawata", true);
                    jvm.put("configuration", run.configuration());
                    jvm.put("startedBy", run.startedBy());
                    jvm.put("label", "started by the " + run.startedBy() + " for jawata, configuration " + run.configuration());
                }
            }
        }
    }

    private ToolResponse attach(JsonNode arguments) throws Exception {
        ToolResponse refused = refusedWhilePersonDebugs();
        if (refused != null) {
            return refused;
        }
        int pid = getIntParam(arguments, "pid", -1);
        if (pid <= 0) {
            return ToolResponse.invalidParameter("pid",
                "attach needs the pid of a local JVM (see action=discover).");
        }
        RuntimeSession session;
        try {
            session = sessions.attach(pid);
        } catch (JvmTargets.NotDebuggable e) {
            // Not a crash and not our failure: this JVM can never be debugged, and the
            // caller needs to know that rather than retry.
            return ToolResponse.error("JVM_NOT_DEBUGGABLE", e.getMessage(),
                "Debuggability is decided at launch. Start the target with "
                    + "debug(action=launch) — the dev/sim preset prepares it — or add "
                    + "-agentlib:jdwp to however it is launched.");
        }
        return sessionResponse(session, "Attached. This JVM is not ours — detach releases it, "
            + "it keeps running.", getStringParam(arguments, "projectKey"));
    }

    private ToolResponse launch(JsonNode arguments, String projectName) throws Exception {
        ToolResponse refused = refusedWhilePersonDebugs();
        if (refused != null) {
            return refused;
        }
        String launcherPath = getStringParam(arguments, "launcherPath");
        String mainClass = getStringParam(arguments, "mainClass");
        String classpath = getStringParam(arguments, "classpath");
        String configuration = getStringParam(arguments, "configuration");
        if (configuration != null && !configuration.isBlank()) {
            if (mainClass != null || classpath != null || launcherPath != null) {
                return ToolResponse.invalidParameter("configuration",
                    "A run configuration (configuration) is exclusive with mainClass, classpath and launcherPath: the host "
                        + "builds the command from the person's configuration.");
            }
            return launchHostRun(arguments, configuration);
        }
        if (launcherPath != null && !launcherPath.isBlank()) {
            if (mainClass != null || classpath != null) {
                return ToolResponse.invalidParameter("launcherPath",
                    "The eclipse-rcp shape (launcherPath) is exclusive with "
                        + "mainClass/classpath — a native launcher brings its own JVM.");
            }
            return launchRcp(arguments, launcherPath);
        }
        if (mainClass == null || mainClass.isBlank()) {
            return ToolResponse.invalidParameter("mainClass", "launch needs a mainClass.");
        }
        if (classpath == null || classpath.isBlank()) {
            return ToolResponse.invalidParameter("classpath", "launch needs a classpath.");
        }
        List<String> command = new ArrayList<>(List.of("-cp", classpath, mainClass));
        command.addAll(stringList(arguments, "args"));

        String workingDirectory = getStringParam(arguments, "workingDirectory");
        RuntimeSession session = sessions.launch(
            projectName,
            command,
            workingDirectory == null ? null : Path.of(workingDirectory),
            stringList(arguments, "jvmArgs"));
        return sessionResponse(session,
            "Launched under the dev/sim preset, and HELD BEFORE ITS FIRST INSTRUCTION. "
                + "This JVM is ours — detach kills it. Arm your breakpoints now, then "
                + "debug(action=resume) to start the program: a target that is already "
                + "running has already run past whatever you wanted to see.",
            getStringParam(arguments, "projectKey"));
    }

    /**
     * Path A of "Run for jawata" (contract §6.1): the host starts the person's run configuration with the debug agent in the background,
     * and the debugger attaches to it by the process id the host answers with. The program is never held at its start.
     */
    private ToolResponse launchHostRun(JsonNode arguments, String configuration) throws Exception {
        if (hostRuns.isEmpty()) {
            return ToolResponse.error("HOST_RUNS_NO_PROGRAMS",
                "This host does not run programs from run configurations, so there is no configuration '" + configuration + "' to start.",
                "Start the program with debug(action=launch, mainClass=…, classpath=…), or start it yourself with the debug agent "
                    + "(debug(action=preset_args)) and attach by pid.");
        }
        HostRuns.RunForJawata run;
        try {
            run = hostRuns.get().runForJawata(configuration, new org.eclipse.core.runtime.NullProgressMonitor());
        } catch (org.eclipse.core.runtime.CoreException refused) {
            return ToolResponse.error("HOST_RUN_FAILED", refused.getMessage(),
                "Name a run configuration the person has made in the host, as the host lists it.");
        }
        RuntimeSession session;
        try {
            session = sessions.attach(run.pid());
        } catch (JvmTargets.NotDebuggable e) {
            return ToolResponse.error("JVM_NOT_DEBUGGABLE", e.getMessage(),
                "The host started '" + configuration + "' (pid " + run.pid() + ") but its debug agent did not answer.");
        }
        return sessionResponse(session, "Started by the host from the person's run configuration '" + configuration + "' (pid " + run.pid()
            + ") and attached. The program is NOT held: it is already running, so reproduce the problem in it, and set breakpoints or "
            + "probes now. detach releases it and it keeps running; the person's configuration is unchanged.",
            getStringParam(arguments, "projectKey"));
    }

    /** The eclipse-rcp launch shape: native launcher, preset behind -vmargs. */
    private ToolResponse launchRcp(JsonNode arguments, String launcherPath) throws Exception {
        Path launcher = Path.of(launcherPath);
        if (!java.nio.file.Files.isExecutable(launcher)) {
            return ToolResponse.invalidParameter("launcherPath",
                "Not an executable: " + launcherPath);
        }
        List<String> programArgs = new ArrayList<>();
        String iniPath = getStringParam(arguments, "iniPath");
        if (iniPath != null && !iniPath.isBlank()) {
            programArgs.add("--launcher.ini");
            programArgs.add(iniPath);
        }
        String configArea = getStringParam(arguments, "configArea");
        if (configArea != null && !configArea.isBlank()) {
            programArgs.add("-configuration");
            programArgs.add(configArea);
        }
        String workspaceArea = getStringParam(arguments, "workspaceArea");
        if (workspaceArea != null && !workspaceArea.isBlank()) {
            programArgs.add("-data");
            programArgs.add(workspaceArea);
        }
        programArgs.addAll(stringList(arguments, "args"));

        String workingDirectory = getStringParam(arguments, "workingDirectory");
        RuntimeSession session = sessions.launchRcp(
            launcher, programArgs, stringList(arguments, "vmargsExtra"),
            workingDirectory == null ? null : Path.of(workingDirectory));
        return sessionResponse(session,
            "Launched the RCP product under the dev/sim preset (behind -vmargs with "
                + "--launcher.appendVmargs, so its own ini vmargs still apply), and HELD "
                + "BEFORE ITS FIRST INSTRUCTION. This JVM is ours — detach kills it. Point "
                + "configArea/workspaceArea at scratch dirs to leave the product's own "
                + "configuration untouched. Arm breakpoints now, then debug(action=resume).",
            getStringParam(arguments, "projectKey"));
    }

    /**
     * The dev/sim preset as PASTE-READY argument blocks, for a target the HOST
     * starts (suspend=n — a pasted preset must never hang the program waiting
     * for a debugger; the held-before-first-instruction variant belongs to
     * action=launch, where the debugger owns the process).
     */
    private ToolResponse presetArgs() {
        List<String> args = org.jawata.mcp.runtime.DevSimPreset.jvmArgs();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jvmArgs", args);
        data.put("jvmArgsLine", String.join(" ", args));
        StringBuilder ini = new StringBuilder("--launcher.appendVmargs\n-vmargs\n");
        for (String arg : args) {
            ini.append(arg).append('\n');
        }
        data.put("eclipseIniBlock", ini.toString());
        data.put("instructions", List.of(
            "Plain java command: append jvmArgsLine before the main class / -jar "
                + "(or export it via JAVA_TOOL_OPTIONS / your launcher's JVM-args knob).",
            "Eclipse RCP product: paste eclipseIniBlock at the END of the product's .ini "
                + "file — --launcher.appendVmargs keeps the ini's existing vmargs AND these; "
                + "each argument must stay on its own line.",
            "The preset uses suspend=n: the program starts normally and is NOT held — "
                + "attach later with debug(action=attach) (find the pid via "
                + "debug(action=discover)). To debug from the first instruction instead, "
                + "use debug(action=launch), which holds the target.",
            "Loopback only: the debug port binds to 127.0.0.1 and is announced on stdout; "
                + "nothing here reaches off the machine."));
        return ToolResponse.success(data, ResponseMeta.builder()
            .steering("Paste, restart the target, then debug(action=discover) shows it as "
                + "debuggable and debug(action=attach) connects.")
            .build());
    }

    private ToolResponse status(JsonNode arguments) {
        String sessionId = getStringParam(arguments, "sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            List<Map<String, Object>> all = sessions.list();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sessions", all);
            data.put("count", all.size());
            return ToolResponse.success(data,
                ResponseMeta.builder().returnedCount(all.size()).build());
        }
        Optional<RuntimeSession> session = sessions.get(sessionId);
        if (session.isEmpty()) {
            return ToolResponse.symbolNotFound("No debug session '" + sessionId + "'.");
        }
        // Opens the journal if it is not open yet: every path that hands back a session must
        // name its hit stream, or an agent that never called launch cannot find it.
        DebugController debugger = debuggerOf(session.get());
        Map<String, Object> described = new LinkedHashMap<>(session.get().describe());
        described.put("hitStream", hitStreamOf(session.get()).toString());
        // The session always discloses what it changed. A debugged program is no longer the
        // program you started, and a status that hides that invites a false conclusion — and
        // "changed" includes an evaluation that ran the target's own (not-provably-pure) code,
        // not only an explicit state mutation. Sprint-24 audit (T1.15).
        List<Map<String, Object>> changed = debugger.mutations();
        long invokingEvaluations = debugger.evaluations().stream()
            .filter(e -> Boolean.TRUE.equals(e.get("invokedMethod")))
            .count();
        described.put("mutationCount", changed.size());
        described.put("evaluationsThatInvokedMethods", invokingEvaluations);
        described.put("programIsUnmodified", debugger.programIsUnmodified());
        if (!changed.isEmpty()) {
            described.put("mutations", changed);
        }
        return ToolResponse.success(described, ResponseMeta.builder()
            .steering("Every hit is appended as one JSON line to `hitStream` — point a file "
                + "monitor at it and each breakpoint wakes you, no polling.")
            .build());
    }

    private ToolResponse detach(JsonNode arguments) {
        String sessionId = getStringParam(arguments, "sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            return ToolResponse.invalidParameter("sessionId", "detach needs a sessionId.");
        }
        Optional<RuntimeSession> session = sessions.get(sessionId);
        if (session.isEmpty()) {
            return ToolResponse.symbolNotFound("No debug session '" + sessionId + "'.");
        }
        RuntimeSession.Origin origin = session.get().origin;
        sessions.close(sessionId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("closed", true);
        data.put("outcome", origin == RuntimeSession.Origin.LAUNCHED
            ? "terminated (we launched it)"
            : "released (it keeps running — it was never ours)");
        return ToolResponse.success(data, ResponseMeta.builder().build());
    }

    private ToolResponse sessionResponse(RuntimeSession session, String note, String projectKey) {
        // Open the journal NOW, before the caller can arm anything — so a watcher can be
        // attached to an existing file rather than racing its creation.
        debuggerOf(session, projectKey);

        Map<String, Object> data = new LinkedHashMap<>(session.describe());
        data.put("note", note);
        data.put("hitStream", hitStreamOf(session).toString());
        return ToolResponse.success(data, ResponseMeta.builder()
            .steering("BE WOKEN BY BREAKPOINTS, DO NOT POLL FOR THEM: every hit is appended "
                + "as one JSON line to `hitStream`. Point your harness's file monitor at it "
                + "(e.g. `tail -f <hitStream>`) and each breakpoint becomes a notification — "
                + "the session stays live and you stay idle in between. jawata cannot push to "
                + "you over MCP (a server only speaks when spoken to), but your own harness "
                + "can wake you, and this is the channel it watches. `wait` remains available "
                + "for the simple case: it blocks and returns the next hit. "
                + "Capabilities are read from the JVM, never assumed — hot-swap and pop-frame "
                + "are not universal. Preset capabilities: " + DevSimPreset.CAPABILITIES + ".")
            .build());
    }

    /**
     * The Eclipse project name of the project the call names with {@code projectKey}: the project a launched program belongs to, whose
     * runtime the host says. Null when the call names none or none is loaded under that key, which launches on the engine's own runtime.
     */
    private String projectNameOf(IJdtService service, JsonNode arguments) {
        String projectKey = getStringParam(arguments, "projectKey");
        if (projectKey == null || projectKey.isBlank()) {
            return null;
        }
        return service.getProject(projectKey).map(loaded -> loaded.javaProject().getProject().getName()).orElse(null);
    }

    private List<String> stringList(JsonNode arguments, String field) {
        List<String> values = new ArrayList<>();
        JsonNode node = arguments.get(field);
        if (node != null && node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        }
        return values;
    }
}
