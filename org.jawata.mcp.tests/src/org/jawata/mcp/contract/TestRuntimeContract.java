package org.jawata.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jawata.spi.lang.RunnerSpec;
import org.jawata.spi.lang.TestRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What every language's test runtime promises the engine's test runner (Sprint 28k, Stage 8; contract §18 ring 1): asked for the
 * classpath of a project the language does not have, it refuses with a reason the person can read, and it does not fail.
 */
public abstract class TestRuntimeContract {

    /**
     * @return the runtime under test
     * @throws Exception when the implementation cannot be built
     */
    protected abstract TestRuntime runtime() throws Exception;

    @Test
    @DisplayName("a project the language does not have is refused with a reason, not with a failure")
    void anUnknownProjectIsRefusedWithAReason() throws Exception {
        RunnerSpec spec = runtime().specFor("no-such-project", false);

        assertNotNull(spec);
        assertTrue(spec.isRefused(), "a runner without a classpath is not a runner: " + spec);
        assertFalse(spec.refusalReason().isBlank(), "a refusal that says nothing sends the person looking for the cause");
    }

    @Test
    @DisplayName("a project the language does not have has no framework, no folders and no runtime classpath, and asking does not fail")
    void anUnknownProjectHasNothingToReport() throws Exception {
        TestRuntime runtime = runtime();

        assertTrue(runtime.frameworkFor("no-such-project", "auto").isEmpty(), "a framework cannot be read from a project that is not there");
        assertTrue(runtime.rootsOf("no-such-project").classRoots().isEmpty());
        assertTrue(runtime.rootsOf("no-such-project").sourceRoots().isEmpty());
        assertTrue(runtime.runtimeClasspath("no-such-project").isEmpty());
    }
}
