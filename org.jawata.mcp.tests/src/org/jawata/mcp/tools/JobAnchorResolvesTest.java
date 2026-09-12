package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.IJdtService;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28f D7, closed at C9 — A JOB'S ANCHOR IS RESOLVED, NOT TAKEN ON TRUST.
 *
 * <p>The signed deliverable says a job whose anchor does not resolve is refused by name.
 * Nothing resolved it: {@code EntryForm} is a pure static gate with no JDT, so the code
 * lane — the one lane whose premise is that the compiler keeps the anchor honest —
 * accepted jobs pointing at members that do not exist. A fresh-context implementation
 * audit found it by recording one.</p>
 *
 * <h2>Why this class exists rather than more cases in {@code JobFormTest}</h2>
 *
 * <p>That class drives the form gate directly, with no project. The branch under test
 * here only runs when a project IS loaded, so a test without one cannot reach it — which
 * is precisely why the gap survived: every test of the gate was blind to it by
 * construction.</p>
 */
class JobAnchorResolvesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ExperienceStore store;
    private ObjectMapper json;
    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("re-derived-job");
        store = H2ExperienceStore.open(null);
        json = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    private ToolResponse recordJob(ExperienceTool tool, String symbol) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "job");
        a.put("symbol", symbol);
        a.put("summary", "Answer which parse helpers this tool already carries.");
        return tool.execute(a);
    }

    /** THE CLAUSE: an anchor the workspace cannot find is refused, and NAMED. */
    @Test
    @DisplayName("a job anchored at a member that does not exist is refused by name")
    void an_unresolvable_anchor_is_refused() {
        ExperienceTool tool = new ExperienceTool(() -> service, store);

        ToolResponse r = recordJob(tool, "com.example.AlphaTool#noSuchMemberAnywhere");

        assertFalse(r.isSuccess(),
            "the code lane answers WHERE a job lives, so a row pointing at nothing answers"
                + " a question wrongly rather than not at all: " + r.getData());
        assertTrue(String.valueOf(r.getError().getMessage()).contains("noSuchMemberAnywhere"),
            "and the refusal must NAME the anchor it could not find — 'refused by name' is"
                + " the deliverable's own wording, and a refusal that does not say which"
                + " symbol leaves the author guessing: " + r.getError().getMessage());
    }

    /**
     * THE CONTROL, and without it the case above is satisfied by a gate that refuses every
     * job — which would be a worse product and an equally green test.
     */
    @Test
    @DisplayName("a job anchored at a member that DOES exist is accepted")
    @SuppressWarnings("unchecked")
    void a_resolvable_anchor_is_accepted() {
        ExperienceTool tool = new ExperienceTool(() -> service, store);

        ToolResponse r = recordJob(tool, "com.example.AlphaTool#parse");

        assertTrue(r.isSuccess(), "this member exists in the fixture: "
            + (r.getError() == null ? "" : r.getError().getMessage()));
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNull(data.get("anchorVerified"),
            "and the response says nothing about verification, because it WAS verified —"
                + " the key's presence must mean exactly one thing: " + data);
    }

    /**
     * THE OTHER HALF, and the reason the refusal is narrow. A hook fires where no project
     * is loaded and where the file may not exist yet; refusing there would reject correct
     * rows for an absence of context the author does not control. So the row is ADMITTED
     * and the gap is SPOKEN — "I could not check" and "it does not exist" are the
     * distinction this sprint is about, and the gate is the last place to blur it.
     */
    @Test
    @DisplayName("with no project loaded the row is admitted and says it was not checked")
    @SuppressWarnings("unchecked")
    void an_unverifiable_anchor_is_admitted_and_said() {
        ExperienceTool tool = new ExperienceTool(() -> null, store);

        ToolResponse r = recordJob(tool, "com.example.AlphaTool#noSuchMemberAnywhere");

        assertTrue(r.isSuccess(),
            "no project is loaded, so nothing could resolve this — refusing here would"
                + " reject correct rows for a missing context the author does not control: "
                + (r.getError() == null ? "" : r.getError().getMessage()));
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(false, data.get("anchorVerified"),
            "but a caller reading only `stored: true` would take this for a verified"
                + " anchor, and the two are different facts: " + data);
        assertTrue(String.valueOf(data.get("anchorUncheckedWhy")).contains("no project"),
            "and the reason names which of the two it is: " + data);
    }

    /**
     * THE THIRD STATE, and it is the one the first version of this gate got WRONG.
     *
     * <p>The resolver answers {@code resolved:false} for three different reasons, and only
     * two of them mean the anchor is not there: the type was not found, and the type is here
     * but the member is gone. Both set {@code stale}. The third is an exception thrown during
     * resolution, which sets no {@code stale} key — and the gate as first written read
     * {@code resolved} alone, so it told the author their anchor "does not resolve in the
     * loaded workspace, so this job would point at nothing" when the truth was that the
     * lookup had failed.</p>
     *
     * <p><b>That is this sprint's own distinction, broken by the gate added to protect it</b>
     * — and the commit message argued the distinction at length while the code below it
     * blurred exactly that case. The C9 architect watch found it by reading; no test could,
     * because none existed for a resolver that fails rather than answers.</p>
     */
    @Test
    @DisplayName("a resolver that THROWS is 'could not check', not 'does not exist'")
    @SuppressWarnings("unchecked")
    void a_resolution_error_is_admitted_and_said_rather_than_refused() {
        IJdtService throwing = (IJdtService) Proxy.newProxyInstance(
            IJdtService.class.getClassLoader(), new Class<?>[] {IJdtService.class},
            (proxy, method, methodArgs) -> {
                if ("findType".equals(method.getName())) {
                    throw new IllegalStateException("the index is rebuilding");
                }
                return method.getReturnType().isPrimitive() ? false : null;
            });
        ExperienceTool tool = new ExperienceTool(() -> throwing, store);

        ToolResponse r = recordJob(tool, "com.example.AlphaTool#parse");

        assertTrue(r.isSuccess(),
            "a lookup that FAILED says nothing about whether the member exists, so refusing"
                + " here rejects a correct row for an outage the author did not cause: "
                + (r.getError() == null ? "" : r.getError().getMessage()));
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(false, data.get("anchorVerified"),
            "and it is NOT reported as verified either — the check did not happen: " + data);
        assertTrue(String.valueOf(data.get("anchorUncheckedWhy")).contains("the index is rebuilding"),
            "and the reason carries what actually went wrong, rather than the absence"
                + " wording that would send the author looking for a symbol that is there: "
                + data);
    }
}
