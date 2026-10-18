package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ARCH-C22-4 (b) and (c) — a code row written by ANOTHER workspace is not an answer on the keyword
 * path of a recall either, and {@code scanned} counts the rows this answer chose from.
 *
 * <p>The nomination already left another workspace's code rows out and counted them
 * ({@code CodeMapIsThisWorkspacesTest}). The recall by a symbol or package cue asked the store
 * directly and applied no such rule, so a cue naming another product's class returned its
 * description to this one; and its {@code scanned} reported the rows HELD while the nomination's
 * reported the rows RANKED, one key with two meanings.</p>
 */
class TheWorkspaceRuleHoldsOnEveryRecallPathTest {

    private static final String MINE = "pipeline.SlotManager#freeSlot";
    private static final String THEIRS = "org.jawata.mcp.tools.ToolRegistry#callTool";
    private static final String MY_TEXT = "Frees a trading slot once its order has fully ended.";
    private static final String THEIR_TEXT = "Runs one tool call through the checks every call passes through.";

    private H2ExperienceStore store;
    private ExperienceTool tool;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
        json = new ObjectMapper();
        store.setProvenance("orb-strategy", null);
        job(MINE, MY_TEXT);
        store.setProvenance("jawata", null);
        job(THEIRS, THEIR_TEXT);
        store.setProvenance("orb-strategy", null);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> recallBySymbol(String symbol) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "recall");
        a.put("symbol", symbol);
        a.putArray("lanes").add("code");
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "got " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    @Test
    void aSymbolCueDoesNotReturnAnotherWorkspacesCodeRow() {
        String own = recallBySymbol(MINE).toString();
        assertTrue(own.contains(MY_TEXT),
            "PROOF OF LIFE: this workspace's own job is found by its symbol: " + own);

        Map<String, Object> answer = recallBySymbol(THEIRS);
        String foreign = answer.toString();
        assertFalse(foreign.contains(THEIR_TEXT),
            "another product's class described as an answer to this one, by the keyword path"
                + " that applies no workspace rule: " + foreign);
        assertEquals(1, answer.get("foreign_workspace_withheld"),
            "an absence the filter caused must say so, or it reads as an empty store: " + foreign);
    }

    @Test
    void scannedCountsTheRowsThisAnswerChoseFromAndNotTheRowsHeld() {
        Map<String, Object> data = recallBySymbol(MINE);
        Object scanned = data.get("scanned");
        assertTrue(scanned instanceof Map, "recall reports what it scanned: " + data.keySet());
        assertEquals(1, ((Map<?, ?>) scanned).get("code"),
            "the lane holds two jobs but only this workspace's one answers here; the figure is"
                + " the rows chosen from, the same meaning the nomination gives it: " + scanned);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> recall(ObjectNode cue, String lane) {
        cue.put("kind", "recall");
        cue.putArray("lanes").add(lane);
        ToolResponse r = tool.execute(cue);
        assertTrue(r.isSuccess(), "got " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    /**
     * The analogy path is a second way rows reach a recall answer: rows the cue did not fit but
     * whose words or meaning are near. An area has no Java anchor, so it is the one code-lane type
     * that path would admit, and a package cue is the cue that targets areas.
     */
    @Test
    void aForeignAreaDoesNotArriveAsAnAnalogyEither() {
        String theirArea = "The tool surface where every registry call is checked and published.";
        store.setProvenance("jawata", null);
        area("org.jawata.mcp.tools", theirArea);
        store.setProvenance("orb-strategy", null);
        area("pipeline", "Where a strategy's slots are claimed, worked and released.");

        ObjectNode own = json.createObjectNode();
        own.put("package", "pipeline");
        own.put("symptom", "slots claimed worked released");
        assertTrue(recall(own, "code").toString().contains("slots are claimed"),
            "PROOF OF LIFE: this workspace's own area answers its package cue");

        ObjectNode foreign = json.createObjectNode();
        foreign.put("package", "pipeline");
        foreign.put("symptom", "registry call checked published tool surface");
        String answer = recall(foreign, "code").toString();
        assertFalse(answer.contains(theirArea),
            "another product's area arrived beside this workspace's package cue, as a nominee"
                + " or an entry: " + answer);
    }

    @Test
    void knowledgeAboutWorkStillTravelsBetweenWorkspaces() {
        store.setProvenance("jawata", null);
        ObjectNode lesson = json.createObjectNode();
        lesson.put("kind", "record");
        lesson.put("type", "lesson");
        lesson.put("symbol", "com.example.Ledger#amend");
        lesson.put("summary", "Amend carries the total, not the remainder.");
        lesson.put("situation", "when an order is amended after a partial fill");
        lesson.put("verdict", "worked");
        assertTrue(tool.execute(lesson).isSuccess(), "fixture lesson refused");
        store.setProvenance("orb-strategy", null);

        ObjectNode cue = json.createObjectNode();
        cue.put("symbol", "com.example.Ledger#amend");
        assertTrue(recall(cue, "experience").toString().contains("Amend carries the total"),
            "a lesson learned in another workspace is knowledge ABOUT work and must still answer");
    }

    @Test
    void theLaneStatesTheRuleOnce() {
        assertTrue(KnowledgeLane.CODE.answersFor("a", "a"), "its own row answers");
        assertFalse(KnowledgeLane.CODE.answersFor("a", "b"), "another workspace's row does not");
        assertFalse(KnowledgeLane.CODE.answersFor("a", null), "an unstamped row is foreign once there is an identity");
        assertTrue(KnowledgeLane.CODE.answersFor(null, "b"), "no identity: everything answers");
        assertTrue(KnowledgeLane.CODE.answersFor("  ", "b"), "a blank identity is no identity");
        java.util.Set<KnowledgeLane> others = java.util.EnumSet.complementOf(
            java.util.EnumSet.of(KnowledgeLane.CODE));
        assertEquals(KnowledgeLane.values().length - 1, others.size(), "every lane but the code lane");
        for (KnowledgeLane other : others) {
            assertTrue(other.answersFor("a", "b"),
                other + " is knowledge about work and travels between workspaces");
            assertFalse(other.filtersFor("a"), other + " is not keyed by workspace");
        }
        assertTrue(KnowledgeLane.CODE.filtersFor("a"));
        assertFalse(KnowledgeLane.CODE.filtersFor(null));
    }

    @Test
    void aStoreWithNoIdentityStillSeesEveryCodeRow() {
        store.setProvenance(null, null);
        String foreign = recallBySymbol(THEIRS).toString();
        assertTrue(foreign.contains(THEIR_TEXT),
            "tests and standalone residents carry no identity and keep every row: " + foreign);
    }
}
