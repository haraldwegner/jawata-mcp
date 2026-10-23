package org.jawata.mcp.java;

import org.jawata.core.IJdtService;
import org.jawata.core.workspace.StrictDiskSync;
import org.jawata.mcp.change.ChangeContext;
import org.jawata.spi.host.Reconciler;
import org.jawata.spi.host.WorkspaceWrites;
import org.jawata.spi.lang.ProviderContext;

/**
 * The one place a Java change's {@link ChangeContext} is built (contract revision 2.1, E7 step 5): every caller of the change
 * lifecycle builds its context here, through {@link JavaProvider} (E8), so the verdict and the paths a change is checked with are
 * the provider's own.
 *
 * <p>DECLARED UNTIL E18 (ledger 28k-C2-3): the host half — the reconciler and the writes — is built here, not asked of a host: the
 * reconciler is a {@link StrictDiskSync} over the same model (its rebuild refreshes and full-builds each named project, which is
 * exactly what the plan's parity check did itself before E7), the writes are {@link WorkspaceWrites#DIRECT}. Behaviour is
 * unchanged; it costs one production edge from {@code org.jawata.mcp} to {@code org.jawata.core.workspace} (rule R4) that E18's
 * {@code EngineHost} removes.</p>
 */
public final class JavaChange {

    private JavaChange() {
    }

    /** The context for a change against this Java model, applied straight into the workspace. */
    public static ChangeContext contextFor(IJdtService service) {
        Reconciler reconciler = new StrictDiskSync(() -> service);
        ProviderContext host = new ProviderContext() {
            @Override
            public Reconciler reconciler() {
                return reconciler;
            }

            @Override
            public WorkspaceWrites writes() {
                return WorkspaceWrites.DIRECT;
            }
        };
        return new JavaProvider(() -> service).changeContext(host);
    }
}
