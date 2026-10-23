package org.jawata.mcp.change;

import org.jawata.spi.host.Reconciler;
import org.jawata.spi.host.WorkspaceWrites;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.ChangePaths;

/**
 * What the change lifecycle needs to apply a change and check it, and nothing of any language (contract revision 2.1, §4.4): how
 * the change reaches the files ({@code writes}), how the host rebuilds projects for a plan's parity check ({@code reconciler}),
 * which errors exist ({@code verdict}), and how the changed files are named ({@code paths}). A parameter object: the lifecycle used
 * to receive the whole Java model to ask these four things.
 */
public record ChangeContext(WorkspaceWrites writes, Reconciler reconciler, BuildVerdict verdict, ChangePaths paths) {
}
