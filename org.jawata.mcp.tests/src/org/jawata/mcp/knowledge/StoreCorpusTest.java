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
        assertTrue(LexicalIndex.scoredIn("molybdenum", after.words()).byId().containsKey(added),
            "and the word index must be rebuilt with it, or recall cannot find it by its words");
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
