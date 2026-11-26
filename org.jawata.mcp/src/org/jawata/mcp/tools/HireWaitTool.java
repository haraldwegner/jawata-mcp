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

    /** The longest one call waits, kept under the shortest client timeout known (Cursor gave up on a 109 s tool call). */
    static final int MAX_WAIT_SECONDS = 50;

    @Override
    public String getDescription() {
        return """
            The state of one hire and, once it has finished, its report: what to call after hire when your client has no turn the report can come back in.

            USAGE: hire_wait(hireId="<the hireId hire answered>" [, waitSeconds=N])

            hire answers at once with a hireId and the hire goes on working on the engine's own thread; its report comes back to the seat that hired
            as a new turn only where jawata itself is that seat (the jawata chat). A client that calls this server's tools is its own principal, so it
            asks here. Answers {state, hireId}: running (with idleSeconds, how long it has shown no sign of life, and stalled, true once that passes
            the ceiling, 300 seconds unless the host's setting jawata.hire.stallSeconds says another, so a hire that has gone quiet is told to you and not waited for;
            a lead with a hire running under it is never stalled, and waitingFor names those hires; soFar: toolSteps, seconds, costUsd and
            contextTokens as its brain last reported them, "no figure" where it sent none, and below, the total of what has ended under it),
            finished (with report, the hire's short report:
            state, checks, number of files changed, money, time and tokens ("no figure" where the brain published none), report, the summary it
            wrote, and record, the file that holds its whole answer and changes; and newToYou, false when the report was already handed over, by
            this tool or to the chat), or unknown (no hire has that id on this engine).
            action="stop" ends a running hire you hired, and every hire under it; it answers who was stopped and how many, and is refused for a hire
            you did not start.
            waitSeconds (0 to 50, default 0) is how long to wait for a running hire before answering running: call again, and between the calls
            you are free to answer the person. A finished report is handed over once as new and marked read, so the chat does not hand it over again.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of("type", "object",
            "properties", Map.of(
                "hireId", Map.of("type", "string", "description", "The hireId the hire tool answered."),
                "waitSeconds", Map.of("type", "integer", "description", "How long to wait for a running hire, 0 to " + MAX_WAIT_SECONDS + " (default 0)."),
                "action", Map.of("type", "string", "enum", List.of("wait", "stop"),
                    "description", "wait (default) answers the hire's state; stop ends a running hire you hired, and every hire under it.")),
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
            // 28h z102: a lead with a hire running under it is waiting for it, not stalled, and the answer says for whom.
            List<String> waitingFor = hires.runningNamesFor(hireId);
            data.put("stalled", waitingFor.isEmpty() && idle >= (stallSeconds >= 0 ? stallSeconds : hires.quietCeiling()));
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
