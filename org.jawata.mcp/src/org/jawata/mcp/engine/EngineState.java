package org.jawata.mcp.engine;

/**
 * WHETHER THE ENGINE HAS A WORKSPACE, in one place: loading, loaded, or failed with a reason (Sprint 28k, E13; contract §4.3).
 *
 * <p>One per JVM, because one engine runs per JVM. It used to be two private fields of {@code JawataApplication}, read through
 * static methods on that class by the tool gate, the degradation stamp and the load tools, so everything that wanted to ask
 * "is a workspace ready" had to name the application class.</p>
 *
 * <p><b>Until the engine has booted, the answer is NOT_LOADED and a load that is noted changes nothing.</b> That is what the
 * fields had: the state lived on the application instance, and a read or a note with no instance answered
 * {@code NOT_LOADED} or did nothing. A test that loads a project through a tool, with no engine started, must not leave a
 * global "loaded" behind for the next test, so the same rule holds here.</p>
 */
public final class EngineState {

    private static final EngineState CURRENT = new EngineState();

    private volatile boolean booted;
    private volatile ProjectLoadingState loading = ProjectLoadingState.NOT_LOADED;
    private volatile String loadingError;

    private EngineState() {
    }

    /** The engine's state; the same object for the life of the JVM. */
    public static EngineState current() {
        return CURRENT;
    }

    /** The engine starts: from here its own loading state is what is answered. */
    public void booted() {
        booted = true;
    }

    /** Where the workspace load stands; NOT_LOADED while the engine has not booted. */
    public ProjectLoadingState loading() {
        return booted ? loading : ProjectLoadingState.NOT_LOADED;
    }

    /** Why the load failed, or null; null while the engine has not booted. */
    public String loadingError() {
        return booted ? loadingError : null;
    }

    /** A workspace load has begun. */
    public void loadStarted() {
        loading = ProjectLoadingState.LOADING;
    }

    /** The boot concluded that the workspace is loaded. */
    public void loadSucceeded() {
        loading = ProjectLoadingState.LOADED;
    }

    /** The boot concluded that the load failed, and why. */
    public void loadFailed(String reason) {
        loadingError = reason;
        loading = ProjectLoadingState.FAILED;
    }

    /**
     * A LOAD OUTSIDE THE BOOT PATH SUCCEEDED — clear whatever the boot concluded (mcp#35).
     *
     * <p>The boot paths wrote the state; {@code load_project} and {@code add_project} install a service without touching it. So
     * after a FAILED boot followed by a successful {@code load_project} the workspace was fine and {@code health_check} still
     * answered "Project load failed" with the original boot error, indefinitely: a stale alarm nothing could clear, which
     * teaches a caller to disregard the one channel that reports real failures. The tool gate consults this state only when NO
     * project is loaded, so the moment a load succeeded the stale verdict was invisible everywhere but {@code health_check}.</p>
     *
     * <p>Does nothing until the engine has booted.</p>
     */
    public void workspaceLoaded() {
        if (booted) {
            loading = ProjectLoadingState.LOADED;
            loadingError = null;
        }
    }

    /** Stand in a state without booting an engine; the application's package-private test hook is the one caller. */
    public void set(ProjectLoadingState state, String error) {
        loadingError = error;
        loading = state;
        booted = true;
    }

    /** Back to a JVM with no engine: NOT_LOADED, no error, not booted. The application's test hook is the one caller. */
    public void reset() {
        booted = false;
        loading = ProjectLoadingState.NOT_LOADED;
        loadingError = null;
    }
}
