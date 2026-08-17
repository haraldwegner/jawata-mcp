package org.jawata.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.core.IJdtService;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.AbstractTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * mcp#28 / #29 / #32 — <b>"a project that failed to load" is a state, not an
 * accident.</b>
 *
 * <p>Filed as three bugs, they are one absence. On a workspace whose every
 * project failed to load (a Maven resolution failure, live on Windows
 * 2026-08-17) the service object EXISTS and is empty, because
 * {@code loadFromWorkspaceJson} assigns it before it checks the failures. So
 * {@code search_symbols} dereferenced a null search service and answered
 * INTERNAL_ERROR "this may be a bug" (#28) — on exactly the path a redirected
 * agent lands on.
 *
 * <p>This pins the gate: it reads <b>are there projects to answer from</b>, not
 * "is the service reference non-null" (which a failed load satisfies) and not
 * the loading enum alone (which is sticky, so a workspace that RECOVERS through
 * {@code load_project} would stay refused forever).
 */
class LoadFailureSeamTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @org.junit.jupiter.api.extension.RegisterExtension
    org.jawata.mcp.fixtures.TestProjectHelper projects =
        new org.jawata.mcp.fixtures.TestProjectHelper();

    /** A tool that WOULD answer if the gate let it — the gate is the subject. */
    private static final class ProbeTool extends AbstractTool {
        private boolean bodyRan;
        private final boolean needsProjects;

        ProbeTool(IJdtService service, boolean needsProjects) {
            super(() -> service);
            this.needsProjects = needsProjects;
        }

        @Override
        protected boolean requiresLoadedProject() {
            return needsProjects;
        }

        @Override
        public String getName() {
            return "probe";
        }

        @Override
        public String getDescription() {
            return "probe";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return Map.of("type", "object");
        }

        @Override
        protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
            bodyRan = true;
            return ToolResponse.success(Map.of("ok", true));
        }
    }

    /**
     * The real shape a failed load leaves behind: a live service holding no
     * projects. Not a mock — the production class with nothing loaded into it.
     */
    private static IJdtService emptyService() {
        return new JdtServiceImpl();
    }

    @AfterEach
    void clearState() {
        JawataApplication.clearLoadingStateForTest();
    }

    @Test
    @DisplayName("a load-failed workspace answers PROJECT_LOAD_FAILED with the reason — never an NPE")
    void aLoadFailedWorkspaceIsRefusedWithItsReason() {
        JawataApplication.setLoadingStateForTest(ProjectLoadingState.FAILED,
            "all 1 workspace project(s) FAILED to load — first: jawata-mcp: Maven resolution failed");
        ProbeTool tool = new ProbeTool(emptyService(), true);

        ToolResponse response = tool.execute(OM.createObjectNode());

        assertFalse(response.isSuccess(), "an empty model cannot answer about code");
        assertFalse(tool.bodyRan,
            "the tool body must not run against an empty model — that is where the null "
                + "search service was dereferenced (mcp#28)");
        assertEquals("PROJECT_LOAD_FAILED", response.getError().getCode(),
            "the answer names the real state instead of INTERNAL_ERROR 'this may be a bug'");
        String said = response.getError().getMessage() + " " + response.getError().getHint();
        assertTrue(said.contains("Maven resolution failed"),
            "and it carries the load failure so the agent can tell the user what to fix: " + said);
    }

    @Test
    @DisplayName("an empty workspace with no failure reads as not-loaded, not as failed")
    void anEmptyWorkspaceWithoutAFailureSaysNotLoaded() {
        ProbeTool tool = new ProbeTool(emptyService(), true);

        ToolResponse response = tool.execute(OM.createObjectNode());

        assertFalse(response.isSuccess());
        assertEquals("PROJECT_NOT_LOADED", response.getError().getCode());
    }

    @Test
    @DisplayName("the RUNTIME tools still answer on a failed workspace — debug needs a JVM, not a model")
    void runtimeToolsAreNotGatedOnProjects() {
        JawataApplication.setLoadingStateForTest(ProjectLoadingState.FAILED, "Maven resolution failed");
        ProbeTool runtimeTool = new ProbeTool(emptyService(), false);

        ToolResponse response = runtimeTool.execute(OM.createObjectNode());

        assertTrue(response.isSuccess(),
            "debug(discover) and profile(native_hs_err) are what still works when the model does "
                + "not — gating them would take away the last tools that answer");
        assertTrue(runtimeTool.bodyRan);
    }

    @Test
    @DisplayName("the gate is emptiness, not the sticky enum: a recovered workspace answers again")
    void aRecoveredWorkspaceIsNotStuckOnTheFailedEnum() throws Exception {
        // The enum is written only on the boot paths — a later successful
        // load_project never clears it. Gating on FAILED would therefore refuse
        // every tool on a workspace that has since recovered.
        JawataApplication.setLoadingStateForTest(ProjectLoadingState.FAILED, "the old failure");
        IJdtService loaded = projects.loadProjectCopy("simple-maven");
        ProbeTool tool = new ProbeTool(loaded, true);

        ToolResponse response = tool.execute(OM.createObjectNode());

        assertTrue(response.isSuccess(),
            "a workspace with a readable project answers, whatever the stale enum says");
        assertTrue(tool.bodyRan);
    }
}
