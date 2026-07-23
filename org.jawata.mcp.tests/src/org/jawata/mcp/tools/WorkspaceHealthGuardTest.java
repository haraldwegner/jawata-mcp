package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.shared.WorkspaceHealth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard: <b>a refactoring must not run against a workspace it cannot fully read.</b>
 *
 * <p>A rename rewrites references, and it finds them through the Java model. A project the
 * model cannot read contains, as far as the rename is concerned, NO references at all — so it
 * renames what it can see, reports success, and leaves every call site in the unreadable
 * project pointing at a name that no longer exists. Broken code, reported as a clean refactor.
 * That is why this is a REFUSAL and not a warning: a warning is a thing an agent routes
 * around.</p>
 *
 * <p>And the refusal is QUALIFIED — it names the project, says what is wrong with it, and
 * gives the remedy. An error code that merely says "unhealthy" moves the mystery up one level
 * and leaves the user to go hunting.</p>
 */
class WorkspaceHealthGuardTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ObjectMapper om;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        om = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @Test
    @DisplayName("a healthy workspace is not obstructed — the guard must not cry wolf")
    void aHealthyWorkspacePassesTheGuard() {
        assertTrue(WorkspaceHealth.diagnose(service).isEmpty(),
            "the fixture workspace is sound");
        assertTrue(WorkspaceHealth.refuseIfUnhealthy(service, "rename_symbol").isEmpty(),
            "a guard that fires on a good workspace is worse than no guard — it teaches "
                + "people to bypass it");

        // And health_check agrees.
        HealthCheckTool health = new HealthCheckTool(() -> true, () -> 56,
            () -> org.jawata.mcp.ProjectLoadingState.LOADED, () -> null, () -> service);
        @SuppressWarnings("unchecked")
        Map<String, Object> workspace =
            (Map<String, Object>) data(health.execute(om.createObjectNode())).get("workspace");
        assertEquals(Boolean.TRUE, workspace.get("healthy"), "got: " + workspace);
    }

    @Test
    @DisplayName("a project that CANNOT BE READ is diagnosed — named, explained, and remedied")
    void anUnreadableProjectIsDiagnosedNotJustFlagged() throws Exception {
        // CLOSE the project. It stays registered — allProjects() still hands it out — but
        // nothing in it can be read. This is exactly the state that silently poisoned every
        // whole-workspace scan, and that a rename would have refactored right past.
        service.allProjects().iterator().next().javaProject().getProject()
            .close(new org.eclipse.core.runtime.NullProgressMonitor());

        List<WorkspaceHealth.Problem> problems = WorkspaceHealth.diagnose(service);
        assertEquals(1, problems.size(), "the dead project is found: " + problems);

        WorkspaceHealth.Problem p = problems.get(0);
        assertNotNull(p.projectKey(), "it is NAMED");
        assertNotNull(p.projectPath(), "with its path, so the user can go and look");
        assertTrue(p.problem().contains("CLOSED"),
            "and WHAT is wrong is stated plainly: " + p.problem());
        assertTrue(p.remedy().contains("load_project") || p.remedy().contains("project(action="),
            "and the REMEDY is an actual command, not advice: " + p.remedy());
    }

    @Test
    @DisplayName("THE GUARD: a rename against an unreadable workspace is REFUSED, with the diagnosis")
    void aRefactoringIsRefusedAndTellsTheUserExactlyWhatToFix() throws Exception {
        service.allProjects().iterator().next().javaProject().getProject()
            .close(new org.eclipse.core.runtime.NullProgressMonitor());

        RenameSymbolTool rename = new RenameSymbolTool(() -> service,
            new RefactoringChangeCache());
        ObjectNode args = om.createObjectNode();
        args.put("symbol", "com.example.Calculator");
        args.put("newName", "Calc");

        ToolResponse r = rename.execute(args);

        // REFUSED — not "renamed 0 files", not a warning attached to a success.
        assertFalse(r.isSuccess(), "a rename that cannot see the whole workspace must not run");
        assertEquals("WORKSPACE_UNHEALTHY", r.getError().getCode(), "got: " + r.getError());

        // QUALIFIED. The message must let the user fix it at a glance rather than investigate.
        String message = r.getError().getMessage();
        assertTrue(message.contains("REFUSING"), "got: " + message);
        assertTrue(message.contains("problem:") && message.contains("fix:"),
            "the refusal must carry the diagnosis AND the remedy, per project: " + message);
        assertTrue(message.contains("no longer exists") || message.contains("NO references"),
            "and must say WHY refusing beats proceeding: " + message);

        // The hint must forbid the workaround. An agent that greps around this has
        // reintroduced the very bug the guard exists to prevent.
        String hint = String.valueOf(r.getError().getHint());
        assertTrue(hint.contains("NOT work around") && hint.contains("grep"),
            "the agent must be told not to route around it: " + hint);

        // And the STRUCTURED data is there too — a code alone would move the mystery up a
        // level instead of ending it.
        Map<String, Object> diagnosis = data(r);
        assertNotNull(diagnosis, "the refusal carries data, not just prose");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
            (List<Map<String, Object>>) diagnosis.get("unhealthyProjects");
        assertEquals(1, rows.size(), "got: " + diagnosis);
        assertNotNull(rows.get(0).get("projectKey"));
        assertNotNull(rows.get(0).get("problem"));
        assertNotNull(rows.get(0).get("remedy"));
        assertEquals("rename_symbol", diagnosis.get("refused"));
    }

    /**
     * jawata-mcp#4 (Sprint 27a Stage 8) — the false-green trap: a project with
     * an UNRESOLVED BUILD PATH stays open, registered and walkable, but the
     * Java builder refuses to run, so every compile gate answers 0 errors —
     * which reads as "clean" and means "was never compiled". The M2 workspace
     * had twenty such projects, all reported healthy, every refactor loop
     * passing vacuously. The contract: health names it with a remedy,
     * get_diagnostics says COULD NOT COMPILE and never a bare zero, and the
     * refactoring guard refuses.
     */
    @Test
    @DisplayName("a broken BUILD PATH is unhealthy, gates say could-not-compile, refactorings refuse")
    void aBrokenBuildPathIsNeverAGreenGate() throws Exception {
        org.eclipse.jdt.core.IJavaProject jp =
            service.allProjects().iterator().next().javaProject();
        org.eclipse.jdt.core.IClasspathEntry[] cp = jp.getRawClasspath();
        org.eclipse.jdt.core.IClasspathEntry[] broken =
            java.util.Arrays.copyOf(cp, cp.length + 1);
        broken[cp.length] = org.eclipse.jdt.core.JavaCore.newLibraryEntry(
            new org.eclipse.core.runtime.Path("/nonexistent/missing-lib.jar"), null, null);
        jp.setRawClasspath(broken, null);
        jp.getProject().build(
            org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, null);

        // 1. HEALTH: named, explained, remedied — not healthy:true.
        List<WorkspaceHealth.Problem> problems = WorkspaceHealth.diagnose(service);
        assertFalse(problems.isEmpty(), "an unbuildable project must be diagnosed");
        assertTrue(problems.get(0).problem().contains("BUILD PATH"),
            "the diagnosis names the build path: " + problems.get(0).problem());
        assertTrue(problems.get(0).problem().contains("VACUOUS"),
            "and says what a 0-error gate on it would mean: " + problems.get(0).problem());
        assertNotNull(problems.get(0).remedy());

        // 2. THE GATE: get_diagnostics must say COULD NOT COMPILE, never bare 0.
        GetDiagnosticsTool diag = new GetDiagnosticsTool(() -> service);
        Map<String, Object> d = data(diag.execute(om.createObjectNode()));
        assertNotNull(d.get("couldNotCompile"),
            "the response carries the could-not-compile block: " + d);
        assertTrue(((Number) d.get("errorCount")).intValue() > 0,
            "an unbuildable project can never contribute errorCount 0: " + d);

        // 3. THE GUARD: reference-rewriting refuses, per the documented contract.
        assertTrue(WorkspaceHealth.refuseIfUnhealthy(service, "rename_symbol").isPresent(),
            "the refactoring guard fires on a broken build path");
    }
}
