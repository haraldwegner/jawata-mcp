package org.jawata.pitplugin;

import java.util.Collection;
import java.util.Optional;

import org.pitest.classinfo.ClassByteArraySource;
import org.pitest.help.PitHelpError;
import org.pitest.junit5.JUnit5TestPluginFactory;
import org.pitest.testapi.Configuration;
import org.pitest.testapi.TestGroupConfig;
import org.pitest.testapi.TestPluginFactory;
import org.pitest.testapi.TestSuiteFinder;
import org.pitest.testapi.TestUnitFinder;

/**
 * PIT's JUnit 5 plugin, with one difference: a test that ABORTS under a mutation, having passed
 * before it, is reported as a failure whose name carries {@link #ABORT_MARK}.
 *
 * <p>PIT's own plugin counts an aborted test (a failed assumption) as a pass, so a mutation that turns
 * a guarding test's failure into an abort comes back as a SURVIVOR, indistinguishable from a mutation
 * nothing noticed. The sprint spec asks for it as a separate signal ("a gate is swallowing the defect");
 * the mark is how it reaches the report, because the killing test's name is the one thing PIT's report
 * carries out of the mutation JVM.</p>
 *
 * <p>Tests are found by PIT's own finder; only the way a unit is run differs ({@link AbortAwareTestUnit}).
 * The priority is below the stock plugin's, so PIT asks this one first.</p>
 */
public final class AbortAwareJUnit5Plugin implements TestPluginFactory {

    /** Appended to the name of the test PIT reports as the killer of a mutation that made it abort. */
    public static final String ABORT_MARK = " [ABORTED UNDER MUTATION]";

    /** Lower than {@link Configuration#DEFAULT_PRIORITY}: PIT tries the lowest first. */
    private static final int PRIORITY = Configuration.DEFAULT_PRIORITY - 5;

    @Override
    public Configuration createTestFrameworkConfiguration(TestGroupConfig group, ClassByteArraySource source,
            Collection<String> excludedRunners, Collection<String> includedTestMethods) {
        return new AbortAwareConfiguration(new JUnit5TestPluginFactory()
            .createTestFrameworkConfiguration(group, source, excludedRunners, includedTestMethods));
    }

    @Override
    public String name() {
        return "jawata-abort-aware-junit5";
    }

    @Override
    public String description() {
        return "JUnit 5 where a test that aborts under a mutation is reported as its own kind of kill";
    }

    /**
     * PIT's own configuration, forwarded in every member but the two that make this plugin ask first
     * and run tests its own way. The environment check is PIT's too: a project without the platform
     * launcher meets PIT's diagnostic, not a failure further down.
     */
    private record AbortAwareConfiguration(Configuration stock) implements Configuration {

        @Override
        public int priority() {
            return PRIORITY;
        }

        @Override
        public TestUnitFinder testUnitFinder() {
            return new AbortAwareTestUnitFinder(stock.testUnitFinder());
        }

        @Override
        public TestSuiteFinder testSuiteFinder() {
            return stock.testSuiteFinder();
        }

        @Override
        public Optional<PitHelpError> verifyEnvironment() {
            return stock.verifyEnvironment();
        }
    }
}
