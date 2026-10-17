package org.jawata.pitplugin;

import java.util.Optional;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.pitest.testapi.AbstractTestUnit;
import org.pitest.testapi.Description;
import org.pitest.testapi.ExecutedInDiscovery;
import org.pitest.testapi.ResultCollector;

/**
 * One JUnit 5 test, run the way PIT's own unit runs it, except for what happens when it ABORTS.
 *
 * <p>An abort is a pass, as in the stock plugin, in every case but one: the test aborted under a
 * mutation (the call is inside PIT's mutation run) and did not abort before any mutation
 * ({@link AbortBaseline#abortedBeforeMutation}). That one is a failure, reported under the test's name
 * with {@link AbortAwareJUnit5Plugin#ABORT_MARK}, because the mutation is what made it abort. A test
 * that aborts unmutated aborts under every mutation, and blaming it on them would blame them all.</p>
 */
final class AbortAwareTestUnit extends AbstractTestUnit implements ExecutedInDiscovery {

    private final Class<?> testClass;
    private final String uniqueId;

    AbortAwareTestUnit(Description found, Class<?> testClass) {
        super(found);
        this.testClass = testClass;
        this.uniqueId = found.getName();
    }

    @Override
    public void execute(ResultCollector collector) {
        Launcher launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(new Reporting(collector));
        launcher.execute(LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectUniqueId(uniqueId)).build(), new TestExecutionListener[0]);
    }

    @Override
    public String toString() {
        return "AbortAwareTestUnit[" + uniqueId + "]";
    }

    /** PIT's own listener, with the abort case split in two. */
    private final class Reporting implements TestExecutionListener {

        private final ResultCollector collector;

        Reporting(ResultCollector collector) {
            this.collector = collector;
        }

        @Override
        public void executionSkipped(TestIdentifier id, String reason) {
            if (id.isTest()) {
                collector.notifySkipped(new Description(id.getUniqueId(), testClass));
            }
        }

        @Override
        public void executionStarted(TestIdentifier id) {
            if (id.isTest()) {
                collector.notifyStart(new Description(id.getUniqueId(), testClass));
            }
        }

        @Override
        public void executionFinished(TestIdentifier id, TestExecutionResult result) {
            Optional<Throwable> thrown = result.getThrowable();
            Description plain = new Description(id.getUniqueId(), testClass);
            if (!id.isTest()) {
                thrown.ifPresent(t -> collector.notifyEnd(plain, t));
            } else if (result.getStatus() == TestExecutionResult.Status.ABORTED) {
                reportAbort(id, plain, thrown);
            } else if (thrown.isPresent()) {
                collector.notifyEnd(plain, thrown.get());
            } else {
                collector.notifyEnd(plain);
            }
        }

        private void reportAbort(TestIdentifier id, Description plain, Optional<Throwable> thrown) {
            if (AbortBaseline.enabled() && AbortBaseline.inMutationRun()
                    && !AbortBaseline.abortedBeforeMutation(id.getUniqueId())) {
                collector.notifyEnd(new Description(id.getUniqueId() + AbortAwareJUnit5Plugin.ABORT_MARK, testClass),
                    thrown.orElseGet(() -> new AssertionError("the test aborted under the mutation")));
            } else {
                collector.notifyEnd(plain);
            }
        }
    }
}
