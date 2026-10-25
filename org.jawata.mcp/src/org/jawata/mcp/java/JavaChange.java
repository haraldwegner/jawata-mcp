package org.jawata.mcp.java;

import org.jawata.core.IJdtService;
import org.jawata.mcp.change.ChangeContext;
import org.jawata.spi.host.Reconciler;
import org.jawata.spi.host.WorkspaceWrites;

/**
 * The one place a Java change's {@link ChangeContext} is built (contract revision 2.1, E7 step 5): every caller of the change
 * lifecycle builds its context here, so the verdict and the paths a change is checked with are the Java provider's own.
 *
 * <p>The host half, the reconciler and the writes, is the host's: {@link JavaProvider} hands it in when the host binds
 * ({@link #hostedBy}), the same reconciler the registry runs before every call, so the change path and the call path share one
 * monitor. Asking for a context before a host has supplied them is an error, not a default: a default would be a reconciler this
 * package built for itself, which is the edge from {@code org.jawata.mcp} to the host's workspace classes that rule R4 forbids
 * (ledger 28k-C2-3).</p>
 */
public final class JavaChange {

    private static volatile Reconciler reconciler;
    private static volatile WorkspaceWrites writes;

    private JavaChange() {
    }

    /**
     * Takes the host half of every later context from the host.
     *
     * @param hostReconciler how the host brings the workspace up to date with the disk and builds it
     * @param hostWrites     how the host lands a change's text edits
     */
    public static void hostedBy(Reconciler hostReconciler, WorkspaceWrites hostWrites) {
        reconciler = hostReconciler;
        writes = hostWrites;
    }

    /** The context for a change against this Java model, applied through the host's writes. */
    public static ChangeContext contextFor(IJdtService service) {
        Reconciler hostReconciler = reconciler;
        WorkspaceWrites hostWrites = writes;
        if (hostReconciler == null || hostWrites == null) {
            throw new IllegalStateException(
                "No host has supplied its reconciler and writes: JavaChange.hostedBy runs when the Java provider binds to a host");
        }
        return new ChangeContext(hostWrites, hostReconciler, new JavaBuildVerdict(service), new JavaChangePaths(service));
    }
}
