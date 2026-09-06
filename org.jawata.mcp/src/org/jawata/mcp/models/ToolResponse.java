package org.jawata.mcp.models;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standard response wrapper for all tool operations.
 * Provides consistent structure with success/error status, data, and metadata.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolResponse {

    private boolean success;
    private Object data;
    private ErrorInfo error;
    private ResponseMeta meta;

    private ToolResponse() {
        // Use factory methods
    }

    public boolean isSuccess() {
        return success;
    }

    public Object getData() {
        return data;
    }

    public ErrorInfo getError() {
        return error;
    }

    public ResponseMeta getMeta() {
        return meta;
    }

    /**
     * THE SAME REFUSAL, NOW NAMING THE SMALLER STEP IT LEAVES THE CALLER (S8b step 7, D3a).
     *
     * <p>Composes with every existing factory rather than duplicating them: a refusal site
     * keeps whatever it already builds — parameter, sentence, reason code — and appends this.
     * Adding a next-step argument to each factory instead would have widened four signatures
     * and left the sites that do not point anywhere carrying a null.</p>
     *
     * <p>A no-op on a success, on a response with no error, and where there is no step —
     * because "this refusal leaves you nothing smaller" is a real answer and the wire says it
     * by omission, exactly as {@link ErrorInfo#getReason()} does.</p>
     */
    public ToolResponse withNextStep(NextStep step) {
        if (success || error == null || step == null) {
            return this;
        }
        this.error = error.withNextStep(step);
        return this;
    }

    /**
     * Sprint 22 (POST layer): central steering injection. On a successful
     * response, attach the directional next-step nudge unless the tool already
     * set one. No-op on errors or when {@code steering} is null.
     */
    public void applySteering(String steering) {
        if (!success || steering == null) {
            return;
        }
        if (meta == null) {
            meta = ResponseMeta.builder().steering(steering).build();
        } else if (meta.getSteering() == null) {
            meta.setSteering(steering);
        }
    }

    /**
     * Sprint 26: APPEND a steering block — composes with (never replaces) the
     * tool's own steering line. Used by the watch engine and the server-side
     * checks; no-op on errors and blank blocks.
     */
    public void appendSteering(String block) {
        if (!success || block == null || block.isBlank()) {
            return;
        }
        if (meta == null) {
            meta = ResponseMeta.builder().steering(block).build();
            return;
        }
        String current = meta.getSteering();
        meta.setSteering(current == null || current.isBlank()
            ? block : current + "\n" + block);
    }

    /**
     * Create a successful response with data and optional metadata.
     */
    public static ToolResponse success(Object data) {
        return success(data, null);
    }

    /**
     * Create a successful response with data and metadata.
     */
    public static ToolResponse success(Object data, ResponseMeta meta) {
        ToolResponse response = new ToolResponse();
        response.success = true;
        response.data = data;
        response.meta = meta;
        return response;
    }

    /**
     * Create an error response with error info.
     */
    public static ToolResponse error(ErrorInfo error) {
        ToolResponse response = new ToolResponse();
        response.success = false;
        response.error = error;
        return response;
    }

    /**
     * Create an error response with code, message, and optional hint.
     */
    public static ToolResponse error(String code, String message, String hint) {
        return error(new ErrorInfo(code, message, hint));
    }

    /**
     * An error that carries the DIAGNOSIS, not just the verdict.
     *
     * <p>A bare error code moves the mystery up one level: the caller now knows something is
     * wrong and still has to go and find out what. When a refusal is caused by something in
     * the USER's world — a broken project, a missing directory — the response must say which
     * one, what is wrong with it, and what to do about it, so it can be fixed at a glance
     * rather than investigated.</p>
     */
    public static ToolResponse error(String code, String message, String hint, Object data) {
        ToolResponse response = error(new ErrorInfo(code, message, hint));
        response.data = data;
        return response;
    }

    /**
     * Create a project not loaded error.
     */
    public static ToolResponse projectNotLoaded() {
        return error(ErrorInfo.projectNotLoaded());
    }

    /**
     * Create a project loading in progress response.
     * Used when auto-load is still running asynchronously.
     */
    public static ToolResponse projectLoading() {
        return error("PROJECT_LOADING",
            "Project is loading, please wait...",
            "The project is being loaded asynchronously. Call health_check to monitor loading status.");
    }

    /**
     * Create a project load failed error.
     */
    public static ToolResponse projectLoadFailed(String errorMessage) {
        return error("PROJECT_LOAD_FAILED",
            "Project failed to load: " + errorMessage,
            "Check the project path and ensure it's a valid Java project.");
    }

    /**
     * Create a file not found error.
     */
    public static ToolResponse fileNotFound(String path) {
        return error(ErrorInfo.fileNotFound(path));
    }

    /**
     * Create a symbol not found error.
     *
     * <p>Sprint 28a (D11): the hint names WHICH workspace looked and failed. A bare
     * "not found" from one of several jawata servers reads as "does not exist",
     * when the truth is "does not exist HERE" — the enriched hint sends the agent
     * to its other workspace servers instead of to a wrong conclusion.</p>
     */
    public static ToolResponse symbolNotFound(String symbol) {
        ErrorInfo base = ErrorInfo.symbolNotFound(symbol);
        String elsewhere = WorkspaceIdentity.elsewhereHint();
        if (elsewhere == null) {
            return error(base);
        }
        return error(new ErrorInfo(base.getCode(), base.getMessage(),
            joinHints(base.getHint(), elsewhere)));
    }

    /**
     * Join two hint sentences so the reader sees two sentences (mcp#32).
     *
     * <p>Concatenating with a bare space produced
     * "…find available symbols This is the 'x' workspace…" — one run-on line in
     * every not-found answer. A hint that already ends in punctuation is left
     * alone.</p>
     */
    private static String joinHints(String base, String addition) {
        if (base == null || base.isBlank()) {
            return addition;
        }
        String trimmed = base.stripTrailing();
        boolean ended = trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")
            || trimmed.endsWith(":") || trimmed.endsWith(";");
        return trimmed + (ended ? " " : ". ") + addition;
    }

    /**
     * Create an invalid coordinates error.
     */
    public static ToolResponse invalidCoordinates(int line, int column, String reason) {
        return error(ErrorInfo.invalidCoordinates(line, column, reason));
    }

    /**
     * Create an invalid parameter error.
     */
    public static ToolResponse invalidParameter(String param, String reason) {
        return error(ErrorInfo.invalidParameter(param, reason));
    }

    /**
     * The same, naming WHICH precondition declined so a caller need not read the prose —
     * see {@link ErrorInfo} for the defect that motivated it.
     */
    public static ToolResponse invalidParameter(String param, String reason, String reasonCode) {
        return error(ErrorInfo.invalidParameter(param, reason, reasonCode));
    }

    /**
     * bugs.md #11 (Sprint 14): create a PROJECT_KEY_DROPPED error for a key
     * that was valid earlier in the session but has since been unloaded.
     */
    public static ToolResponse projectKeyDropped(String projectKey, long droppedAtMillis) {
        return error(ErrorInfo.projectKeyDropped(projectKey, droppedAtMillis));
    }

    /**
     * Create a security violation error.
     */
    public static ToolResponse securityViolation(String reason) {
        return error(ErrorInfo.securityViolation(reason));
    }

    /**
     * Create an internal error response.
     */
    public static ToolResponse internalError(String message) {
        return error(ErrorInfo.internalError(message));
    }

    /**
     * Create an internal error response from an exception.
     *
     * <p>Carries the exception CLASS and top frame — "Internal error: null"
     * (an NPE's message) identifies nothing, and cost a live diagnosis
     * exactly that way (C13-c).</p>
     */
    public static ToolResponse internalError(Throwable e) {
        return error(ErrorInfo.internalError(e.getClass().getSimpleName()
            + (e.getMessage() != null ? ": " + e.getMessage() : "")
            + (e.getStackTrace().length > 0 ? " at " + e.getStackTrace()[0] : "")));
    }
}
