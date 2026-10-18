package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ARCH-C22-2 — the per-lane walk belongs to the scope that owns the lanes, and the rule about
 * which code rows answer for a workspace belongs to the code lane's corpus.
 *
 * <p>The retrieval used to write the walk out by hand at four sites and to ask the workspace
 * question of each row through a private check of its own. Both now live on the object that holds
 * the data, so these tests address those two members directly: a retrieval test could stay green
 * while either member quietly did the wrong thing, as long as some other site happened to
 * compensate.</p>
 */
class LaneOwnsItsWalkAndItsRowsTest {

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

    private void record(String type, String symbol, String summary) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", type);
        if (symbol != null) {
            a.put("symbol", symbol);
        }
        a.put("summary", summary);
        if ("lesson".equals(type)) {
            a.put("situation", "when the lane walk is being measured");
            a.put("verdict", "worked");
        }
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "fixture row refused: " + r.getError());
    }

    private static Set<String> symbolsOf(List<StoredEntry> rows) {
        return rows.stream().map(StoredEntry::summary).collect(Collectors.toSet());
    }

    @Test
    void byLaneVisitsExactlyTheLanesOfTheScopeInLaneOrder() {
        LaneScope scope = LaneScope.of(KnowledgeLane.DOMAIN, KnowledgeLane.EXPERIENCE);
        List<KnowledgeLane> visited = new ArrayList<>();

        Map<KnowledgeLane, String> got = scope.byLane(lane -> {
            visited.add(lane);
            return lane.wire();
        });

        assertEquals(List.copyOf(scope.lanes()), visited,
            "the walk must visit the scope's lanes once each, in the order the scope holds them —"
                + " a walk over every lane would search lanes nobody named");
        assertEquals(scope.lanes(), got.keySet(),
            "the result carries one value per lane of the scope and no other; got " + got.keySet());
        assertEquals(List.copyOf(scope.lanes()), List.copyOf(got.keySet()), "and in lane order");
        assertTrue(!got.containsKey(KnowledgeLane.CODE),
            "a lane outside the scope must not appear in the gather");
    }

    @Test
    void theCodeLaneAnswersOnlyForRowsStampedWithTheWorkspace() {
        store.setProvenance("orb-strategy", null);
        record("job", "pipeline.SlotManager#freeSlot", "Frees a slot once its order has ended.");
        store.setProvenance("jawata", null);
        record("job", "org.jawata.mcp.tools.ToolRegistry#callTool", "Runs one tool call.");

        StoreCorpus.LaneCorpus code = StoreCorpus.of(store).lane(KnowledgeLane.CODE);
        Set<String> all = symbolsOf(code.live());
        assertEquals(2, all.size(), "PROOF OF LIFE: both workspaces' rows are in the lane: " + all);

        Set<String> mine = symbolsOf(code.liveFor("orb-strategy"));
        assertEquals(Set.of("Frees a slot once its order has ended."), mine,
            "a workspace sees its own code rows and none of another's; got " + mine);
    }

    @Test
    void aStoreWithNoIdentityOrABlankOneSeesEveryCodeRow() {
        store.setProvenance("orb-strategy", null);
        record("job", "pipeline.SlotManager#freeSlot", "Frees a slot once its order has ended.");
        store.setProvenance("jawata", null);
        record("job", "org.jawata.mcp.tools.ToolRegistry#callTool", "Runs one tool call.");

        StoreCorpus.LaneCorpus code = StoreCorpus.of(store).lane(KnowledgeLane.CODE);

        assertEquals(2, code.liveFor(null).size(), "no identity: the lane is returned whole");
        assertEquals(2, code.liveFor("  ").size(), "a blank identity is no identity");
    }

    @Test
    void aRowStampedWithNoWorkspaceIsForeignOnceThereIsAnIdentity() {
        store.setProvenance(null, null);
        record("job", "legacy.Unknown#thing", "Written before rows carried a workspace stamp.");

        StoreCorpus.LaneCorpus code = StoreCorpus.of(store).lane(KnowledgeLane.CODE);

        assertEquals(1, code.live().size(), "PROOF OF LIFE: the unstamped row is in the lane");
        assertTrue(code.liveFor("orb-strategy").isEmpty(),
            "nothing says it belongs to this workspace, so it counts as foreign");
    }

    @Test
    void everyOtherLaneTravelsBetweenWorkspacesWhole() {
        store.setProvenance("jawata", null);
        record("lesson", "com.example.Ledger", "A lesson learned in another workspace.");

        StoreCorpus.LaneCorpus lessons = StoreCorpus.of(store).lane(KnowledgeLane.EXPERIENCE);
        assertEquals(1, lessons.live().size(), "PROOF OF LIFE: the lesson is in its lane");

        assertSame(lessons.live(), lessons.liveFor("orb-strategy"),
            "knowledge about work is not keyed by workspace; the lane is returned as it is,"
                + " not filtered and not copied");
    }
}
