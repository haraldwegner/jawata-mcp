package org.jawata.mcp.field;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FieldTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28b D3: the field lane's one front door. The seat DETECTS on
 * {@code pile}, RECORDS with {@code mark_posted}, and the two switches live
 * behind {@code silence} — distinct, because turning off the in-session line
 * must never silence the periodic reminders (and vice versa).
 */
class FieldToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static FieldTool tool(Path dir) {
        return new FieldTool(() -> null, () -> dir);
    }

    private static ObjectNode args(String action) {
        return MAPPER.createObjectNode().put("action", action);
    }

    private static FieldEvent failure(String tool, String kind, String code) {
        return new FieldEvent(1L, Token.of(tool), Token.of(kind), false,
            new Token(code), 3, Token.of("claude_code"), new Version(3, 11, 0));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> shapes(ToolResponse response) {
        return (List<Map<String, Object>>) ((Map<String, Object>) response.getData()).get("shapes");
    }

    private static Object field(ToolResponse response, String key) {
        return ((Map<String, Object>) response.getData()).get(key);
    }

    @Test
    void pile_ranks_error_shapes_by_recurrence(@TempDir Path dir) {
        FieldPile pile = new FieldPile(dir);
        pile.append(failure("run_tests", "run", "RUNNER_TIMEOUT"));
        pile.append(failure("run_tests", "run", "RUNNER_TIMEOUT"));
        pile.append(failure("run_tests", "run", "RUNNER_TIMEOUT"));
        pile.append(failure("inspect", "source", "TYPE_NOT_FOUND"));

        ToolResponse response = tool(dir).execute(args("pile"));
        assertTrue(response.isSuccess());
        List<Map<String, Object>> ranked = shapes(response);
        assertEquals("run_tests/run/RUNNER_TIMEOUT", ranked.get(0).get("shape"),
            "the seat detects on recurrence — the most-repeated shape leads");
        assertEquals(3L, ranked.get(0).get("count"));
        assertEquals(Boolean.FALSE, ranked.get(0).get("posted"));
        assertEquals("inspect/source/TYPE_NOT_FOUND", ranked.get(1).get("shape"));
        assertEquals(2, field(response, "shapeCount"));
        assertEquals(4L, field(response, "failures"));
    }

    @Test
    void mark_posted_records_the_shape_and_resets_the_strikes(@TempDir Path dir) {
        new FieldState_TestSeam().seedStrikes(dir, 2);
        ToolResponse response = tool(dir).execute(
            args("mark_posted").put("shape", "run_tests/run/RUNNER_TIMEOUT"));
        assertTrue(response.isSuccess());
        assertEquals(0, field(response, "strikes"),
            "a /report use resets the reminder strikes (D9)");
        FieldState state = FieldState.read(dir);
        assertTrue(state.posted().contains("run_tests/run/RUNNER_TIMEOUT"));
        assertEquals(0, state.strikes());
    }

    @Test
    void a_posted_shape_shows_as_posted_in_the_pile(@TempDir Path dir) {
        new FieldPile(dir).append(failure("run_tests", "run", "RUNNER_TIMEOUT"));
        tool(dir).execute(args("mark_posted").put("shape", "run_tests/run/RUNNER_TIMEOUT"));
        assertEquals(Boolean.TRUE, shapes(tool(dir).execute(args("pile"))).get(0).get("posted"));
    }

    @Test
    void the_two_switches_are_independent(@TempDir Path dir) {
        FieldTool tool = tool(dir);
        // Defaults: the nudge is on, the reminders are not silenced.
        ToolResponse initial = tool.execute(args("silence"));
        assertEquals(Boolean.TRUE, field(initial, "nudges"));
        assertEquals(Boolean.FALSE, field(initial, "silenced"));
        assertEquals(Boolean.FALSE, field(initial, "changed"));

        // Switching off the in-session nudge leaves the reminders alone…
        ToolResponse nudgeOff = tool.execute(args("silence").put("nudges", false));
        assertEquals(Boolean.FALSE, field(nudgeOff, "nudges"));
        assertEquals(Boolean.FALSE, field(nudgeOff, "silenced"),
            "turning off the nudge must not silence the reminders");

        // …and silencing the reminders leaves the nudge switch as the user set it.
        ToolResponse silenced = tool.execute(args("silence").put("silenced", true));
        assertEquals(Boolean.TRUE, field(silenced, "silenced"));
        assertEquals(Boolean.FALSE, field(silenced, "nudges"));

        FieldState onDisk = FieldState.read(dir);
        assertFalse(onDisk.nudges());
        assertTrue(onDisk.silenced());
    }

    @Test
    void the_state_survives_a_round_trip_and_defaults_when_absent(@TempDir Path dir) {
        FieldState fresh = FieldState.read(dir);
        assertTrue(fresh.nudges(), "a missing state file must never silence");
        assertFalse(fresh.silenced());

        assertTrue(FieldState.read(dir).withNudges(false).withSilenced(true)
            .withPosted("a/b/C").withReminderShown(1234L).write(dir));
        FieldState read = FieldState.read(dir);
        assertFalse(read.nudges());
        assertTrue(read.silenced());
        assertTrue(read.posted().contains("a/b/C"));
        assertEquals(1234L, read.remindedAtMillis());
        assertEquals(1, read.strikes());
    }

    @Test
    void an_unknown_action_is_a_named_refusal(@TempDir Path dir) {
        ToolResponse response = tool(dir).execute(args("delete_everything"));
        assertFalse(response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
    }

    @Test
    void mark_posted_without_a_shape_is_refused(@TempDir Path dir) {
        ToolResponse response = tool(dir).execute(args("mark_posted"));
        assertFalse(response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
    }

    @Test
    void the_tool_answers_with_no_project_loaded(@TempDir Path dir) {
        // The field lane answers about jawata's own use — a workspace whose
        // projects failed to load is exactly when an agent wants to report it.
        assertTrue(tool(dir).execute(args("pile")).isSuccess());
    }

    /** Seeds reminder strikes through the real state writer. */
    private static final class FieldState_TestSeam {
        void seedStrikes(Path dir, int strikes) {
            FieldState state = FieldState.read(dir);
            for (int i = 0; i < strikes; i++) {
                state.withReminderShown(1000L + i);
            }
            state.write(dir);
        }
    }
}
