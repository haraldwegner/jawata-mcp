package org.jawata.pitplugin;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/**
 * Writes down the tests that ABORT before any mutation, wherever PIT runs them.
 *
 * <p>PIT's own JUnit 5 plugin runs every test of a class once while it discovers the tests, in the
 * coverage JVM, and marks the units as already executed; so that run never reaches
 * {@link AbortAwareTestUnit} and only a listener the JUnit launcher itself loads can see it. This is
 * that listener, registered by service file. It does nothing outside a PIT run the engine started
 * ({@link AbortBaseline#enabled()}), and nothing inside the mutation run, where an abort is the
 * mutation's doing and is reported by {@link AbortAwareTestUnit}.</p>
 */
public final class AbortBaselineListener implements TestExecutionListener {

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (id.isTest() && result.getStatus() == TestExecutionResult.Status.ABORTED
                && AbortBaseline.enabled() && !AbortBaseline.inMutationRun()) {
            AbortBaseline.record(id.getUniqueId());
        }
    }
}
