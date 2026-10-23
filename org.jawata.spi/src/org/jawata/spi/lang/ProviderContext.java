package org.jawata.spi.lang;

import org.jawata.spi.host.Reconciler;
import org.jawata.spi.host.WorkspaceWrites;

/**
 * What the engine hands a language provider, and nothing host-specific (contract §4.5). At E8 it carries the host ports that exist:
 * the reconciler and the writes. The rest of the contract's members — the host itself, settings, directories, the person channel —
 * join it when their types land (E13–E18), and {@code host()} then carries these two.
 */
public interface ProviderContext {

    /** How the host refreshes and builds projects. */
    Reconciler reconciler();

    /** How a change reaches the host's files. */
    WorkspaceWrites writes();
}
