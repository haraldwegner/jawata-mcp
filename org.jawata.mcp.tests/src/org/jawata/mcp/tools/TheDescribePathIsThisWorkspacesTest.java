package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.StoredEntry;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ARCH-C22-6 — the describe path and the job write gate are THIS workspace's, on a store shared by
 * several.
 *
 * <p>Both read jobs and areas by exact anchor with no workspace rule, so another workspace's job
 * for a same-named member was reported as "the job the store already holds" and, worse, the write
 * gate REPLACED it: one workspace silently overwrote the wording another had written. A write
 * takes the strict reading (ARCH-C22-4, part a): it never mutates a row this workspace does not
 * own, so the other workspace's row stays exactly as written and this workspace's description is
 * inserted beside it.</p>
 */
class TheDescribePathIsThisWorkspacesTest {

    private static final String MEMBER = "com.example.shared.Dial#angle";
    private static final String THEIR_JOB = "Answers the needle's angle in degrees clockwise from rest.";
    private static final String MY_JOB = "Answers how far the needle has swung since the last reading.";

    private H2ExperienceStore store;
    private ExperienceTool tool;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
        json = new ObjectMapper();
        store.setProvenance("jawata", null);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private ToolResponse recordJob(String symbol, String summary) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "job");
        a.put("symbol", symbol);
        a.put("summary", summary);
        return tool.execute(a);
    }

    private void recordArea(String pkg, String summary) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "area");
        a.put("summary", summary);
        a.putArray("packages").add(pkg);
        assertTrue(tool.execute(a).isSuccess(), "fixture area refused");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jobsFor(String symbol) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "describe");
        a.put("action", "jobs_for");
        a.putArray("symbols").add(symbol);
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "got " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> describing() {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "stats");
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "got " + r.getError());
        return (Map<String, Object>) ((Map<String, Object>) r.getData()).get("describing");
    }

    private List<StoredEntry> allJobsFor(String symbol) {
        return store.anchoredAt("job", ExperienceStore.Anchor.SYMBOL, List.of(symbol))
            .getOrDefault(symbol, List.of());
    }

    @Test
    void anotherWorkspacesJobIsNotTheJobThisStoreAlreadyHoldsForThisWorkspace() {
        assertTrue(recordJob(MEMBER, THEIR_JOB).isSuccess(), "fixture job refused");
        store.setProvenance("orb-strategy", null);

        Map<String, Object> data = jobsFor(MEMBER);

        assertEquals(List.of(MEMBER), data.get("missing"),
            "this workspace has not described the member, so it is undescribed here: " + data);
        assertEquals(List.of(), data.get("described"),
            "another workspace's wording offered as the job already held: " + data);
    }

    @Test
    void aWriteNeverOverwritesAnotherWorkspacesJob() {
        assertTrue(recordJob(MEMBER, THEIR_JOB).isSuccess(), "fixture job refused");
        store.setProvenance("orb-strategy", null);

        ToolResponse mine = recordJob(MEMBER, MY_JOB);
        assertTrue(mine.isSuccess(), "this workspace's own description is accepted: " + mine.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> written = (Map<String, Object>) mine.getData();
        assertFalse(written.containsKey("replaced"),
            "nothing of this workspace's was replaced, so the answer must not say it replaced a row: "
                + written);
        Set<String> texts = allJobsFor(MEMBER).stream().map(StoredEntry::summary)
            .collect(Collectors.toSet());
        assertEquals(Set.of(THEIR_JOB, MY_JOB), texts,
            "both descriptions stand, the other workspace's exactly as it wrote it: " + texts);
    }

    @Test
    void aWorkspaceStillReplacesItsOwnJob() {
        assertTrue(recordJob(MEMBER, THEIR_JOB).isSuccess(), "fixture job refused");
        ToolResponse again = recordJob(MEMBER, MY_JOB);
        assertTrue(again.isSuccess(), "got " + again.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> written = (Map<String, Object>) again.getData();
        assertTrue(written.containsKey("replaced"),
            "CONTROL: within one workspace a re-description still replaces and says so: " + written);
        assertEquals(1, allJobsFor(MEMBER).size(), "one job per member within a workspace");
    }

    @Test
    void theDescribedAreasAreThisWorkspacesOnly() {
        recordArea("org.jawata.mcp.tools", "The tool surface: the registry and the tools it publishes.");
        store.setProvenance("orb-strategy", null);
        recordArea("pipeline", "Where a strategy's slots are claimed, worked and released.");

        @SuppressWarnings("unchecked")
        List<String> described = (List<String>) describing().get("describedAreas");

        assertTrue(described.contains("pipeline"), "PROOF OF LIFE: its own area is listed: " + described);
        assertFalse(described.contains("org.jawata.mcp.tools"),
            "another product's package listed as described here, so an agent would never describe"
                + " its own package of that name: " + described);
    }

    @Test
    void aStoreWithNoIdentityStillSeesEverything() {
        assertTrue(recordJob(MEMBER, THEIR_JOB).isSuccess(), "fixture job refused");
        store.setProvenance(null, null);

        Map<String, Object> data = jobsFor(MEMBER);

        assertEquals(List.of(Map.of("symbol", MEMBER, "job", THEIR_JOB)), data.get("described"),
            "tests and standalone residents carry no identity and keep every row: " + data);
    }
}
