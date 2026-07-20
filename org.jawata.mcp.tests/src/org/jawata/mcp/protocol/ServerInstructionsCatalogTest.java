package org.jawata.mcp.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 26a D1 (C4): the capability catalog surfaced to every client via the
 * MCP initialize {@code instructions} field carries DEEP how-to (not just tool
 * names) for the reflex-relevant families, and markets the runtime tools as the
 * ZERO-code-change path so the agent reaches for them instead of a stopwatch or
 * debug-logging.
 */
class ServerInstructionsCatalogTest {

    private static final String CAT = McpProtocolHandler.SERVER_INSTRUCTIONS;

    private static void has(String needle, String why) {
        assertTrue(CAT.contains(needle), why + " — missing: '" + needle + "'");
    }

    @Test
    void the_change_family_carries_how_to_not_just_names() {
        has("auto_apply=false", "the staging how-to (review the diff before apply), not just 'use rename'");
        has("reversible via undo", "the reversibility how-to");
        has("architect review", "names that a large/structural edit is gated");
    }

    @Test
    void the_runtime_tools_are_marketed_as_zero_code_change() {
        // Both runtime pitches say ZERO code change — the reason to prefer them
        // over the reflex.
        int zeroChange = CAT.split("ZERO code change", -1).length - 1;
        assertTrue(zeroChange >= 2,
            "both debug and profile are pitched as the zero-code-change path (found "
                + zeroChange + " of 2)");
    }

    @Test
    void the_debug_reflex_is_countered_with_how_to() {
        has("debug:", "the debug tool is named as a family entry");
        has("logpoint", "how to read a value at runtime without editing source");
        has("read LIVE values", "the how-to phrasing, not a bare tool name");
        has("System.out/logger", "it names the debug-armor reflex it replaces");
    }

    @Test
    void the_profile_reflex_is_countered_with_how_to() {
        has("profile:", "the profile tool is named as a family entry");
        has("sample the running JVM", "the how-to, not a bare tool name");
        has("nanoTime", "it names the hand-rolled-stopwatch reflex it replaces");
        has("hotspot", "profile names the hotspot as a symbol");
    }

    @Test
    void the_search_and_grep_guidance_is_unchanged_in_spirit() {
        has("search_symbols", "the search entry survives");
        has("grep is a FALLBACK ONLY", "grep stays a fallback — the guard's stance is unchanged");
        has("fully-qualified name", "the FQN how-to for addressing symbols");
    }
}
