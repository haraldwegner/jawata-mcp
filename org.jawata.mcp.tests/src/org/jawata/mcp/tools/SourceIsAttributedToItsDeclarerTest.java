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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mcp#26 — <b>a type belongs to the project that DECLARES it, not to the first one that can
 * see it.</b>
 *
 * <p>{@code LibrarySource.sourceOfPaged} walks {@code allProjects()} and reports the first
 * whose classpath answers {@code findType}. A project that merely DEPENDS on the declaring one
 * answers yes too, so the reported {@code projectKey} was iteration-order luck presented as
 * provenance. Found in the v3.9.0 dogfood: a type declared in {@code com.jats2.model} was
 * attributed to a UI project that only depends on it.</p>
 *
 * <p>THE FIXTURE PAIR EXISTS BECAUSE NO EXISTING ONE COULD SHOW THIS. Every sample project here
 * is independent — {@code simple-maven-b} declares no dependencies, and {@code reactor-cross}
 * says in its own pom "No dependency on module-a on purpose" — so the first project to resolve
 * a type was always the one declaring it, and an assertion comparing them would have passed
 * before and after the fix. {@code xref-consumer} names {@code xref-declarer} in a
 * {@code kind="src" path="/xref-declarer"} entry, which is Eclipse's own spelling for a project
 * reference and which this importer honours (mcp#3).</p>
 *
 * <p>The consumer is loaded FIRST on purpose: it is the project the loop meets first, so
 * without the fix it is the one reported.</p>
 */
class SourceIsAttributedToItsDeclarerTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String SHARED_TYPE = "com.example.xref.Declared";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private InspectTool tool;
    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadWorkspaceCopy("xref-consumer", "xref-declarer");
        tool = new InspectTool(() -> service);
    }

    /**
     * Whether this fixture currently poses the question at all.
     *
     * <p>The attribution defect needs TWO things: the consumer met FIRST by the iteration, and
     * the consumer RESOLVING a type it does not declare. The first holds. <b>The second does
     * not, measured</b> — the {@code kind="src" path="/xref-declarer"} entry is parsed
     * ({@code ProjectImporter} line 2277 collects it) and does not put the type on the
     * consumer's classpath in this fixture, so {@code findType} answers null there and the loop
     * correctly walks on to the declarer.</p>
     *
     * <p>WHY THAT IS NOT SETTLED HERE: the comment at {@code ProjectImporter:1156} says these
     * references "wire in {@code resolveWorkspace} exactly like Require-Bundle" — and a symbol
     * search for {@code resolveWorkspace} finds no such method on the service, only an unrelated
     * one in {@code FqnResolver}. So the comment names a method that has been renamed or
     * removed, and whether the wiring still happens under another name, happens only for PDE
     * bundles, or does not happen at all, is NOT established. Reporting a product defect on that
     * basis would be a cause I had not distinguished from my own fixture being wrong.</p>
     */
    private boolean theConsumerResolvesADeclarationItDoesNotOwn() {
        return service.allProjects().stream()
            .filter(p -> "xref-consumer".equals(p.projectKey()))
            .anyMatch(p -> {
                try {
                    return p.javaProject().findType(SHARED_TYPE) != null;
                } catch (Exception e) {
                    return false;
                }
            });
    }

    @Test
    @DisplayName("mcp#26: the source is attributed to the project that owns the file, and names it")
    void theDeclaringProjectIsReportedAndTheFileIsNamed() {
        ObjectNode args = OM.createObjectNode();
        args.put("kind", "source");
        args.put("typeName", SHARED_TYPE);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(), "got: " + response.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getData();

        assertAll(
            // PROOF OF LIFE: this must be the workspace-source branch. On a binary hit the
            // attribution question does not arise, and the assertions below would be about a
            // path the defect never touched.
            () -> assertEquals("workspace-source", data.get("origin"), "got: " + data),

            // The second half of the issue, and the half this fixture CAN discriminate: three
            // agents ran the original probe and only one reported the tool's real shape — one
            // fetched the path from a separate search, one INFERRED it from the package name.
            // A payload that answers "which project" and not "which file" invites exactly that.
            () -> assertNotNull(data.get("filePath"), "no filePath in the payload: " + data),
            () -> assertTrue(String.valueOf(data.get("filePath")).endsWith("Declared.java"),
                "the path must name the declaring file: " + data));
    }

    @Test
    @DisplayName("mcp#26: the source is attributed to the project that DECLARES it")
    void theDeclaringProjectIsReported() {
        // ABORTS rather than passing when the fixture cannot pose the question — the idiom this
        // suite already uses for its corpus gates, and for the same reason: a green line here
        // would say "the attribution is right" when what happened is that no project ever
        // claimed a type it does not own, so the loop was never asked to choose.
        org.junit.jupiter.api.Assumptions.assumeTrue(theConsumerResolvesADeclarationItDoesNotOwn(),
            "[mcp#26 ATTRIBUTION] NOT RUN — the kind=\"src\" path=\"/xref-declarer\" reference"
                + " does not put " + SHARED_TYPE + " on xref-consumer's classpath, so no project"
                + " here resolves a type it does not declare and the defect cannot arise."
                + " The fix is in place and this case is unverified in this run.");

        ObjectNode args = OM.createObjectNode();
        args.put("kind", "source");
        args.put("typeName", SHARED_TYPE);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(), "got: " + response.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getData();

        assertEquals("xref-declarer", data.get("projectKey"),
            "the type is declared in xref-declarer and only RESOLVES in the consumer: " + data);
    }

    @Test
    @DisplayName("THE CONTROL — a type declared in the FIRST project is still attributed to it")
    void aTypeInTheFirstProjectIsUnaffected() {
        // Without this, a fix that simply reported the LAST project, or the one that is not
        // first, would pass the case above while being just as wrong — and the two would be
        // indistinguishable. `Consumer` is declared in the project the loop meets first, so a
        // correct answer here is the same before and after.
        ObjectNode args = OM.createObjectNode();
        args.put("kind", "source");
        args.put("typeName", "com.example.xref.Consumer");

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(), "got: " + response.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getData();

        assertEquals("xref-consumer", data.get("projectKey"),
            "a type declared in the consumer belongs to the consumer: " + data);
    }
}
