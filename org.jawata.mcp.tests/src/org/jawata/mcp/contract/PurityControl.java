package org.jawata.mcp.contract;

import org.eclipse.jdt.core.IJavaProject;
import org.jawata.core.IJdtService;

/**
 * The positive control of the purity scan (Sprint 28k, Stage 8; contract §18 ring 1): a class that does exactly what the neutral core must
 * not, so the scan can be shown to turn red. It is compiled with the tests and read by the scan as bytes; nothing ever calls it.
 */
final class PurityControl {

    private PurityControl() {
    }

    /** Names a JDT type and a jawata core type in its signature, and ends the process. */
    static void doesWhatTheNeutralCoreMustNot(IJavaProject jdt, IJdtService model) {
        System.exit(jdt == null && model == null ? 1 : 0);
    }
}
