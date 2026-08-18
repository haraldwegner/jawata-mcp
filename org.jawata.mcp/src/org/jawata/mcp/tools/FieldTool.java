package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.field.FieldEvent;
import org.jawata.mcp.field.FieldPile;
import org.jawata.mcp.field.FieldState;
import org.jawata.mcp.models.ToolResponse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The field lane's ONE front door (Sprint 28b, D3) — the `/report` seat's tool.
 *
 * <p>Actions:</p>
 * <ul>
 *   <li>{@code pile} — the local recording, ranked by error-shape recurrence:
 *       what the seat DETECTS on. Shapes only; nothing here can carry a path,
 *       a message or a symbol, because nothing in the pile can.</li>
 *   <li>{@code mark_posted} — records that a shape was reported (the seat's
 *       RECORD step): it stops nudging and resets the reminder strikes.</li>
 *   <li>{@code silence} — reads or sets the two DISTINCT switches: the
 *       in-session nudge ({@code nudges}) and the periodic reminders
 *       ({@code silenced}). "Tell the agent to go silent" writes the same
 *       state the studio tile's checkbox writes.</li>
 * </ul>
 *
 * <p>One tool rather than four (the collapse-to-39 discipline): every action
 * reads or writes the same two files in {@code <workspace>/field/}.</p>
 *
 * <p><b>No response carries a filesystem path.</b> The {@code /report} seat
 * drafts a PUBLIC issue body straight from these answers, and an absolute path
 * carries the user's account name and the name of whatever he is working on —
 * exactly the content the pile is built to exclude. The seat is told it has no
 * paths and must not invent any; handing it one would make that instruction a
 * lie. {@code FieldToolLeakTest} asserts it on the serialized response of every
 * action, success and refusal alike.</p>
 *
 * <p><b>No project needed.</b> The field lane answers about jawata's own use,
 * not about code — a workspace whose projects failed to load is exactly when
 * an agent most wants to report the failure.</p>
 */
public class FieldTool extends AbstractTool {

    private final Supplier<Path> fieldDir;

    public FieldTool(Supplier<IJdtService> serviceSupplier, Supplier<Path> fieldDir) {
        super(serviceSupplier);
        this.fieldDir = fieldDir;
    }

    @Override
    protected boolean requiresLoadedProject() {
        return false;
    }

    @Override
    public String getName() {
        return "field";
    }

    @Override
    public String getDescription() {
        return """
            The local field recording — what jawata did here, as SHAPES (tool, kind,
            error code, latency bucket, client), never content. The /report seat's tool.

            USAGE: field(action="pile")            — recurring error shapes, most first
                   field(action="mark_posted", shape="run_tests/run/RUNNER_TIMEOUT")
                   field(action="silence")          — read both switches
                   field(action="silence", nudges=false)   — stop the in-session line
                   field(action="silence", silenced=true)  — stop the periodic reminders

            The two switches are DISTINCT: `nudges` is the one-line pointer inside a
            session; `silenced` is the periodic reminder that failures are accumulating.
            Turning one off never turns the other off.""";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "string");
        action.put("enum", List.of("pile", "mark_posted", "silence"));
        action.put("description", "pile: ranked error shapes. mark_posted: record a"
            + " shape as reported (stops its nudge). silence: read the two switches,"
            + " or set either.");

        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("type", "string");
        shape.put("description", "mark_posted: the shape key from `pile`,"
            + " e.g. \"run_tests/run/RUNNER_TIMEOUT\".");

        Map<String, Object> nudges = new LinkedHashMap<>();
        nudges.put("type", "boolean");
        nudges.put("description", "silence: turn the in-session nudge line on or off.");

        Map<String, Object> silenced = new LinkedHashMap<>();
        silenced.put("type", "boolean");
        silenced.put("description", "silence: turn the periodic failure reminders off"
            + " (true) or back on (false) — the same state the studio tile's checkbox"
            + " writes.");

        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("type", "integer");
        limit.put("description", "pile: max shapes returned (default 20).");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", action);
        properties.put("shape", shape);
        properties.put("nudges", nudges);
        properties.put("silenced", silenced);
        properties.put("limit", limit);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String action = arguments == null ? null : arguments.path("action").asText(null);
        if (action == null || action.isBlank()) {
            return ToolResponse.invalidParameter("action",
                "one of pile | mark_posted | silence");
        }
        Path dir = fieldDir.get();
        if (dir == null) {
            return ToolResponse.error("FIELD_UNAVAILABLE",
                "this resident has no field directory, so nothing is being recorded here",
                "field recording needs a workspace root; check health_check's workspace block");
        }
        return switch (action) {
            case "pile" -> pile(dir, arguments);
            case "mark_posted" -> markPosted(dir, arguments);
            case "silence" -> silence(dir, arguments);
            default -> ToolResponse.invalidParameter("action",
                "unknown action '" + action + "'; one of pile | mark_posted | silence");
        };
    }

    private ToolResponse pile(Path dir, JsonNode arguments) {
        int limit = arguments.path("limit").asInt(20);
        FieldPile pileFile = new FieldPile(dir);
        FieldState state = FieldState.read(dir);
        List<FieldEvent> events = pileFile.fold();
        Map<String, Long> shapes = pileFile.countErrorShapes();

        List<Map<String, Object>> ranked = new ArrayList<>();
        shapes.entrySet().stream()
            .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                .reversed())
            .limit(Math.max(1, limit))
            .forEach(entry -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("shape", entry.getKey());
                row.put("count", entry.getValue());
                row.put("posted", state.posted().contains(entry.getKey()));
                ranked.add(row);
            });

        long failures = events.stream().filter(e -> !e.ok()).count();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("shapes", ranked);
        data.put("shapeCount", shapes.size());
        data.put("events", events.size());
        data.put("failures", failures);
        data.put("droppedWrites", pileFile.failedWrites());
        data.put("nudges", state.nudges());
        data.put("silenced", state.silenced());
        return ToolResponse.success(data);
    }

    private ToolResponse markPosted(Path dir, JsonNode arguments) {
        String shape = arguments.path("shape").asText(null);
        if (shape == null || shape.isBlank()) {
            return ToolResponse.invalidParameter("shape",
                "mark_posted needs the shape key from `pile`");
        }
        FieldState state = FieldState.read(dir).withPosted(shape);
        state.recordReportUsed(dir);
        if (!state.write(dir)) {
            return ToolResponse.error("FIELD_STATE_WRITE_FAILED",
                "the shape was NOT recorded as posted — it will nudge again",
                "check that the resident's field directory is writable"
                    + " (health_check's workspace block names the workspace root)");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("shape", shape);
        data.put("posted", state.posted());
        data.put("strikes", FieldState.reminderStrikes(dir));
        return ToolResponse.success(data);
    }

    private ToolResponse silence(Path dir, JsonNode arguments) {
        FieldState state = FieldState.read(dir);
        boolean changed = false;
        if (arguments.has("nudges") && !arguments.get("nudges").isNull()) {
            state.withNudges(arguments.get("nudges").asBoolean());
            changed = true;
        }
        if (arguments.has("silenced") && !arguments.get("silenced").isNull()) {
            state.withSilenced(arguments.get("silenced").asBoolean());
            changed = true;
        }
        if (changed && !state.write(dir)) {
            return ToolResponse.error("FIELD_STATE_WRITE_FAILED",
                "the switch was NOT saved — it still reads as it did before",
                "check that the resident's field directory is writable"
                    + " (health_check's workspace block names the workspace root)");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nudges", state.nudges());
        data.put("silenced", state.silenced());
        data.put("strikes", FieldState.reminderStrikes(dir));
        data.put("changed", changed);
        return ToolResponse.success(data);
    }
}
