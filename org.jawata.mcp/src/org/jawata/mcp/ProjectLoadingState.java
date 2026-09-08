package org.jawata.mcp;

/**
 * Represents the state of project loading.
 * Used to provide feedback to AI agents about whether a project
 * is still loading, loaded successfully, or failed to load.
 */
public enum ProjectLoadingState {
    /**
     * No project configured or requested.
     */
    NOT_LOADED,

    /**
     * Project is currently being loaded (async operation in progress).
     */
    LOADING,

    /**
     * Project loaded successfully and is ready for use.
     */
    LOADED,

    /**
     * Project loading failed with an error.
     */
    FAILED;

    /**
     * mcp#65 — has the load NOT finished yet, so that an absence is "not yet" rather than
     * "no"? True for {@link #LOADING} and, crucially, for {@link #NOT_LOADED}.
     *
     * <p>The question lives on the state rather than at each reader because the first version
     * asked it at the wiring site as {@code state == LOADING}, and that omission had a live
     * cost: {@code JawataApplication.start()} installs the workspace identity, DISPATCHES the
     * async load, and begins serving — all before the load task reaches the line that sets
     * {@code LOADING}. So the initialize handshake, the first message every client sends, ran
     * with the state still {@code NOT_LOADED} and took the fully-loaded path, which is the
     * defect mcp#65 is about, one state earlier.</p>
     *
     * <p>{@code NOT_LOADED} is terminal only when neither a workspace file nor the project
     * environment variable exists — and in exactly that case nothing installed a workspace
     * identity, so no reader of this asks the question at all.</p>
     */
    public boolean loadPending() {
        return this == NOT_LOADED || this == LOADING;
    }
}
