package org.jawata.mcp.field;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FieldTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
        seedRemindersShown(dir, 2);
        assertEquals(2, FieldState.reminderStrikes(dir), "two reminders went unanswered");
        ToolResponse response = tool(dir).execute(
            args("mark_posted").put("shape", "run_tests/run/RUNNER_TIMEOUT"));
        assertTrue(response.isSuccess());
        assertEquals(0, field(response, "strikes"),
            "a /report use resets the reminder strikes (D9)");
        assertTrue(FieldState.read(dir).posted().contains("run_tests/run/RUNNER_TIMEOUT"));
        assertEquals(0, FieldState.reminderStrikes(dir),
            "and the reset is in the LEDGER, which is the only place strikes live");
    }

    /**
     * 28b closing audit F2: the strike count the tool reports is folded from
     * the ledger the hook actually writes — not from a state-file counter that
     * production never wrote. Before the fix an agent read {@code strikes: 0}
     * forever while the real count advanced.
     */
    @Test
    void the_reported_strikes_come_from_the_ledger_the_hook_writes(@TempDir Path dir) {
        // What the hook appends, in the hook's own format, with nobody having
        // touched state.json at all.
        seedRemindersShown(dir, 3);
        assertEquals(3, field(tool(dir).execute(args("silence")), "strikes"),
            "the tool reports what reminded.log says, not a copy nobody writes");
    }

    @Test
    void a_missing_or_malformed_ledger_reads_as_no_strikes(@TempDir Path dir) throws Exception {
        assertEquals(0, FieldState.reminderStrikes(dir), "no ledger, no strikes");
        Files.writeString(dir.resolve("reminded.log"),
            "not a record\n\n" + "17\tshown\n" + "gibberish\n");
        assertEquals(1, FieldState.reminderStrikes(dir),
            "a half-written line loses itself, never the fold");
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
            .withPosted("a/b/C").write(dir));
        FieldState read = FieldState.read(dir);
        assertFalse(read.nudges());
        assertTrue(read.silenced());
        assertTrue(read.posted().contains("a/b/C"));
    }

    /**
     * The state document carries the two switches and the posted set — and
     * nothing else. The dead {@code remindedAt}/{@code strikes} keys are gone:
     * a value written by nobody and reported as fact is worse than an absent
     * one (28b closing audit, F2).
     */
    @Test
    void the_state_document_carries_no_reminder_bookkeeping(@TempDir Path dir)
            throws Exception {
        assertTrue(FieldState.read(dir).withSilenced(true).write(dir));
        String document = Files.readString(FieldState.file(dir));
        assertFalse(document.contains("remindedAt"), document);
        assertFalse(document.contains("strikes"), document);
        assertTrue(document.contains("\"silenced\":true"),
            "and the switch the hook decides on by substring is still there: " + document);
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

    /** Appends {@code shown} lines exactly as the hook binary does — the only
     *  writer of that marker, and the only place a strike ever comes from. */
    private static void seedRemindersShown(Path dir, int count) {
        try {
            Files.createDirectories(dir);
            StringBuilder ledger = new StringBuilder();
            for (int i = 0; i < count; i++) {
                ledger.append(1000L + i).append("\tshown\n");
            }
            Files.writeString(dir.resolve("reminded.log"), ledger.toString());
        } catch (Exception e) {
            throw new IllegalStateException("could not seed the reminder ledger", e);
        }
    }
}
