package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D8d (feedback D11c): THE MAP A SESSION OPENS WITH CAME FROM THE WRONG WORKSPACE.
 *
 * <p>The "JAWATA MAP" injected at every prompt of a session working on one product described
 * {@code org.jawata.mcp.tools…} and {@code org.jawata.core…} — another product's code — on every
 * turn. The store is shared by every workspace on the machine, each row is stamped with the
 * workspace that wrote it, and the CODE lane's nomination never asked: it ranked the whole
 * lane. That is context spent for nothing at every prompt, and worse, a description of the wrong
 * codebase presented as an orientation to this one.</p>
 *
 * <p>The rule is the one {@code WorkspaceScopedJudgmentTest} already holds for judging anchors:
 * a store WITH an identity sees only rows stamped with it, an unstamped row counts as foreign,
 * and a store WITHOUT an identity keeps the see-everything behaviour.</p>
 */
class CodeMapIsThisWorkspacesTest {

    private static final String TASK = "what keeps the slot manager from freeing a busy slot?";

    private H2ExperienceStore store;
    private ExperienceTool tool;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
        json = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private void job(String symbol, String summary) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "job");
        a.put("symbol", symbol);
        a.put("summary", summary);
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "fixture row refused: " + r.getError());
    }

    private void area(String pkg, String summary) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "area");
        a.put("summary", summary);
        a.putArray("packages").add(pkg);
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "fixture area refused: " + r.getError());
    }

    /** Two workspaces wrote into one store, as they do on a real machine. */
    private void twoWorkspacesWrote() {
        store.setProvenance("orb-strategy", null);
        job("pipeline.SlotManager#freeSlot",
            "Frees a trading slot once its order has fully ended, never while one is working.");
        area("pipeline", "Where a strategy's slots are claimed, worked and released.");

        store.setProvenance("jawata", null);
        job("org.jawata.mcp.tools.ToolRegistry#callTool",
            "Runs one tool call through the checks every call passes through.");
        area("org.jawata.mcp.tools", "The tool surface: the registry and the tools it publishes.");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapData() {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "nominate");
        a.put("question", TASK);
        a.put("lane", "code");
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "got " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertTrue(data.get("candidates") instanceof List,
            "PROOF OF LIFE: a nomination answers with candidates at all: " + data.keySet());
        return data;
    }

    @SuppressWarnings("unchecked")
    private static String describe(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        for (Object c : (List<Object>) data.get("candidates")) {
            Map<String, Object> m = (Map<String, Object>) c;
            sb.append(m.get("type")).append(' ').append(m.get("symbol")).append(" | ")
                .append(m.get("principle")).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("the map holds this workspace's rows and none of another's")
    void aWorkspaceSeesItsOwnCodeAndNotAnothers() {
        twoWorkspacesWrote();
        store.setProvenance("orb-strategy", null);

        Map<String, Object> data = mapData();
        String shown = describe(data);

        assertTrue(shown.contains("pipeline.SlotManager#freeSlot")
                && shown.contains("Where a strategy's slots are claimed"),
            "the control: this workspace's own job and area are in the map, or the absence"
                + " below would be satisfied by a map that shows nothing. Got:\n" + shown);
        assertFalse(shown.contains("org.jawata") || shown.contains("registry and the tools"),
            "another product's code described, as an orientation to this one. Got:\n" + shown);
    }

    @Test
    @DisplayName("withheld rows are counted, because a silent drop reads as an empty store")
    void theWithholdingIsSaid() {
        twoWorkspacesWrote();
        store.setProvenance("orb-strategy", null);

        Map<String, Object> data = mapData();

        assertEquals(2, data.get("foreign_workspace_withheld"),
            "the job and the area written by the other workspace were held back, and the answer"
                + " must say how many — an absence caused by the filter is not an absence in the"
                + " store: " + data.keySet());
    }

    @Test
    @DisplayName("a store with NO identity keeps the see-everything behaviour")
    void withoutAnIdentityNothingIsWithheld() {
        twoWorkspacesWrote();
        store.setProvenance(null, null);

        String shown = describe(mapData());

        assertTrue(shown.contains("pipeline.SlotManager#freeSlot")
                && shown.contains("org.jawata.mcp.tools.ToolRegistry#callTool"),
            "tests and standalone runs carry no identity, and must keep every row. Got:\n" + shown);
    }

    @Test
    @DisplayName("an unstamped row counts as foreign once the store has an identity")
    void anUnstampedRowIsForeignOnceThereIsAnIdentity() {
        store.setProvenance(null, null);
        job("legacy.Unknown#thing", "A description written before rows were stamped with a workspace.");

        store.setProvenance("orb-strategy", null);
        String shown = describe(mapData());

        assertFalse(shown.contains("legacy.Unknown"),
            "it cannot be shown to belong HERE, and the rule everywhere else in this store is"
                + " that an unstamped row is foreign once the store has an identity. Got:\n" + shown);
    }
}
