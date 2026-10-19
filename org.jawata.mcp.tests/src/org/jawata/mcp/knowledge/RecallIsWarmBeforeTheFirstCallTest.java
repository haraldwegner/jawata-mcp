package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.UUID;

import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * REL44-8, the cold-start case — THE FIRST RECALL AFTER A RESTART MUST NOT PAY FOR BUILDING WHAT EVERY
 * LATER RECALL FINDS READY.
 *
 * <p>Measured on a restart over a 10,189-row store: the first recall took ~710 ms and, given the hook's
 * 200 ms budget, gave no answer; recalls two to fourteen answered in 120 to 230 ms. The difference was
 * the corpus snapshot (every row read once), the vectors (read once) and the embedder (loaded on first
 * use) — three things built on the first call that arrives, which is the hook's. The warm-up builds
 * them at start, so that call finds them built.</p>
 */
class RecallIsWarmBeforeTheFirstCallTest {

    private H2ExperienceStore real;
    private ExperienceStore counted;
    private int allCalls;

    @BeforeEach
    void setUp() {
        real = H2ExperienceStore.open(null);
        counted = (ExperienceStore) Proxy.newProxyInstance(ExperienceStore.class.getClassLoader(),
            new Class<?>[] {ExperienceStore.class}, (proxy, method, args) -> {
                if ("all".equals(method.getName())) {
                    allCalls++;
                }
                try {
                    return method.invoke(real, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        ExperienceTool tool = new ExperienceTool(() -> null, real);
        ObjectMapper mapper = new ObjectMapper();
        for (int i = 0; i < 5; i++) {
            ObjectNode a = mapper.createObjectNode();
            a.put("kind", "record");
            a.put("type", "domain_fact");
            a.put("summary", "fact " + i + " about the ledger [" + UUID.randomUUID() + "]");
            assertTrue(tool.execute(a).isSuccess());
        }
    }

    @AfterEach
    void tearDown() {
        real.close();
    }

    @Test
    void after_the_warm_up_the_first_lookup_reads_no_row_at_all() {
        RecallWarmUp.Warmed warmed = RecallWarmUp.run(counted);
        int readsByWarmUp = allCalls;

        StoreCorpus.of(counted).lane(KnowledgeLane.DOMAIN);

        assertTrue(readsByWarmUp >= 1, "the warm-up must have read the store, or it warmed nothing");
        assertEquals(readsByWarmUp, allCalls,
            "the first lookup after the warm-up read the whole store again — the snapshot was not kept");
        assertEquals(5, warmed.rows(), "the warm-up reports the rows it made ready");
    }

    @Test
    void without_the_warm_up_the_first_lookup_pays_for_the_snapshot() {
        // The other half of the discriminator: with the warm-up deleted this is what the first call does.
        StoreCorpus.of(counted);

        assertTrue(allCalls >= 1, "an unwarmed first lookup reads the store; the case above is vacuous otherwise");
    }

    @Test
    void the_embedder_and_the_vectors_are_made_ready_when_there_is_one() {
        // The real store, not the counting wrapper: meaning needs the concrete H2 store to hold vectors.
        RecallWarmUp.Warmed warmed = RecallWarmUp.run(real);

        assertEquals(EmbeddingService.shared().available(), warmed.meaning(),
            "the meaning half is warmed exactly when an embedder exists");
    }

    @Test
    void a_store_that_cannot_be_read_is_reported_not_thrown() {
        real.close();

        RecallWarmUp.Warmed warmed = RecallWarmUp.run(counted);

        assertEquals(0, warmed.rows());
    }
}
