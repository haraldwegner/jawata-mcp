package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The import layer stops failing silently (Sprint 28 Stage 8, G6a).
 *
 * <p><strong>The measurement this exists for.</strong> On a live 29-project
 * Eclipse/PDE workspace, jawata reported 1229 compile errors. 1215 of them came
 * from four projects that resolved <em>no dependencies at all</em>. The only
 * visible difference between those four and the seven that worked was that
 * their classpath had no {@code projectDependencies} key — an ABSENCE the
 * reader has to notice, guess the meaning of, and be right about. The import
 * layer knew every requirement it had failed to satisfy and wrote each to
 * {@code log.debug}: off by default, in no response, invisible next to the
 * error count.</p>
 *
 * <p>So a project that silently resolves nothing looked exactly like a project
 * whose code is broken.</p>
 */
class UnresolvedDependencyHonestyTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        return (Map<String, Object>) r.getData();
    }

    /**
     * THE FIELD IS ALWAYS THERE. An empty list means "asked and satisfied"; a
     * missing field means "nobody said", and those are different facts that
     * were previously spelled the same way.
     */
    @Test
    @DisplayName("a fully-resolving project reports an EMPTY unresolved list, not a missing field")
    void resolvingEverythingIsStatedRatherThanImplied() {
        InspectTool tool = new InspectTool(() -> service);
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "classpath");
        Map<String, Object> d = data(tool.execute(args));

        assertTrue(d.containsKey("unresolvedDependencies"),
            "the key must be present even when nothing is unresolved — an absent field is "
                + "what made four broken projects indistinguishable from healthy ones: "
                + d.keySet());
        List<?> unresolved = assertInstanceOf(List.class, d.get("unresolvedDependencies"));
        assertTrue(unresolved.isEmpty(),
            "a plain Maven fixture resolves everything it asks for: " + unresolved);
    }

    /**
     * And every row SAYS WHY. "not found" is not a reason: the cure for a
     * missing Require-Bundle differs from the cure for an unexported package,
     * and a reader who cannot tell them apart cannot act.
     */
    @Test
    @DisplayName("an unresolved row names the requirement, its form, and why it failed")
    void everyRowCarriesAnActionableReason() {
        for (org.jawata.core.project.UnresolvedRequirement u : List.of(
                org.jawata.core.project.UnresolvedRequirement.requireBundle("org.eclipse.swt"),
                org.jawata.core.project.UnresolvedRequirement.importPackage("javafx.embed.swt"),
                org.jawata.core.project.UnresolvedRequirement.junitContainer("org.junit"))) {
            assertNotNull(u.kind());
            assertNotNull(u.name());
            assertTrue(u.reason().length() > 20,
                "'" + u.kind() + "' must say WHY, not just that it failed: " + u.reason());
            assertTrue(u.reason().toLowerCase().contains("pool")
                    || u.reason().toLowerCase().contains("workspace"),
                "the reason must name WHERE it looked, or the reader cannot tell a "
                    + "misconfiguration from a missing artifact: " + u.reason());
        }
    }

    /**
     * THE GUARD SEMANTICS ARE PINNED, DELIBERATELY UNCHANGED.
     *
     * <p>{@code healthy} is the refactoring guard: false REFUSES renames,
     * because a rename cannot see references in a project it cannot read.
     * Flipping it on unresolved dependencies would refuse every refactoring
     * across the exact 29-project workspace this instrument exists to
     * diagnose — the cure worse than the disease. The count rides ALONGSIDE
     * it. If a later change wants to couple them, this test makes that
     * deliberate rather than accidental.</p>
     */
    @Test
    @DisplayName("unresolved dependencies are reported and do NOT flip `healthy`")
    void theRefactoringGuardIsNotArmedByAnUnresolvedDependency() {
        HealthCheckTool tool = new HealthCheckTool(
            () -> Boolean.TRUE,
            () -> 1,
            () -> org.jawata.mcp.ProjectLoadingState.LOADED,
            () -> "",
            () -> service);
        Map<String, Object> d = data(tool.execute(mapper.createObjectNode()));

        @SuppressWarnings("unchecked")
        Map<String, Object> workspace = (Map<String, Object>) d.get("workspace");
        assertNotNull(workspace, "health_check reports a workspace block: " + d.keySet());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projects = (List<Map<String, Object>>) workspace.get("projects");
        assertNotNull(projects);
        assertTrue(!projects.isEmpty(), "the fixture is loaded, so a row must exist");

        for (Map<String, Object> row : projects) {
            assertTrue(row.containsKey("unresolvedDependencyCount"),
                "every project row states its unresolved count, including zero: " + row.keySet());
            assertEquals(Boolean.TRUE, row.get("healthy"),
                "a readable project stays healthy — this test pins that the new field did "
                    + "not quietly arm the refactoring guard: " + row);
        }
        assertEquals(Boolean.TRUE, workspace.get("healthy"),
            "and the workspace-level guard is unchanged too: " + workspace);
    }
}
