package org.jawata.mcp.knowledge;

/**
 * Drops the process-wide {@link EmbeddingService} so the next test that asks for it decides again (Sprint 28k, Stage 9). An engine
 * test that boots the engine with embedding switched off leaves the cached service switched off for the rest of the JVM, and every
 * later test that needs the real model then finds none and aborts. The reset itself is package-private in the host bundle; this
 * lives in its package, as the test fragment can, so a test in another package can call it.
 */
public final class EmbeddingServiceTestHooks {

    private EmbeddingServiceTestHooks() {
    }

    /** Forget the cached service, so the next ask re-reads the settings and loads the model if it is switched on. */
    public static void forgetTheSharedService() {
        EmbeddingService.resetForTests();
    }
}
