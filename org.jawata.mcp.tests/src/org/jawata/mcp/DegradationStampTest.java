package org.jawata.mcp;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.jawata.core.IJdtService;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.AbstractTool;
import org.jawata.mcp.tools.HealthCheckTool;
import org.jawata.mcp.tools.LoadProjectTool;
import org.jawata.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * mcp#12 — <b>no silent degradation.</b> Plus #21 and #35, which are the two ways the
 * resident got its own state wrong badly enough to need it.
 *
 * <p>jawata is a tool for agents, and an agent routes around friction silently: a
 * capability that degrades without complaining is never noticed. The channel that
 * provably reaches the agent is the RESPONSE ITSELF, so a resident in any degraded state
 * stamps a one-line notice into every response's meta until the state is cured, and
 * {@code health_check} mirrors the same list.</p>
 *
 * <p><b>The refusal half is the one that was missing.</b> Before this, the nearest thing
 * to a stamp was the store notice appended through {@code appendSteering}, which no-ops on
 * an error — so a degraded resident was silent on exactly the answers a caller is most
 * likely to misread as a fact about their own code.</p>
 */
class DegradationStampTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @org.junit.jupiter.api.extension.RegisterExtension
    org.jawata.mcp.fixtures.TestProjectHelper projects =
        new org.jawata.mcp.fixtures.TestProjectHelper();

    @AfterEach
    void clearState() {
        JawataApplication.clearLoadingStateForTest();
        ResidentDegradation.clearForTest();
    }

    /** A tool that answers, or refuses, on demand — the STAMP is the subject, not the tool. */
    private static final class ProbeTool extends AbstractTool {
        private final boolean refuse;

        ProbeTool(boolean refuse) {
            super(() -> null);
            this.refuse = refuse;
        }

        @Override
        protected boolean requiresLoadedProject() {
            return false;
        }

        @Override
        public String getName() {
            return refuse ? "probe_refuse" : "probe_ok";
        }

        @Override
        public String getDescription() {
            return "probe";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
            return refuse
                ? ToolResponse.error("PROBE_REFUSED", "the probe declined", "no hint")
                : ToolResponse.success(Map.of("ok", true));
        }
    }

    private static ToolRegistry registryWith(ProbeTool tool) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        return registry;
    }

    private static String steeringOf(ToolResponse r) {
        return r.getMeta() == null ? null : r.getMeta().getSteering();
    }

    // ================================================================= #12

    @Test
    @DisplayName("mcp#12: a degraded resident stamps EVERY response — the success and the refusal alike")
    void everyResponseCarriesTheStamp() throws Exception {
        ResidentDegradation.declare("experience-store",
            "EXPERIENCE STORE DEGRADED — serving a NON-PERSISTENT in-memory store");

        ToolResponse ok = registryWith(new ProbeTool(false))
            .callTool("probe_ok", OM.createObjectNode());
        ToolResponse refused = registryWith(new ProbeTool(true))
            .callTool("probe_refuse", OM.createObjectNode());

        assertAll(
            () -> assertTrue(ok.isSuccess(), "the probe answers"),
            () -> assertNotNull(steeringOf(ok), "a success carries the stamp"),
            () -> assertTrue(steeringOf(ok).startsWith("DEGRADED: "),
                "and it leads with it, so it is the first thing read: " + steeringOf(ok)),
            () -> assertTrue(steeringOf(ok).contains("NON-PERSISTENT"),
                "carrying the component's own words: " + steeringOf(ok)),

            // THE HALF THAT WAS MISSING. appendSteering no-ops on an error, so before
            // this every refusal issued by a degraded resident said nothing about it.
            () -> assertFalse(refused.isSuccess(), "the probe refuses"),
            () -> assertNotNull(steeringOf(refused),
                "a REFUSAL carries the stamp too — it is the answer most likely to be "
                    + "misread as a fact about the caller's own code"),
            () -> assertTrue(steeringOf(refused).contains("NON-PERSISTENT"),
                "got: " + steeringOf(refused)));
    }

    @Test
    @DisplayName("mcp#12: health_check mirrors the SAME notices the responses carry")
    void healthCheckMirrorsTheSameNotices() {
        ResidentDegradation.declare("catalogue", "DEGRADED — catalogue namespace(s) missing");

        Map<?, ?> status = (Map<?, ?>) health().getData();
        Object degraded = status.get("degraded");

        assertNotNull(degraded, "health_check reports the degraded list");
        assertEquals(ResidentDegradation.notices(), degraded,
            "and it is the SAME list, not a second copy that can disagree: " + degraded);
        assertTrue(String.valueOf(degraded).contains("catalogue namespace"), "got: " + degraded);
    }

    @Test
    @DisplayName("THE CONTROL — a healthy resident stamps nothing at all, and health_check omits the key")
    void aHealthyResidentIsSilent() throws Exception {
        // Without this, every assertion above would pass against a stamp that is
        // permanently on — which is a noise line, not a signal.
        JawataApplication.setLoadingStateForTest(ProjectLoadingState.LOADED, null);

        ToolResponse ok = registryWith(new ProbeTool(false))
            .callTool("probe_ok", OM.createObjectNode());

        assertAll(
            () -> assertNull(ResidentDegradation.stamp(), "nothing is degraded, so there is no stamp"),
            // NOT "the meta is empty": a healthy response still carries its ordinary
            // steering line, and asserting null there would have been a claim about
            // the steering mechanism rather than about this one. The claim is narrower
            // and is the one that matters — no DEGRADED line is added.
            () -> assertFalse(String.valueOf(steeringOf(ok)).contains("DEGRADED"),
                "a healthy resident adds no degradation line: " + steeringOf(ok)),
            () -> assertFalse(((Map<?, ?>) health().getData()).containsKey("degraded"),
                "health_check omits the key rather than reporting an empty list — an always-"
                    + "present empty array makes 'nothing is wrong' and 'we did not look' "
                    + "render identically"));
    }

    // ================================================================= #21

    @Test
    @DisplayName("mcp#21: a tool answering while the workspace LOADS says so — success is not completeness")
    void anAnswerDuringLoadingSaysTheWorkspaceIsStillLoading() throws Exception {
        // The measured shape: project(action=list) answered {"success":true,"projects":[]}
        // while health_check knew the workspace was still loading. health_check knew;
        // the answer the agent actually read did not.
        JawataApplication.setLoadingStateForTest(ProjectLoadingState.LOADING, null);

        ToolResponse ok = registryWith(new ProbeTool(false))
            .callTool("probe_ok", OM.createObjectNode());

        assertTrue(ok.isSuccess(), "a partial answer is still an answer — it is not refused");
        String said = steeringOf(ok);
        assertNotNull(said, "but it must not be silent about being partial");
        assertAll(
            () -> assertTrue(said.contains("still LOADING"), "got: " + said),
            () -> assertTrue(said.contains("not yet a real absence"),
                "the load-bearing half: an empty list here does NOT mean nothing is there. "
                    + "got: " + said));
    }

    // ================================================================= #35

    @Test
    @DisplayName("mcp#35: a successful load_project clears the boot's verdict — the alarm stops")
    void aSuccessfulLoadClearsAStaleFailure() throws Exception {
        // The real sequence: boot fails, the agent runs load_project, it works — and
        // health_check went on reporting the boot's error for the rest of the process.
        JdtServiceImpl copy = projects.loadProjectCopy("simple-maven");
        java.nio.file.Path root = copy.getProjectRoot();

        JawataApplication.setLoadingStateForTest(ProjectLoadingState.FAILED,
            "all 1 workspace project(s) FAILED to load — first: Maven resolution failed");

        Map<?, ?> before = (Map<?, ?>) health().getData();
        assertEquals("Project load failed", before.get("status"), "the alarm is standing");
        assertNotNull(before.get("degraded"), "and it is stamped: " + before);

        // Drive the TOOL, not the state setter — the claim is that load_project clears it,
        // and calling noteWorkspaceLoaded() here would assert that a method exists rather
        // than that anything calls it.
        LoadProjectTool load = new LoadProjectTool(() -> null, s -> { });
        ToolResponse loaded = load.execute(
            OM.createObjectNode().put("projectPath", root.toString()));
        assertTrue(loaded.isSuccess(), "the load itself must succeed: " + loaded.getError());

        Map<?, ?> after = (Map<?, ?>) health().getData();
        assertAll(
            () -> assertEquals("Ready", after.get("status"),
                "the boot's verdict is gone, because it is no longer true: " + after),
            () -> assertFalse(after.containsKey("degraded"),
                "and nothing is stamped any more: " + after.get("degraded")),
            () -> assertNull(ResidentDegradation.stamp(),
                "so later responses stop carrying it too"));
    }

    // ================================================================= helper

    /** health_check over the live application state, which is what both channels read. */
    private static ToolResponse health() {
        return new HealthCheckTool(
            () -> false,
            () -> 42,
            JawataApplication::getLoadingState,
            JawataApplication::getLoadingError,
            () -> null).execute(OM.createObjectNode());
    }
}
