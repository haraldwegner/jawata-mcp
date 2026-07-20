package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.ToolExperienceStore;
import org.jawata.mcp.learn.ToolExperienceRecorder;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 26a D4 (C3): the edit-switch ML model is RETIRED. Its classes are gone
 * (absence proven), its status kinds answer HONESTLY (say retired — never an
 * empty-result lie, never an error), and the experience loop that replaces it
 * still captures after the retirement.
 */
class EditMlRetirementTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    void the_edit_ml_model_classes_are_gone() {
        for (String fqn : List.of(
                "org.jawata.mcp.learn.OnlineLogreg",
                "org.jawata.mcp.learn.HandTree",
                "org.jawata.mcp.learn.Learner",
                "org.jawata.mcp.learn.RollingRecord",
                "org.jawata.mcp.learn.FeatureVector",
                "org.jawata.mcp.learn.LearnerService")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(fqn),
                fqn + " must be retired (deleted), not merely unused");
        }
    }

    @Test
    void learner_status_says_retired_not_an_empty_lie() {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        try {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            tool.setToolExperienceStore(new ToolExperienceStore(store));
            ToolResponse r = tool.execute(OM.createObjectNode().put("kind", "learner_status"));
            assertTrue(r.isSuccess(), "learner_status is a valid kind, not an error");
            Map<?, ?> data = (Map<?, ?>) r.getData();
            assertEquals("retired", data.get("status"), "it SAYS retired");
            assertEquals(List.of(), data.get("models"), "no model rows");
            assertNotNull(data.get("replacedBy"), "it names what replaced the models");
            assertTrue(((Number) data.get("capturedExperiences")).longValue() >= 0,
                "the live experience-loop capture count is reported (not a fabricated number)");
        } finally {
            store.close();
        }
    }

    @Test
    void train_and_observe_edit_answer_retired_not_error() {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        try {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            for (String kind : List.of("train", "observe_edit")) {
                ToolResponse r = tool.execute(OM.createObjectNode().put("kind", kind));
                assertTrue(r.isSuccess(), kind + " stays a valid, honest kind — not an error");
                assertEquals("retired", ((Map<?, ?>) r.getData()).get("status"), kind);
            }
        } finally {
            store.close();
        }
    }

    @Test
    void the_experience_loop_still_captures_after_retirement() {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        ToolExperienceStore tes = new ToolExperienceStore(store);
        ToolExperienceRecorder rec = new ToolExperienceRecorder(tes);
        rec.onCall("s1", "rename_symbol", OM.createObjectNode().put("symbol", "com.foo.Bar"),
            ToolResponse.success(Map.of("filesModified", List.of("Bar.java"))));
        rec.onCall("s1", "compile_workspace", null, ToolResponse.success(Map.of("errorCount", 0)));
        assertEquals(1L, tes.count(),
            "the experience loop still captures the edit→compile outcome with the models gone");
        store.close();
    }
}
