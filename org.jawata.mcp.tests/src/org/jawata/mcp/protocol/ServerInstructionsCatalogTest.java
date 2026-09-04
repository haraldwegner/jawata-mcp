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

    /**
     * Stage 6a (M9): the catalog must not name a tool that no longer ships.
     *
     * <p><b>One direction, and the other one is not available.</b> M9 asks that this text be
     * reconciled with the published tools "by equality". It cannot be, and a check written
     * that way could never fail: the catalog opens by saying it explains how to drive each
     * FAMILY, and names roughly sixteen tools of forty-two on purpose. Requiring it to name
     * all of them would demand a different document; requiring every name in it to be live is
     * satisfied by whatever it happens to contain, because those names were chosen from the
     * live set to begin with.</p>
     *
     * <p>What CAN drift — and did twice on the front page in this same sprint — is a document
     * still naming a tool that was folded away. So the subject is the RETIRED names, the
     * population {@code ToolRegistry} already keeps for its did-you-mean pointers, read from
     * that map rather than written out here. Stage 1 retired six in one change and this text
     * was not part of that change.</p>
     */
    @Test
    void the_catalog_names_no_tool_that_has_been_retired() {
        java.util.Set<String> retired = org.jawata.mcp.tools.ToolRegistry.retiredNames();
        java.util.List<String> stale = new java.util.ArrayList<>();
        for (String name : retired) {
            // Whole-word, because several retired names share a head with a live one —
            // `extract_method` with `extract`, `move_class` with `move`.
            if (CAT.matches("(?s).*\\b" + java.util.regex.Pattern.quote(name) + "\\b.*")) {
                stale.add(name);
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(), stale,
            "the catalog every client reads at connect time names a tool that no longer"
                + " ships. A client told to call it gets a not-found and a did-you-mean,"
                + " which is exactly the drift this checks for: " + stale);
        // PROOF OF LIFE: an empty retired set makes the loop vacuous, and that set is read
        // from production, so it can shrink without this file being touched.
        assertTrue(retired.size() >= 18,
            "the retired-name map is the population this sweeps — eighteen or more today."
                + " A sudden shrink means the sweep looks at less than it thinks. Found: "
                + retired.size());
    }
}
