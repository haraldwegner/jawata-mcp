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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Sprint 28b, closing-audit F8: the field tool's OUTBOUND seam carries no
 * filesystem path.
 *
 * <p>The {@code /report} seat reads these responses and drafts a bug report the
 * user posts publicly under his own GitHub account. {@code seats/report.md}
 * step 2 tells it "You have no file paths … and you must not invent, infer or
 * ask for any" — so any path the tool hands back is a path the seat has, and
 * an absolute one names the user's account and his workspace. The spec's whole
 * promise is shapes, never content; this test holds the tool to it.</p>
 *
 * <p>The assertion is on the SERIALIZED response — data, error message and
 * hint together — because that is what crosses the wire to the seat, not the
 * one map key a reviewer happens to look at.</p>
 */
class FieldToolLeakTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A path separator followed by a home-directory-shaped segment: the
     *  {@code /home/<user>} · {@code /Users/<user>} · {@code \Users\<user>}
     *  shape that names a person. */
    private static final Pattern HOME_SHAPED =
        Pattern.compile("[/\\\\](?:home|Users|users)[/\\\\][A-Za-z0-9._-]+");

    private static ObjectNode args(String action) {
        return MAPPER.createObjectNode().put("action", action);
    }

    @Test
    void no_field_action_answers_with_a_filesystem_path(@TempDir Path tmp) throws Exception {
        // The field directory is deliberately shaped like a real install:
        // <home>/<user>/<workspace>/field. If any answer echoes its location,
        // the needles below are in the wire form.
        Path dir = tmp.resolve("home").resolve("orvillestamper")
            .resolve("burlington-payments").resolve("field");
        Files.createDirectories(dir);
        new FieldPile(dir).append(new FieldEvent(1L, Token.of("run_tests"), Token.of("run"),
            false, new Token("RUNNER_TIMEOUT"), 3, Token.of("claude_code"),
            new Version(3, 11, 0)));

        FieldTool tool = new FieldTool(() -> null, () -> dir);
        List<ToolResponse> everyAnswer = List.of(
            tool.execute(args("pile")),
            tool.execute(args("pile").put("limit", 1)),
            tool.execute(args("mark_posted").put("shape", "run_tests/run/RUNNER_TIMEOUT")),
            tool.execute(args("silence")),
            tool.execute(args("silence").put("silenced", true)),
            tool.execute(args("silence").put("nudges", false)),
            // The refusal paths answer too, and a hint is as public as a datum.
            tool.execute(args("mark_posted")),
            tool.execute(args("delete_everything")));

        for (ToolResponse answer : everyAnswer) {
            String wire = MAPPER.writeValueAsString(answer);
            assertFalse(HOME_SHAPED.matcher(wire).find(),
                "a field answer carries a home-directory-shaped path — the /report seat"
                    + " drafts a PUBLIC issue from this: " + wire);
            assertFalse(wire.contains("orvillestamper"),
                "a field answer names the user: " + wire);
            assertFalse(wire.contains("burlington-payments"),
                "a field answer names the workspace: " + wire);
            assertFalse(wire.contains(dir.toString()),
                "a field answer echoes the field directory: " + wire);
        }
    }
}
