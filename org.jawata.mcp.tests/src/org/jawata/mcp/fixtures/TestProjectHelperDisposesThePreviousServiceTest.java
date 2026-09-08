package org.jawata.mcp.fixtures;

import org.jawata.core.JdtServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mcp#24 — the helper must not leak a service when a test loads twice.
 *
 * <p><b>What this can and cannot prove.</b> The reported symptom is a nondeterministic
 * flake: a scan failing with <i>"&lt;project root&gt; ... is not on its project's build
 * path"</i>, which cannot be made to fire on demand. So this does NOT assert the flake is
 * gone — that would be unfalsifiable. It asserts the LEAK, which is deterministic, and
 * which {@code TestProjectHelper.afterEach} names in its own comment as "the substrate of
 * the load-dependent lookup-failure flakes": every service a test abandoned stayed live in
 * the JVM-shared Eclipse workspace with its linked projects still registered, and a stale
 * live handle can answer a workspace-scoped lookup.
 *
 * <p>The field held only the LAST service, and {@code afterEach} disposed only that one, so
 * before this fix a test calling a load method twice leaked the first for the rest of the
 * JVM.
 */
class TestProjectHelperDisposesThePreviousServiceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("loading a second copy disposes the first service, and the second still works")
    void theAbandonedServiceIsDisposed() throws Exception {
        JdtServiceImpl first = helper.loadProjectCopy("simple-maven");
        assertFalse(first.projectKeys().isEmpty(),
            "PROOF OF LIFE: the first service must actually hold a project, or the"
                + " disposal assertion below would pass over a service that loaded nothing");

        JdtServiceImpl second = helper.loadProjectCopy("simple-maven");

        // assertAll, because JUnit stops at the first failure and the CONTROL below is the
        // half that makes the disposal assertion mean anything.
        assertAll(
            () -> assertNotSame(first, second, "the helper must hand out a fresh service"),
            () -> assertTrue(first.projectKeys().isEmpty(),
                "the ABANDONED service must be disposed — it still holds "
                    + first.projectKeys() + ", which stays live in the JVM-shared workspace"),
            () -> assertFalse(second.projectKeys().isEmpty(),
                "CONTROL: the surviving service must still hold its project. Without this,"
                    + " a helper that disposed BOTH would pass the assertion above"));
    }

    @Test
    @DisplayName("the guard is on every load path, not just the copying one")
    void theNonCopyingPathIsGuardedToo() throws Exception {
        JdtServiceImpl first = helper.loadProject("simple-maven");
        assertFalse(first.projectKeys().isEmpty(), "PROOF OF LIFE: the first service loaded");

        JdtServiceImpl second = helper.loadProjectCopy("simple-maven");

        assertAll(
            () -> assertTrue(first.projectKeys().isEmpty(),
                "loadProject's service must be disposed when the next load replaces it"),
            () -> assertFalse(second.projectKeys().isEmpty(), "CONTROL: the survivor holds its project"));
    }
}
