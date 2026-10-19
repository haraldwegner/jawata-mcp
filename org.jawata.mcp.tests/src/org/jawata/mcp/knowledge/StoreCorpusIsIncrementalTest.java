package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * REL44-49 — AFTER A WRITE THE CORPUS SNAPSHOT IS ASSEMBLED FROM THE ROWS THAT CHANGED, AND IS EXACTLY
 * WHAT A FULL READ WOULD HAVE GIVEN.
 *
 * <p>Every write moves the store's change stamp, so the next lookup rebuilt the snapshot by reading
 * every row; sampled over 10,189 rows that read was the largest part of a write's own steering
 * lookup once the word indexes were kept. The cases here hold both halves of the cure: the saving
 * (one changed row is fetched, not the whole store) and the safety (whatever the write was, the
 * snapshot is the one a full read gives, and any doubt falls back to the full read).</p>
 */
class StoreCorpusIsIncrementalTest {

    private ObjectMapper mapper;
    private H2ExperienceStore real;
    private ExperienceTool tool;
    private CountedStore counted;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        real = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, real);
        counted = new CountedStore(real);
    }

    @AfterEach
    void tearDown() {
        real.close();
    }

    /** The real store seen through a wrapper that counts the reads the corpus makes of it. */
    private static final class CountedStore {
        final ExperienceStore store;
        int allCalls;
        int byIdsCalls;
        List<String> lastIds = List.of();
        boolean hideMarks;
        boolean shortFetch;

        @SuppressWarnings("unchecked")
        CountedStore(H2ExperienceStore real) {
            this.store = (ExperienceStore) Proxy.newProxyInstance(
                ExperienceStore.class.getClassLoader(), new Class<?>[] {ExperienceStore.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "all" -> allCalls++;
                        case "rowMarks" -> {
                            if (hideMarks) {
                                return null;
                            }
                        }
                        case "byIds" -> {
                            byIdsCalls++;
                            lastIds = new ArrayList<>((List<String>) args[0]);
                        }
                        default -> { }
                    }
                    try {
                        Object answer = method.invoke(real, args);
                        if (shortFetch && "byIds".equals(method.getName())) {
                            List<StoredEntry> rows = new ArrayList<>((List<StoredEntry>) answer);
                            if (!rows.isEmpty()) {
                                rows.remove(rows.size() - 1);
                            }
                            return rows;
                        }
                        return answer;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        }
    }

    private String record(String type, String word) {
        String summary = "a " + type + " that mentions " + word + " ["
            + UUID.randomUUID() + "]";
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", type);
        a.put("summary", summary);
        if ("lesson".equals(type)) {
            a.put("situation", "when a test writes a row the corpus must read again");
            a.put("verdict", "worked");
        }
        assertTrue(tool.execute(a).isSuccess());
        return real.all().stream().filter(e -> summary.equals(e.summary()))
            .findFirst().orElseThrow().id();
    }

    /** The snapshot the corpus holds must be the one a full read of the store would give. */
    private void assertIsWhatAFullReadGives(StoreCorpus.Snapshot snap) {
        List<StoredEntry> full = real.all();
        assertEquals(full, snap.all(), "the snapshot's rows, in the store's own order");
        for (KnowledgeLane lane : KnowledgeLane.values()) {
            LexicalIndex.Corpus fresh = LexicalIndex.index(snap.lane(lane).live());
            for (String word : List.of("mentions", "corpus", "tungsten", "molybdenum")) {
                LexicalIndex.Scores kept = LexicalIndex.scoredIn(word, snap.lane(lane).words());
                LexicalIndex.Scores rebuilt = LexicalIndex.scoredIn(word, fresh);
                assertEquals(rebuilt.byId(), kept.byId(), lane + " scored '" + word + "' differently");
            }
        }
    }

    @Test
    void afterAWriteOnlyTheChangedRowIsFetchedNotTheWholeStore() {
        record("lesson", "zirconium");
        record("domain_fact", "vanadium");
        record("lesson", "tantalum");
        StoreCorpus.of(counted.store);
        int readsBefore = counted.allCalls;
        assertTrue(readsBefore >= 1, "PRECONDITION: the first snapshot reads every row");

        String added = record("lesson", "molybdenum");
        StoreCorpus.Snapshot after = StoreCorpus.of(counted.store);

        assertEquals(readsBefore, counted.allCalls,
            "a write must not make the corpus read every row of the store again");
        assertEquals(List.of(added), counted.lastIds, "only the row that was written is fetched");
        assertIsWhatAFullReadGives(after);
    }

    @Test
    void everyKindOfWriteGivesTheSnapshotAFullReadWould() {
        String lesson = record("lesson", "zirconium");
        String fact = record("domain_fact", "tungsten");
        record("lesson", "tantalum");
        assertIsWhatAFullReadGives(StoreCorpus.of(counted.store));

        record("domain_fact", "vanadium");
        assertIsWhatAFullReadGives(StoreCorpus.of(counted.store));

        real.setStatus(lesson, ExperienceEntry.REJECTED);
        assertIsWhatAFullReadGives(StoreCorpus.of(counted.store));

        assertTrue(real.rewriteForm(fact, "when the situation is rewritten in place", "worked"));
        assertIsWhatAFullReadGives(StoreCorpus.of(counted.store));

        real.deleteByIds(List.of(fact));
        assertIsWhatAFullReadGives(StoreCorpus.of(counted.store));

        record("lesson", "molybdenum");
        assertIsWhatAFullReadGives(StoreCorpus.of(counted.store));
    }

    @Test
    void aStoreThatCannotSayWhatChangedIsReadInFull() {
        counted.hideMarks = true;
        record("lesson", "zirconium");
        StoreCorpus.of(counted.store);
        int readsBefore = counted.allCalls;

        record("lesson", "molybdenum");
        StoreCorpus.Snapshot after = StoreCorpus.of(counted.store);

        assertEquals(readsBefore + 1, counted.allCalls, "no marks, so the whole store is read as it always was");
        assertIsWhatAFullReadGives(after);
    }

    @Test
    void aFetchThatReturnsFewerRowsThanAskedFallsBackToTheFullRead() {
        record("lesson", "zirconium");
        StoreCorpus.of(counted.store);
        int readsBefore = counted.allCalls;
        counted.shortFetch = true;

        record("lesson", "molybdenum");
        StoreCorpus.Snapshot after = StoreCorpus.of(counted.store);

        assertEquals(readsBefore + 1, counted.allCalls,
            "a row that did not come back must never be silently missing from the snapshot");
        assertIsWhatAFullReadGives(after);
    }

    @Test
    void aBulkChangeIsReadInFullBecauseFetchingThatManyRowsOneByOneIsNoSaving() {
        record("lesson", "zirconium");
        StoreCorpus.of(counted.store);
        int readsBefore = counted.allCalls;

        System.setProperty(StoreCorpus.MAX_INCREMENTAL_PROPERTY, "3");
        try {
            for (int i = 0; i < 6; i++) {
                record("domain_fact", "bulk" + i);
            }
            StoreCorpus.Snapshot after = StoreCorpus.of(counted.store);

            assertEquals(readsBefore + 1, counted.allCalls);
            assertIsWhatAFullReadGives(after);
        } finally {
            System.clearProperty(StoreCorpus.MAX_INCREMENTAL_PROPERTY);
        }
    }
}
