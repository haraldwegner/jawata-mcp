package org.jawata.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.jawata.spi.host.BuildKind;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.host.Reconciler;
import org.jawata.spi.host.SyncReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What every host's reconciler promises the engine (Sprint 28k, Stage 8; contract §18 ring 1): before a call the workspace catches up with
 * the disk and says what it did, a second ask with nothing changed does nothing, and a build of nothing answers an empty set rather than
 * an error.
 */
public abstract class ReconcilerContract {

    /**
     * @return the reconciler under test, over a workspace in which nothing changes while the test runs
     * @throws Exception when the implementation cannot be built
     */
    protected abstract Reconciler reconciler() throws Exception;

    @Test
    @DisplayName("catching up before a call answers a report")
    void syncAnswersAReport() throws Exception {
        SyncReport report = reconciler().syncBeforeCall();

        assertNotNull(report);
        assertNotNull(report.refreshedPaths(), "the paths it refreshed are a list, empty when it refreshed none");
    }

    @Test
    @DisplayName("a second ask with nothing changed on disk reconciles nothing")
    void nothingChangedMeansNothingReconciled() throws Exception {
        Reconciler reconciler = reconciler();
        reconciler.syncBeforeCall();

        SyncReport second = reconciler.syncBeforeCall();

        assertFalse(second.reconciled(), "a report that says it reconciled something the second time makes every call pay for it: " + second);
    }

    @Test
    @DisplayName("a build of nothing answers a set, and does not fail")
    void aBuildOfNothingAnswersASet() throws Exception {
        assertNotNull(reconciler().rebuild(BuildScope.of(), BuildKind.INCREMENTAL, new NullProgressMonitor()));
    }
}
