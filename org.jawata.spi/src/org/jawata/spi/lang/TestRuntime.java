package org.jawata.spi.lang;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.eclipse.core.runtime.IProgressMonitor;

/** The test runner's question to a language: what does a forked test run of this project need (contract §4.5)? */
public interface TestRuntime {

    /** The runner's classpath for this project, or the reason it cannot be assembled. */
    RunnerSpec specFor(String projectName, boolean includeTestNg);

    /** Builds the project and the projects it requires; the reason it cannot run tests, or empty when it can. */
    Optional<String> buildAndCheck(String projectName, IProgressMonitor monitor);

    /**
     * The test framework the project's tests run under.
     *
     * @param projectName the project, by the name the language builds it under
     * @param requested   what the caller asked for: a framework's name, or {@code auto} to let the language read it from the project
     * @return the framework's name, or empty when {@code auto} found none
     */
    Optional<String> frameworkFor(String projectName, String requested);

    /**
     * @param projectName the project, by the name the language builds it under
     * @return the folder a forked run of its tests starts in, the one the project's own build starts them in
     */
    Path workingFolder(String projectName);

    /**
     * The test the person pointed at.
     *
     * @param file       a source file
     * @param line       the line, counted from zero
     * @param column     the column, counted from zero
     * @param wantMethod whether to find the test method at the position (and its class), or only the class
     * @return the class and, when asked for, the method; empty when there is none at that position
     */
    Optional<TestTarget> testAt(Path file, int line, int column, boolean wantMethod);

    /**
     * @param projectName the project, by the name the language builds it under
     * @return where its classes are built and where its sources are
     */
    ProjectRoots rootsOf(String projectName);

    /**
     * @param projectName the project, by the name the language builds it under
     * @return everything its own code needs at run time, as paths, without the runner's tools added
     */
    List<String> runtimeClasspath(String projectName);
}
