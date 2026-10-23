package org.jawata.mcp.java;

import org.jawata.core.IJdtService;
import org.jawata.core.workspace.StrictDiskSync;
import org.jawata.mcp.change.ChangeContext;
import org.jawata.spi.host.WorkspaceWrites;

/**
 * The one place a Java change's {@link ChangeContext} is built (contract revision 2.1, E7 step 5): every caller of the change
 * lifecycle builds its context here. It moves onto the Java provider at E8, where the host's own reconciler and writes replace the
 * two built here.
 *
 * <p>DECLARED UNTIL E8 (ledger 28k-C2-3): the reconciler is a {@link StrictDiskSync} over the same model — its rebuild refreshes and
 * full-builds each named project, which is exactly what the plan's parity check did itself before E7 — so behaviour is unchanged;
 * it costs one production edge from {@code org.jawata.mcp} to {@code org.jawata.core.workspace} (rule R4) that E8 removes.</p>
 */
public final class JavaChange {

    private JavaChange() {
    }

    /** The context for a change against this Java model, applied straight into the workspace. */
    public static ChangeContext contextFor(IJdtService service) {
        return new ChangeContext(WorkspaceWrites.DIRECT, new StrictDiskSync(() -> service),
            new JavaBuildVerdict(service), new JavaChangePaths(service));
    }
}
