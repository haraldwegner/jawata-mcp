package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;

/**
 * Sprint 28f D2 — the retired verb is REFUSED, and the refusal names its replacement.
 *
 * <p><b>No alias, deliberately.</b> "reseed" reads like a refresh and the verb wipes
 * the store before reloading; that is how it took 378 rows to 194 on 2026-09-08 with
 * nobody expecting it. Keeping the old name working would be a silent continuation of
 * exactly the misreading the rename exists to end, so a caller on it gets a loud
 * failure instead — and the failure carries the new name, so nobody has to guess.</p>
 *
 * <p>This is the half a rename cannot give you. {@code rename_symbol} moved the method
 * and the compiler checked every caller; the WIRE name is a string, which no engine
 * reaches and no compiler sees. Seven senders across two repositories had to be found
 * by hand, and three of them were missed by the first search pattern.</p>
 */
class VerbTableTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolResponse call(ExperienceTool tool, String kind) {
        ObjectNode a = JSON.createObjectNode();
        a.put("kind", kind);
        return tool.execute(a);
    }

    @Test
    void the_retired_verb_is_unknown_and_the_refusal_names_its_replacement() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);

            ToolResponse refused = call(tool, "reseed");
            assertFalse(refused.isSuccess(),
                "the old name must not work — an alias would keep the misreading alive");
            String rendered = String.valueOf(refused.getError());
            assertTrue(rendered.contains("wipe_and_import"),
                () -> "and the refusal must hand the caller the name that DOES work,"
                    + " or the rename is a puzzle: " + rendered);
        }
    }

    /**
     * The new name is published, and the CONTROL is that it reaches its own gate.
     *
     * <p>Asserting only that it is not "unknown" would pass against a verb wired to
     * nothing. Called with no {@code confirm}, it must refuse for ITS OWN reason — the
     * destructive-verb gate — which proves the dispatch arrives at the real method.</p>
     */
    @Test
    void the_new_name_is_published_and_reaches_its_own_gate() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);

            ToolResponse refused = call(tool, "wipe_and_import");
            String rendered = String.valueOf(refused.getError());
            assertFalse(rendered.contains("Unknown") || rendered.contains("unknown"),
                () -> "the new name must be in the verb table: " + rendered);
            assertTrue(rendered.contains("confirm"),
                () -> "and it must reach its own confirm gate, which is what proves the"
                    + " dispatch goes to the real method rather than to nothing: "
                    + rendered);
        }
    }
}
