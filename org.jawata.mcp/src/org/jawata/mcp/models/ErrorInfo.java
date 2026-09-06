package org.jawata.mcp.models;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Error information for tool failures.
 * Provides machine-readable code, human-readable message, and helpful hint for AI.
 *
 * <h2>{@code code} is COARSE; {@code reason} says which refusal fired</h2>
 *
 * <p>One operation declines for many different reasons and every one of them arrives as
 * {@code INVALID_PARAMETER} with a different sentence. That leaves a caller — and a test — no
 * way to tell them apart but to search the prose, and searching prose is how the following
 * shipped in Sprint 28d-rescue's Stage 5: a test asserted the message contained
 * {@code "Adjustable"} to prove the override refusal had fired, and PASSED when the operation
 * instead performed the change and the COMPILE GATE undid it — because the compiler's own
 * error names the interface too. The branch the test was written for never ran.</p>
 *
 * <p>So a refusal may carry a {@code reason}: a short constant naming WHICH precondition
 * declined, beside the sentence rather than instead of it. The sentence is for the human and
 * stays exactly as it was; the reason is what a test or an agent should branch on. It is
 * nullable and omitted from the wire when absent, so a refusal that has not adopted one is
 * unchanged.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorInfo {

    private final String code;
    private final String message;
    private final String hint;
    private final String reason;
    private final NextStep nextStep;

    public ErrorInfo(String code, String message, String hint) {
        this(code, message, hint, null);
    }

    public ErrorInfo(String code, String message, String hint, String reason) {
        this(code, message, hint, reason, null);
    }

    public ErrorInfo(String code, String message, String hint, String reason,
                     NextStep nextStep) {
        this.code = code;
        this.message = message;
        this.hint = hint;
        this.reason = reason;
        this.nextStep = nextStep;
    }

    /**
     * THE SMALLER STEP THIS REFUSAL LEAVES THE CALLER, or null where none applies.
     *
     * <p>A refusal that names its successor IN PROSE — "run {@code data
     * kind=encapsulate_field} on each public field first" — is readable and is something the
     * caller has to parse back into a call. This is the same answer in the shape the finding
     * channel already publishes, so an agent reads a refusal's pointer exactly as it reads a
     * cure: {@code operation}, {@code arguments}, and a {@code discriminator} where there is a
     * neighbour to be told apart from.</p>
     *
     * <p>Nullable and omitted from the wire when absent, for the reason {@link #getReason()}
     * is: a refusal that has nothing smaller to offer says nothing rather than saying nothing
     * loudly, and every refusal that has not adopted one is unchanged.</p>
     */
    public NextStep getNextStep() {
        return nextStep;
    }

    /** The same refusal, now naming what to do instead. */
    public ErrorInfo withNextStep(NextStep step) {
        return new ErrorInfo(code, message, hint, reason, step);
    }

    public String getCode() {
        return code;
    }

    /**
     * WHICH precondition declined, or null where the refusal has not adopted one.
     *
     * <p>Fine-grained where {@link #getCode()} is coarse: every precondition failure of a
     * refactoring shares the code {@code INVALID_PARAMETER} and differs only in its prose.</p>
     */
    public String getReason() {
        return reason;
    }

    public String getMessage() {
        return message;
    }

    public String getHint() {
        return hint;
    }

    @Override
    public String toString() {
        return code + " / " + message;
    }

    // Standard error codes
    public static final String PROJECT_NOT_LOADED = "PROJECT_NOT_LOADED";
    public static final String FILE_NOT_FOUND = "FILE_NOT_FOUND";
    public static final String SYMBOL_NOT_FOUND = "SYMBOL_NOT_FOUND";
    public static final String INVALID_COORDINATES = "INVALID_COORDINATES";
    public static final String INVALID_PARAMETER = "INVALID_PARAMETER";
    /**
     * Sprint 14 (bugs.md #11): the projectKey passed by the caller WAS valid
     * earlier in this session but has since been dropped (e.g. via
     * remove_project or a manager-side workspace mutation). Distinct from
     * INVALID_PARAMETER so an agent can detect the workspace-state shift and
     * re-acquire via {@code list_projects} instead of treating the key as a
     * typo. The error message carries the drop timestamp.
     */
    public static final String PROJECT_KEY_DROPPED = "PROJECT_KEY_DROPPED";
    public static final String SECURITY_VIOLATION = "SECURITY_VIOLATION";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String REFACTORING_FAILED = "REFACTORING_FAILED";

    // Factory methods for common errors
    public static ErrorInfo projectNotLoaded() {
        return new ErrorInfo(
            PROJECT_NOT_LOADED,
            "No project loaded. Call load_project first.",
            "Use load_project with the path to your Java project root"
        );
    }

    public static ErrorInfo fileNotFound(String path) {
        return new ErrorInfo(
            FILE_NOT_FOUND,
            "File not found: " + path,
            "Verify the file path is correct and the file exists"
        );
    }

    public static ErrorInfo symbolNotFound(String symbol) {
        return new ErrorInfo(
            SYMBOL_NOT_FOUND,
            "Symbol not found: " + symbol,
            "Use search_symbols to find available symbols"
        );
    }

    public static ErrorInfo invalidCoordinates(int line, int column, String reason) {
        return new ErrorInfo(
            INVALID_COORDINATES,
            String.format("Invalid coordinates (line=%d, column=%d): %s", line, column, reason),
            "Remember: coordinates are zero-based. Editor line 1 = line 0 in API"
        );
    }

    public static ErrorInfo invalidParameter(String param, String reason) {
        return invalidParameter(param, reason, null);
    }

    /**
     * The same, carrying a constant that names WHICH precondition declined — see the class
     * javadoc for why the sentence alone is not enough to branch on.
     */
    public static ErrorInfo invalidParameter(String param, String reason, String reasonCode) {
        return new ErrorInfo(
            INVALID_PARAMETER,
            String.format("Invalid parameter '%s': %s", param, reason),
            "Check the tool's inputSchema (tools/list) for the required parameters and their expected shapes.",
            reasonCode
        );
    }

    public static ErrorInfo projectKeyDropped(String projectKey, long droppedAtMillis) {
        String when = java.time.Instant.ofEpochMilli(droppedAtMillis).toString();
        return new ErrorInfo(
            PROJECT_KEY_DROPPED,
            String.format("Project '%s' was unloaded at %s", projectKey, when),
            "Re-acquire via list_projects; the workspace's loaded-project set changed mid-session."
        );
    }

    public static ErrorInfo securityViolation(String reason) {
        return new ErrorInfo(
            SECURITY_VIOLATION,
            "Security violation: " + reason,
            "Ensure file paths are within the project directory"
        );
    }

    public static ErrorInfo timeout(String operation, int seconds) {
        return new ErrorInfo(
            TIMEOUT,
            String.format("Operation '%s' timed out after %d seconds", operation, seconds),
            "Try reducing the scope of the operation or increase timeout"
        );
    }

    public static ErrorInfo internalError(String message) {
        return new ErrorInfo(
            INTERNAL_ERROR,
            "Internal error: " + message,
            "This may be a bug. Check server logs for details."
        );
    }

    public static ErrorInfo refactoringFailed(String reason) {
        return new ErrorInfo(
            REFACTORING_FAILED,
            "Refactoring failed: " + reason,
            "Check preconditions: valid identifier, no conflicts"
        );
    }
}
