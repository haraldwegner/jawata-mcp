package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE IN-MEMORY CORPUS IS REUSED WHILE NOTHING CHANGES, AND NEVER SERVED STALE.
 *
 * <p><b>The case (2026-09-14).</b> Recall read every entry and rebuilt the word index on
 * every call; over 10,257 entries that took 1.5–1.6 s against the hook's 1.2 s budget.
 * {@link StoreCorpus} now holds both, keyed by {@link ExperienceStore#changeStamp()}. The
 * speed is the point, and a cache is only acceptable if every kind of write is seen:
 * these tests make one write of each kind and require the next snapshot to reflect it.</p>
 */
class StoreCorpusTest {

    private ObjectMapper mapper;
    private H2ExperienceStore store;
    private ExperienceTool tool;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    /** Record one lesson carrying {@code word}, and answer its id. */
    private String record(String word) {
        String summary = "a lesson that mentions " + word + " so the word index can find it ["
            + UUID.randomUUID() + "]";
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "lesson");
        a.put("summary", summary);
        a.put("situation", "when a test writes a row the cached corpus must notice");
        a.put("verdict", "worked");
        a.putArray("symptoms").add("the recall answered from an older copy of the store");
        assertTrue(tool.execute(a).isSuccess());
        return store.all().stream().filter(e -> summary.equals(e.summary()))
            .findFirst().orElseThrow().id();
    }

    private static boolean holds(List<StoredEntry> rows, String id) {
        return rows.stream().anyMatch(e -> id.equals(e.id()));
    }

    @Test
    void anUnchangedStoreHandsBackTheSameSnapshot() {
        record("zirconium");
        record("vanadium");
        assertNotNull(store.changeStamp(),
            "PRECONDITION: an H2 store must report a stamp, or nothing is ever cached and"
                + " the reuse below would pass for the wrong reason");
        assertSame(StoreCorpus.of(store), StoreCorpus.of(store),
            "nothing was written between the two calls, so the rows must not be read again");
    }

    @Test
    void aNewRowIsInTheNextSnapshotAndItsWordsScore() {
        record("zirconium");
        record("vanadium");
        StoreCorpus.Snapshot before = StoreCorpus.of(store);

        String added = record("molybdenum");
        StoreCorpus.Snapshot after = StoreCorpus.of(store);

        assertNotSame(before, after, "a written row must invalidate the snapshot");
        assertTrue(holds(after.all(), added), "the new row must be in the corpus");
        boolean scoredInSomeLane = false;
        for (KnowledgeLane lane : KnowledgeLane.values()) {
            scoredInSomeLane |= LexicalIndex.scoredIn("molybdenum", after.lane(lane).words())
                .byId().containsKey(added);
        }
        assertTrue(scoredInSomeLane,
            "and the word index of the row's own lane must be rebuilt with it, or recall cannot"
                + " find it by its words");
    }

    /** Record one domain fact carrying {@code word} — a row of a different lane than a lesson. */
    private String recordFact(String word) {
        String summary = "a fact that mentions " + word + " so the domain lane has a row ["
            + UUID.randomUUID() + "]";
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "domain_fact");
        a.put("summary", summary);
        assertTrue(tool.execute(a).isSuccess());
        return store.all().stream().filter(e -> summary.equals(e.summary()))
            .findFirst().orElseThrow().id();
    }

    /**
     * REL44-49 — A WRITE TO ONE LANE DOES NOT RE-INDEX THE OTHERS.
     *
     * <p>Every write moves the change stamp, so the next lookup rebuilt the whole snapshot, the word
     * index of every lane included; sampled on a store of 10,189 rows that rebuild was 43% of a
     * write's own steering lookup, and the lookup is what misses its 400 ms budget under load. A
     * lane's index is a pure function of that lane's rows, so a lane whose rows did not change keeps
     * the index it had, and only the lane that was written is indexed again.</p>
     */
    @Test
    void aWriteToOneLaneKeepsTheWordIndexOfTheOthers() {
        recordFact("tungsten");
        record("zirconium");
        StoreCorpus.Snapshot before = StoreCorpus.of(store);

        String added = record("molybdenum");
        StoreCorpus.Snapshot after = StoreCorpus.of(store);

        assertNotSame(before.lane(KnowledgeLane.EXPERIENCE).words(),
            after.lane(KnowledgeLane.EXPERIENCE).words(),
            "the lane that was written must be indexed again, or its new row cannot be found");
        assertTrue(holds(after.lane(KnowledgeLane.EXPERIENCE).live(), added));
        assertSame(before.lane(KnowledgeLane.DOMAIN).words(),
            after.lane(KnowledgeLane.DOMAIN).words(),
            "a lane nothing was written to keeps its word index instead of building it again");
    }

    /** A kept index must answer exactly as a freshly built one would — reuse may never change a score. */
    @Test
    void aKeptWordIndexScoresExactlyAsAFreshOneWould() {
        recordFact("tungsten");
        recordFact("tantalum");
        StoreCorpus.of(store);
        record("molybdenum");
        StoreCorpus.Snapshot after = StoreCorpus.of(store);

        StoreCorpus.LaneCorpus domain = after.lane(KnowledgeLane.DOMAIN);
        LexicalIndex.Corpus fresh = LexicalIndex.index(domain.live());
        for (String word : List.of("tungsten", "tantalum", "mentions")) {
            LexicalIndex.Scores kept = LexicalIndex.scoredIn(word, domain.words());
            LexicalIndex.Scores rebuilt = LexicalIndex.scoredIn(word, fresh);
            org.junit.jupiter.api.Assertions.assertEquals(rebuilt.byId(), kept.byId(),
                "the kept index scored '" + word + "' differently from a fresh one");
            org.junit.jupiter.api.Assertions.assertEquals(rebuilt.ceiling(), kept.ceiling());
        }
    }

    @Test
    void aRejectedRowLeavesTheLiveCorpus() {
        record("zirconium");
        String doomed = record("vanadium");
        assertTrue(holds(StoreCorpus.of(store).live(), doomed), "PRECONDITION: live before");

        store.setStatus(doomed, ExperienceEntry.REJECTED);
        StoreCorpus.Snapshot after = StoreCorpus.of(store);

        assertFalse(holds(after.live(), doomed),
            "a rejected row must not stay nominable because the corpus was cached");
        assertTrue(holds(after.all(), doomed), "it is still in the store, only not live");
    }

    @Test
    void aDeletedRowLeavesTheCorpus() {
        record("zirconium");
        String gone = record("vanadium");
        assertTrue(holds(StoreCorpus.of(store).all(), gone), "PRECONDITION: present before");

        store.deleteByIds(List.of(gone));

        assertFalse(holds(StoreCorpus.of(store).all(), gone),
            "a deleted row must not survive in the cached corpus");
    }
}
