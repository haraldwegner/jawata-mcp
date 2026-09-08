package org.jawata.mcp.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.jawata.core.IJdtService;
import org.jawata.mcp.field.FieldPile;
import org.jawata.mcp.field.FieldState;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;

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

    /**
     * mcp#42: the in-flight registry, so {@code pile} can report calls that never came
     * back. Nullable — a FieldTool built without one answers about the pile alone, and
     * says so by reporting an empty {@code neverReturned} rather than omitting the key.
     */
    private final Supplier<org.jawata.mcp.field.InFlightCalls> inFlight;

    public FieldTool(Supplier<IJdtService> serviceSupplier, Supplier<Path> fieldDir) {
        this(serviceSupplier, fieldDir, null);
    }

    /** mcp#42: the wiring the application uses — the registry's own in-flight registry. */
    public FieldTool(Supplier<IJdtService> serviceSupplier, Supplier<Path> fieldDir,
            Supplier<org.jawata.mcp.field.InFlightCalls> inFlight) {
        super(serviceSupplier);
        this.fieldDir = fieldDir;
        this.inFlight = inFlight;
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
                   field(action="pile", sinceDays=7)  — only what is still happening
                   field(action="mark_posted", shape="run_tests/run/RUNNER_TIMEOUT")
                   field(action="silence")          — read both switches
                   field(action="silence", nudges=false)   — stop the in-session line
                   field(action="silence", silenced=true)  — stop the periodic reminders

            The two switches are DISTINCT: `nudges` is the one-line pointer inside a
            session; `silenced` is the periodic reminder that failures are accumulating.
            Turning one off never turns the other off.

            RANKING IS NOT RECENCY. `pile` ranks by how OFTEN a shape recurred over the
            whole recording, which on a long-lived install is cumulative over its entire
            life — so the top row may be a fault fixed months ago. Every row therefore
            carries `lastSeenDaysAgo`, and the response carries the span its counts were
            taken over; `sinceDays` narrows the fold and then reports `lifetimeEvents`
            beside it, so the two can be compared without a second call. A count read
            without its span cannot tell a live failure rate from a historical total.""";
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

        Map<String, Object> sinceDays = new LinkedHashMap<>();
        sinceDays.put("type", "integer");
        sinceDays.put("description", "pile: count only events from the last N days"
            + " (default 0 = the whole recording). Ranking is by RECURRENCE over that"
            + " span, so on a long-lived install the top shape may be a fault fixed"
            + " months ago; narrow with this, or read each row's lastSeenDaysAgo. A"
            + " narrowed call also returns lifetimeEvents, so the two are comparable"
            + " without a second call.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", action);
        properties.put("shape", shape);
        properties.put("nudges", nudges);
        properties.put("silenced", silenced);
        properties.put("limit", limit);
        properties.put("sinceDays", sinceDays);

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

        // mcp#74: the pile ranked shapes by a LIFETIME count and said nothing about WHEN, so
        // a shape at 323 could equally be a live fault or one fixed months ago — and the
        // issue reporting it could only be filed as a field report for exactly that reason.
        // sinceDays narrows the fold; 0 (the default) keeps every event, so an existing
        // caller sees the same population it always did, now with its dates attached.
        long now = System.currentTimeMillis();
        int sinceDays = Math.max(0, arguments.path("sinceDays").asInt(0));
        long sinceMillis = sinceDays == 0 ? 0 : now - (sinceDays * 86_400_000L);

        Map<String, FieldPile.ShapeStat> shapes = pileFile.errorShapes(sinceMillis);
        FieldPile.Span window = pileFile.span(sinceMillis);
        FieldPile.Span lifetime = sinceDays == 0 ? window : pileFile.span(0);

        List<Map<String, Object>> ranked = new ArrayList<>();
        shapes.entrySet().stream()
            .sorted(Comparator.<Map.Entry<String, FieldPile.ShapeStat>>comparingLong(
                e -> e.getValue().count()).reversed())
            .limit(Math.max(1, limit))
            .forEach(entry -> {
                FieldPile.ShapeStat stat = entry.getValue();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("shape", entry.getKey());
                row.put("count", stat.count());
                // THE FIELD mcp#74 IS ABOUT. A rank says which shape recurs most; only this
                // says whether it is still happening. Days rather than a raw instant because
                // the question a reader has is "is this current", not "at what o'clock".
                row.put("lastSeenDaysAgo", stat.daysSinceLast(now));
                row.put("firstSeenMillis", stat.firstMillis());
                row.put("lastSeenMillis", stat.lastMillis());
                row.put("posted", state.posted().contains(entry.getKey()));
                ranked.add(row);
            });

        // mcp#42: THE CALLS THAT NEVER CAME BACK. Everything above is folded from the
        // pile, and the pile is written when a call FINISHES — so a hang contributes
        // nothing to any of it, and the worst failure mode a user can hit was the one
        // shape /report structurally could not surface. Measured 2026-08-21: two hung
        // inspect(kind=landmarks) calls, and a pile reporting three shapes over 1303
        // events with no inspect/landmarks row at all.
        //
        // Reported as its own list rather than folded into `shapes`, because these are
        // not a count of past events: they are outstanding NOW, and the number that
        // matters is how long each has been stuck.
        List<Map<String, Object>> stuck = new ArrayList<>();
        if (inFlight != null) {
            for (org.jawata.mcp.field.InFlightCalls.Call call
                    : inFlight.get().outstanding(
                        org.jawata.mcp.field.InFlightCalls.DEFAULT_STUCK_MS)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("shape", call.shape());
                row.put("outstandingMs", call.outstandingMs(now));
                stuck.add(row);
            }
        }

        long failures = shapes.values().stream().mapToLong(FieldPile.ShapeStat::count).sum();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("shapes", ranked);
        data.put("shapeCount", shapes.size());
        data.put("events", window.events());
        data.put("failures", failures);
        // mcp#74: a count NEVER travels without the span it was taken over. "548 failures"
        // is not a fact a reader can act on until they know whether it is a week or a year,
        // and the issue that reported this one had to say so in prose because the response
        // could not. Zeros mean an empty pile, not 1970 — the events count says which.
        data.put("coversFromMillis", window.fromMillis());
        data.put("coversToMillis", window.toMillis());
        data.put("coversDays", window.events() == 0
            ? 0 : Math.max(1, (window.toMillis() - window.fromMillis()) / 86_400_000L));
        data.put("windowDays", sinceDays);           // 0 = the whole recording
        if (sinceDays > 0) {
            // Both numbers, so a narrowed call can be compared with the total WITHOUT a
            // second call — which is the comparison that tells a live fault from a fixed one.
            data.put("lifetimeEvents", lifetime.events());
        }
        // A read that FAILED is not a recording that is empty. Every count above is a floor
        // when this is non-zero, and the response says so rather than leaving the reader to
        // infer it from a log they may not have.
        data.put("failedReads", pileFile.failedReads());
        // Always present, even when empty: an absent key would make "nothing is stuck"
        // and "this build cannot tell" the same answer, which is the defect one level up.
        data.put("neverReturned", stuck);
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
