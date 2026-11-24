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
            the ceiling, 300 seconds unless the host's setting jawata.hire.stallSeconds says another, so a hire that has gone quiet is told to you and not waited for), finished (with report, the hire's own answer,
            and newToYou, false when the report was already handed over, by this tool or to the chat), or unknown (no hire has that id on this engine).
            waitSeconds (0 to 50, default 0) is how long to wait for a running hire before answering running: call again, and between the calls
            you are free to answer the person. A finished report is handed over once as new and marked read, so the chat does not hand it over again.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of("type", "object",
            "properties", Map.of(
                "hireId", Map.of("type", "string", "description", "The hireId the hire tool answered."),
                "waitSeconds", Map.of("type", "integer", "description", "How long to wait for a running hire, 0 to " + MAX_WAIT_SECONDS + " (default 0).")),
            "required", List.of("hireId"));
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        String hireId = arguments == null || !arguments.hasNonNull("hireId") ? "" : arguments.get("hireId").asText().strip();
        if (hireId.isEmpty()) {
            return ToolResponse.invalidParameter("hireId", "name the hire to wait for: the hireId the hire tool answered");
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
            data.put("stalled", idle >= (stallSeconds >= 0 ? stallSeconds : hires.quietCeiling()));
        } else if (report.isPresent()) {
            data.put("state", "finished");
            data.put("report", report.get());
            data.put("newToYou", hires.takeReportOf(hireId));
        } else if (hires.labelOf(hireId).equals(hireId)) {
            data.put("state", "unknown");
        } else {
            // the hire has ended and its report is being kept: not a finished report yet
            data.put("state", "running");
        }
        return ToolResponse.success(data);
    }
}
