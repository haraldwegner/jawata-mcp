package org.jawata.mcp.field;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FieldTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28e, C3 — <b>the control mcp#74's {@code failedReads} shipped without.</b>
 *
 * <p>{@link FieldPile#fold()} answers with an empty list when the recording cannot be read,
 * so <i>"no failures recorded"</i> and <i>"I could not open the recording"</i> were the same
 * answer — and the second is exactly the state someone is looking at the pile to discover.
 * mcp#74 added a counter for it and put it on the wire.</p>
 *
 * <p><b>Why this class exists.</b> The C3 audit measured the counter's coverage and found one
 * assertion, {@code assertEquals(0L, …)} over an empty {@code @TempDir} — so deleting the
 * increment left every test green. An assertion that only ever sees the zero cannot tell a
 * working counter from an absent one, which is the same shape as the defect the counter was
 * added to fix, one level up.</p>
 *
 * <p><b>The unreadable state is a DIRECTORY where the file belongs.</b> Portable — no
 * permission bits, and root cannot read past it either — and a real state a botched deploy
 * produces. {@code Files.exists} is true, so the early return is passed and the read is
 * genuinely attempted, which is what makes this exercise the catch rather than the guard.</p>
 */
class PileCountsAnUnreadableReadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static FieldTool tool(Path dir) {
        return new FieldTool(() -> null, () -> dir);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pile(Path dir) {
        ObjectNode args = MAPPER.createObjectNode().put("action", "pile");
        ToolResponse resp = tool(dir).execute(args);
        assertTrue(resp.isSuccess(), "the pile answers even when it cannot be read: " + resp);
        return (Map<String, Object>) resp.getData();
    }

    private static long failedReads(Map<String, Object> data) {
        Object v = data.get("failedReads");
        assertTrue(v instanceof Number, "the response carries failedReads: " + data);
        return ((Number) v).longValue();
    }

    /**
     * The two states must not answer alike. Asserting only the unreadable case would pass
     * against a counter wired to a constant, so the clean case is measured in the same run
     * and the two are required to DISAGREE.
     */
    @Test
    @DisplayName("a recording that cannot be READ is not the same answer as one that is empty")
    void anUnreadablePileIsCountedAndDiffersFromAnEmptyOne(@TempDir Path clean, @TempDir Path broken)
            throws IOException {
        // The empty case: no pile file at all, which is a clean install.
        long onEmpty = failedReads(pile(clean));

        // The unreadable case: the pile's own name, taken by a directory.
        Files.createDirectories(broken.resolve(FieldPile.FILE_NAME));
        long onUnreadable = failedReads(pile(broken));

        assertAll(
            () -> assertEquals(0L, onEmpty,
                "a clean install has recorded no failed reads"),
            () -> assertTrue(onUnreadable > 0L,
                "a pile that cannot be read reports it: failedReads was " + onUnreadable),
            () -> assertNotEquals(onEmpty, onUnreadable,
                "the whole point of the counter is that these two states answer differently"));
    }
}
