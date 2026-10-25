package org.jawata.mcp;

/**
 * Clears the degradation note for a test.
 *
 * <p>Lives in the engine's own package in the test fragment, which shares the engine's class loader, so it reaches the
 * package-private {@code clearForTest} of {@link ResidentDegradation}. A test in another package asks this instead of
 * making that hook public.</p>
 */
public final class ResidentDegradationTestHooks {

    private ResidentDegradationTestHooks() {
    }

    /** A note left behind poisons every later test. */
    public static void clear() {
        ResidentDegradation.clearForTest();
    }
}
