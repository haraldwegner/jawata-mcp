package org.jawata.spi.lang;

import java.util.Optional;

import org.eclipse.core.runtime.IProgressMonitor;

/** The test runner's question to a language: what does a forked test run of this project need (contract §4.5)? */
public interface TestRuntime {

    /** The runner's classpath for this project, or the reason it cannot be assembled. */
    RunnerSpec specFor(String projectName, boolean includeTestNg);

    /** Builds the project and the projects it requires; the reason it cannot run tests, or empty when it can. */
    Optional<String> buildAndCheck(String projectName, IProgressMonitor monitor);
}
