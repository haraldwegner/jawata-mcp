package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ARCH-C22-7 — a listing's window is filled by THIS workspace's rows, not by the machine's.
 *
 * <p>The describe path listed the newest N rows of a type and filtered by workspace afterwards, so
 * on a store shared by many workspaces another workspace's newer rows filled the window and this
 * workspace's older ones never arrived. {@code listEntriesHere} puts the workspace rule in the
 * query, before the limit.</p>
 *
 * <p>The first case shows the defect and the cure side by side, which is its own control: the old
 * way of asking returns nothing for the same store the new way answers.</p>
 */
class AListingWindowIsFilledByThisWorkspaceTest {

    private H2ExperienceStore store;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private void job(String symbol, String summary) throws InterruptedException {
        store.put(ExperienceEntry.of(
            SymbolFact.of("job", summary, Confidence.MEDIUM).symbol(symbol).build()).build());
        Thread.sleep(8); // creation instants decide "newest"; do not let two writes tie
    }

    @Test
    void foreignRowsThatAreNewerDoNotCrowdOutThisWorkspacesRows() throws InterruptedException {
        store.setProvenance("orb-strategy", null);
        job("pipeline.SlotManager#freeSlot", "Frees a trading slot once its order has ended.");
        store.setProvenance("jawata", null);
        job("org.jawata.A#one", "The first newer foreign job.");
        job("org.jawata.A#two", "The second newer foreign job.");
        job("org.jawata.A#three", "The third newer foreign job.");
        store.setProvenance("orb-strategy", null);

        List<StoredEntry> oldWay = store.listEntries("job", null, null, null, 2).stream()
            .filter(e -> e.answersFor("orb-strategy")).toList();
        assertTrue(oldWay.isEmpty(),
            "CONTROL, the defect: a window of two filled by the three newer foreign rows leaves"
                + " this workspace's older row out of reach: " + oldWay);

        List<String> here = store.listEntriesHere("job", 2).stream().map(StoredEntry::symbolFqn)
            .collect(Collectors.toList());
        assertEquals(List.of("pipeline.SlotManager#freeSlot"), here,
            "the window is filled by this workspace's rows");
    }

    @Test
    void theWindowStillHoldsTheNewestOfThisWorkspacesOwnRows() throws InterruptedException {
        store.setProvenance("orb-strategy", null);
        job("pipeline.A#old", "The oldest own job.");
        job("pipeline.B#mid", "The middle own job.");
        job("pipeline.C#new", "The newest own job.");

        List<String> here = store.listEntriesHere("job", 2).stream().map(StoredEntry::symbolFqn)
            .collect(Collectors.toList());

        assertEquals(List.of("pipeline.C#new", "pipeline.B#mid"), here, "newest first, cut at the limit");
    }

    @Test
    void aStoreWithNoIdentityListsEveryRow() throws InterruptedException {
        store.setProvenance("jawata", null);
        job("org.jawata.A#one", "A foreign job.");
        store.setProvenance(null, null);
        job("pipeline.B#two", "An unstamped job.");

        assertEquals(2, store.listEntriesHere("job", 10).size(),
            "no identity: nothing is withheld, as everywhere else");
    }

    @Test
    void aBlankIdentityIsNoIdentityAndListsEveryRow() throws InterruptedException {
        store.setProvenance("jawata", null);
        job("org.jawata.A#one", "A foreign job.");
        store.setProvenance("  ", null);
        job("pipeline.B#two", "A job written under a blank identity.");

        assertEquals(2, store.listEntriesHere("job", 10).size(),
            "a blank identity is no identity, as KnowledgeLane#filtersFor says");
    }

    @Test
    void anUnstampedRowIsLeftOutOnceThereIsAnIdentity() throws InterruptedException {
        store.setProvenance(null, null);
        job("legacy.Unknown#thing", "Written before rows were stamped.");
        store.setProvenance("orb-strategy", null);

        assertTrue(store.listEntriesHere("job", 10).isEmpty(),
            "the same answer the in-memory rule gives: nothing says it belongs here");
    }

    @Test
    void theQueryAndTheRuleAgreeOnEveryRow() throws InterruptedException {
        store.setProvenance("orb-strategy", null);
        job("pipeline.A#one", "Own one.");
        store.setProvenance("jawata", null);
        job("org.jawata.B#two", "Foreign two.");
        store.setProvenance(null, null);
        job("legacy.C#three", "Unstamped three.");
        store.setProvenance("orb-strategy", null);
        job("pipeline.D#four", "Own four.");

        List<String> viaQuery = store.listEntriesHere("job", 100).stream()
            .map(StoredEntry::symbolFqn).toList();
        List<String> viaRule = store.listEntries("job", null, null, null, 100).stream()
            .filter(e -> e.answersFor("orb-strategy")).map(StoredEntry::symbolFqn).toList();

        assertEquals(List.of("pipeline.D#four", "pipeline.A#one"), viaQuery,
            "PROOF OF LIFE: both own rows, newest first, and neither of the others");
        assertEquals(viaRule, viaQuery,
            "the workspace match in the SQL must be the same rule KnowledgeLane#answersFor states");
    }
}
