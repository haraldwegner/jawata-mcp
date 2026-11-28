package org.jawata.mcp.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.execution.HireRegistry;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What a client that runs jawata without the jawata chat asks to learn what its hire did (Sprint 28h, Stage 27, z30). In the chat jawata is the
 * principal and a hire's report comes back as a new turn; a client that only calls this server's tools is its own principal, nothing can push a
 * report into its turn, and the hire tool answers at once. So it asks: the state of one hire's plan, waiting a few seconds for a running one.
 */
public final class HireWaitTool implements Tool {

    /** Stands for the ceiling the registry holds, the host's setting when it set one (28h Stage 27, z28): read when a hire is asked about. */
    static final long DEFAULT_STALL_SECONDS = -1;

    private final HireRegistry hires;
    private final long stallSeconds;

    public HireWaitTool(HireRegistry hires) {
        this(hires, DEFAULT_STALL_SECONDS);
    }

    public HireWaitTool(HireRegistry hires, long stallSeconds) {
        this.hires = hires;
        this.stallSeconds = stallSeconds;
    }

    @Override
    public String getName() {
        return "hire_wait";
    }

    /**
     * The longest one call waits, 600 seconds, as the person decided (28h z211): each call by a client that cannot be sent the report as a new turn
     * costs its whole conversation, so one long call replaces many short ones. A larger value is refused, not clamped.
     */
    static final int MAX_WAIT_SECONDS = 600;

    @Override
    public String getDescription() {
        return """
            State of a hire and, once finished, its report: call after hire unless your client gets the report as a
            new turn (only the jawata chat does).

            hire_wait(hireId, waitSeconds=0..600): running (idleSeconds, stalled, soFar), finished (report, record,
            newToYou) or unknown. The call returns as soon as the hire finishes. While running, call again.
            action="stop" ends a hire you started and every hire under it.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of("type", "object",
            "properties", Map.of(
                "hireId", Map.of("type", "string", "description", "From hire."),
                "waitSeconds", Map.of("type", "integer", "description", "0 to " + MAX_WAIT_SECONDS + " (default 0); the call returns the moment the hire finishes."),
                "action", Map.of("type", "string", "enum", List.of("wait", "stop"),
                    "description", "wait (default) or stop.")),
            "required", List.of("hireId"));
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        String hireId = arguments == null || !arguments.hasNonNull("hireId") ? "" : arguments.get("hireId").asText().strip();
        if (hireId.isEmpty()) {
            return ToolResponse.invalidParameter("hireId", "name the hire to wait for: the hireId the hire tool answered");
        }
        if ("stop".equals(arguments.path("action").asText("wait"))) {
            return stop(hireId);
        }
        // 28h z211: a wait above the largest is refused, not clamped, and before anything else the wait does.
        if (arguments.path("waitSeconds").asInt(0) > MAX_WAIT_SECONDS) {
            return ToolResponse.invalidParameter("waitSeconds", "the largest wait is " + MAX_WAIT_SECONDS + " seconds; ask for " + MAX_WAIT_SECONDS
                + " or less and call again if the hire is still running");
        }
        // The principal of the jawata chat has a turn its hire's report comes back in (28h z62): it never waits here, and the call answers at once, so
        // the person's next message is not queued behind a poll of minutes.
        if (hires.isRunningPlan(hireId) && hires.reportComesAsTurnForPlan(hireId)) {
            Map<String, Object> now = new LinkedHashMap<>();
            now.put("hireId", hireId);
            now.put("state", "running");
            now.put("reportComesAsTurn", true);
            now.put("next", "end your turn now: this hire's report comes back to you as a new turn, and the person's messages are answered meanwhile; do not call hire_wait again");
            return ToolResponse.success(now);
        }
        int waitSeconds = Math.max(0, Math.min(MAX_WAIT_SECONDS, arguments.path("waitSeconds").asInt(0)));
        long deadline = System.nanoTime() + waitSeconds * 1_000_000_000L;
        while (hires.isRunningPlan(hireId) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hireId", hireId);
        java.util.Optional<JsonNode> report = hires.answerOfPlan(hireId);
        if (hires.isRunningPlan(hireId)) {
            data.put("state", "running");
            // A hire that shows no sign of life is told to its principal here, which is where a client with no chat asks (28h Stage 27, z28).
            long idle = hires.idleSecondsOf(hireId).orElse(0);
            data.put("idleSeconds", idle);
            // 28h z201: the idle is the smallest of the hire and the hires below it, so a lead whose hire works is not stalled and one whose chain is quiet is; the answer still says what it waits for.
            List<String> waitingFor = hires.runningNamesFor(hireId);
            data.put("stalled", idle >= (stallSeconds >= 0 ? stallSeconds : hires.quietCeiling()));
            if (!waitingFor.isEmpty()) {
                data.put("waitingFor", waitingFor);
            }
            // 28h z136: the money and time are seen while the hire still runs, so its hirer can decide to stop it.
            hires.soFarOfPlan(hireId).ifPresent(running -> data.put("soFar",
                org.jawata.mcp.execution.HireReport.soFar(running, hires.usedUnder(hireId))));
        } else if (report.isPresent()) {
            data.put("state", "finished");
            // 28h z52: the caller is told the short report; the whole answer and the changes are in the record it names.
            data.put("report", org.jawata.mcp.execution.HireReport.shortOf(report.get()));
            data.put("newToYou", hires.takeReportOf(hireId));
        } else if (hires.labelOf(hireId).equals(hireId)) {
            data.put("state", "unknown");
        } else {
            // the hire has ended and its report is being kept: not a finished report yet
            data.put("state", "running");
        }
        return ToolResponse.success(data);
    }

    /** A lead ends a running hire of its own, and every hire under it (28h z136); the answer says how many and who. */
    private ToolResponse stop(String hireId) {
        String caller = org.jawata.mcp.models.CallerIdentity.serving().map(org.jawata.mcp.models.CallerIdentity::id).orElse("");
        if (!hires.isRunningPlan(hireId)) {
            return ToolResponse.error("HIRE_NOT_RUNNING", "no running hire has the id " + hireId, "ask hire_wait for its report instead");
        }
        if (!hires.mayBeStoppedBy(hireId, caller)) {
            return ToolResponse.error("NOT_YOUR_HIRE",
                "refused: " + hires.labelOf(hireId) + " was not hired by you, nor by a hire of yours",
                "stop only hires you started; a hire under another lead is that lead's to stop");
        }
        List<String> stopped = hires.stopTree(hireId, caller);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hireId", hireId);
        data.put("state", "stopping");
        data.put("count", stopped.size());
        data.put("stopped", stopped);
        return ToolResponse.success(data);
    }
}
