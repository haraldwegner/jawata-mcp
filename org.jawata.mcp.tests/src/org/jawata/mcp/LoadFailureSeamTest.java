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

    /**
     * A tool that WOULD answer if the gate let it — the gate is the subject.
     *
     * <p><b>Two axes, because the gate has two.</b> {@code needsProjects} is what
     * the tool DECLARES; {@code touchesService} is what its body DOES. The
     * fixture carried only the first, and every test built it with a real or
     * empty service — so the branch that hands a body a <i>null</i> service was
     * unreachable from any test, which is how the Sprint 28b gate fix shipped
     * with an unguarded read behind it (defect #2).
     */
    private static final class ProbeTool extends AbstractTool {
        private boolean bodyRan;
        private boolean sawNullService;
        private final boolean needsProjects;
        private final boolean touchesService;

        ProbeTool(IJdtService service, boolean needsProjects) {
            this(service, needsProjects, false);
        }

        ProbeTool(IJdtService service, boolean needsProjects, boolean touchesService) {
            super(() -> service);
            this.needsProjects = needsProjects;
            this.touchesService = touchesService;
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
            sawNullService = service == null;
            if (touchesService) {
                // The obligation the seam CREATES. A tool that declared
                // independence is entered with whatever the supplier holds —
                // including null — so a body that reads the model has to say so
                // in its own words. Reading unguarded here is precisely the
                // ProfileTool defect: the NPE leaves execute() and the
                // dispatcher wraps it into INTERNAL_ERROR "this may be a bug".
                if (service == null) {
                    return ToolResponse.projectNotLoaded();
                }
                return ToolResponse.success(Map.of("projects", service.allProjects().size()));
            }
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

    // ==================================================================
    // The SECOND axis (Sprint 28b): service-nullity.
    //
    // The gate's state space is requiresLoadedProject() x service-nullity,
    // but every test above supplies a real or empty service — never null.
    // The null column was therefore untested, and the fix that opened it
    // shipped an unguarded read behind it. These three pin that column.
    // ==================================================================

    @Test
    @DisplayName("null service x declares independence: the body RUNS — the override is not silently un-done")
    void aNullServiceStillRunsAToolThatDeclaredIndependence() {
        ProbeTool tool = new ProbeTool(null, false);

        ToolResponse response = tool.execute(OM.createObjectNode());

        assertTrue(response.isSuccess(),
            "debug/profile/field say they answer about the RUNTIME rather than the model; "
                + "refusing them when no service exists at all takes them away at exactly "
                + "the moment an agent reaches for them");
        assertTrue(tool.bodyRan, "the body is what answers — the gate must not stand in for it");
        assertTrue(tool.sawNullService,
            "and the body is handed a NULL service: that is the contract every "
                + "project-independent tool has to be written against");
    }

    @Test
    @DisplayName("null service x requires a project: PROJECT_NOT_LOADED, and the body never runs")
    void aNullServiceRefusesAToolThatRequiresAProject() {
        ProbeTool tool = new ProbeTool(null, true);

        ToolResponse response = tool.execute(OM.createObjectNode());

        assertFalse(response.isSuccess());
        assertEquals("PROJECT_NOT_LOADED", response.getError().getCode(),
            "the ordinary tool is still gated — opening the null column for the runtime "
                + "tools must not open it for everyone");
        assertFalse(tool.bodyRan);
    }

    @Test
    @DisplayName("an independent tool whose body READS the model answers its own typed refusal, never an NPE")
    void anIndependentToolThatReadsTheModelRefusesInItsOwnWords() {
        ProbeTool tool = new ProbeTool(null, false, true);

        ToolResponse response = assertDoesNotThrow(
            () -> tool.execute(OM.createObjectNode()),
            "an UNGUARDED read of the null service throws straight out of execute(); the "
                + "dispatcher then wraps it into INTERNAL_ERROR 'this may be a bug' — the "
                + "same shape mcp#28 was filed for, re-introduced one layer down");

        assertTrue(tool.bodyRan);
        assertTrue(tool.sawNullService);
        assertFalse(response.isSuccess());
        assertEquals("PROJECT_NOT_LOADED", response.getError().getCode(),
            "declaring independence buys the body the right to RUN, not a non-null service: "
                + "whatever it reads, it owns the refusal");
        assertNotEquals("INTERNAL_ERROR", response.getError().getCode(),
            "an agent can act on 'no project is loaded'; it can do nothing with a "
                + "NullPointerException reported as a bug in us");
    }
}
