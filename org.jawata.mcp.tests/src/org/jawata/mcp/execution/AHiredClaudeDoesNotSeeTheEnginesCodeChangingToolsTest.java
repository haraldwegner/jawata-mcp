package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REL44-6: a hired Claude is given the engine's code-changing tools only if the seat names them.
 *
 * <p>{@code --allowedTools} PRE-APPROVES the seat's list and does not hide the rest: measured, the
 * init event of a read-only PM hire listed all 44 of the resident's tools, so whether
 * {@code rename_symbol} and the like were refused rested on {@code --permission-mode plan}, an
 * instruction. {@code --disallowedTools} is the CLI's own way to take a tool away, and the engine
 * already knows which of its tools change code ({@code Tool#changesCode}), so the hire hides those
 * the seat did not ask for.</p>
 */
class AHiredClaudeDoesNotSeeTheEnginesCodeChangingToolsTest {

    private static final SelfEndpoint SELF = new SelfEndpoint("http://127.0.0.1:1/mcp", "t");

    @Test
    @DisplayName("given the resident's endpoint and a hidden list, the argv takes those tools away by the names the CLI sees")
    void theArgvHidesTheListedTools() {
        List<String> argv = new ClaudeCodeDialect("claude", 5, null, true, SELF,
            List.of("rename_symbol", "extract")).command("brief", List.of("inspect"));

        int hidden = argv.indexOf("--disallowedTools");
        assertAll(
            () -> assertTrue(hidden >= 0, argv.toString()),
            () -> assertEquals("mcp__jawata__rename_symbol", argv.get(hidden + 1), argv.toString()),
            () -> assertEquals("mcp__jawata__extract", argv.get(hidden + 2), argv.toString()),
            () -> assertEquals("mcp__jawata__inspect", argv.get(argv.indexOf("--allowedTools") + 1),
                "and the seat's own list is still what is pre-approved"));
    }

    @Test
    @DisplayName("nothing to hide, or no endpoint to name the tools through, adds no flag")
    void noListOrNoEndpointAddsNoFlag() {
        assertAll(
            () -> assertFalse(new ClaudeCodeDialect("claude", 5, null, true, SELF, List.of())
                .command("b", List.of("inspect")).contains("--disallowedTools")),
            () -> assertFalse(new ClaudeCodeDialect("claude", 5, null, true, null, List.of("extract"))
                .command("b", List.of("inspect")).contains("--disallowedTools"),
                "without the endpoint the CLI sees the user's own servers, whose names are not these"));
    }

    @Test
    @DisplayName("a seat reaches the flag through SeatHire, and a brain that does not attach the resident is untouched")
    void theSeatPlumbingReachesTheDialect() {
        SeatHire claude = SeatHire.read("---\nname: a\n---\n");
        SeatHire cursor = SeatHire.read("---\nname: a\nbrain: cursor\n---\n");

        assertAll(
            () -> assertTrue(claude.dialect(SELF, List.of("extract")).command("b", List.of())
                .contains("--disallowedTools")),
            () -> assertEquals(cursor.dialect(SELF).command("b", List.of()),
                cursor.dialect(SELF, List.of("extract")).command("b", List.of()),
                "cursor does not attach the resident, so there is nothing to hide from it"));
    }
}
