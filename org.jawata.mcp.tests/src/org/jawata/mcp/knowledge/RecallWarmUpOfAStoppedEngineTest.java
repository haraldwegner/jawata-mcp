package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S19-69 (the 4.4.19 release job, 2026-10-28): the recall warm-up of an engine that had been stopped went on to load the embedder
 * after its engine was gone, and cached an ENABLED process-wide service that the next engine in the same JVM inherited, so a test
 * that switched embedding off found it on, and waited 15 seconds for an end that came much later. A warm-up that was told to stop
 * must not create the service.
 */
class RecallWarmUpOfAStoppedEngineTest {

    @AfterEach
    void leaveNothingBehind() {
        Thread.interrupted();
        EmbeddingServiceTestHooks.forgetTheSharedService();
    }

    @Test
    @DisplayName("a recall warm-up that was told to stop does not load the embedder for the next engine (S19-69)")
    void aWarmUpThatWasToldToStopCreatesNoEmbeddingService() {
        EmbeddingServiceTestHooks.forgetTheSharedService();
        String property = EmbeddingService.DISABLE_PROPERTY;
        String before = System.getProperty(property);
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        try {
            System.clearProperty(property);
            Thread.currentThread().interrupt();

            RecallWarmUp.Warmed warmed = RecallWarmUp.run(store);
            Thread.interrupted();

            assertFalse(warmed.meaning(), "an interrupted warm-up warms nothing by meaning");
            // The next engine switches embedding off and asks for the service. If the warm-up created one first, with embedding
            // still on, that one answers and is available; if it did not, the service is created now and is switched off.
            System.setProperty(property, "true");
            assertFalse(EmbeddingService.shared().available(),
                "the interrupted warm-up created the process-wide embedding service, which the next engine in this JVM inherits");
        } finally {
            Thread.interrupted();
            if (before == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, before);
            }
            store.close();
        }
    }
}
