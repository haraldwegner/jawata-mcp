package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.StoreBackups;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.Test;

/**
 * REL44-33 — what the experience tool says when the safety copy before a destructive operation could
 * not be made clean in three attempts. The attempts, the deletion and the override themselves are held
 * in {@code ACopyWhoseCountDisagreesWithItsRowsIsNotKeptTest}; this holds the answer the user reads and
 * that the flag that lets them go on is published.
 */
class AnUncleanSafetyCopyRefusesTheOperationTest {

    @Test
    void theAnswerIsAPreconditionRefusalThatNamesTheWayOn() {
        ToolResponse r = ExperienceTool.cleanCopyRefusal(
            new StoreBackups.UncleanCopyException("wipe", 3, "COUNT(*) says 51 but 50 rows read"));

        assertFalse(r.isSuccess(), "the operation did not run");
        String shown = String.valueOf(r.getError());
        assertTrue(shown.contains("PRECONDITION_NOT_MET"), shown);
        assertTrue(shown.contains("proceed_without_clean_backup=true"), shown);
        assertTrue(shown.contains("COUNT(*) says 51 but 50 rows read"), shown);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theFlagThatLetsTheUserGoOnIsPublishedAsABoolean() {
        try (H2ExperienceStore store = H2ExperienceStore.openMemory()) {
            Map<String, Object> props = (Map<String, Object>) new ExperienceTool(() -> null, store)
                .getInputSchema().get("properties");

            Map<String, Object> flag = (Map<String, Object>) props.get("proceed_without_clean_backup");
            assertEquals("boolean", flag.get("type"));
        }
    }
}
