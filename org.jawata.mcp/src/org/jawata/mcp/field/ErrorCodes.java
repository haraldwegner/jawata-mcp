package org.jawata.mcp.field;

import org.jawata.mcp.models.ErrorInfo;
import org.jawata.mcp.models.ToolResponse;

/**
 * The single mapping from a {@link ToolResponse} failure to the error-code
 * token (Sprint 28b, D1). Structured error CODES are UPPER_SNAKE and pass the
 * {@link Token} whitelist; anything else — including a code that somehow
 * carries free text — coerces to {@code INTERNAL_ERROR}. The message string is
 * never consulted: messages embed paths and identifiers, which is why the
 * allowlist bans them wholesale. The leak corpus tests this seam.
 */
public final class ErrorCodes {

    static final Token INTERNAL = new Token(ErrorInfo.INTERNAL_ERROR);

    private ErrorCodes() {
    }

    /** The error-code token for a completed call; {@link Token#UNKNOWN} for a
     *  success (the field is unused then). */
    public static Token of(ToolResponse response) {
        if (response == null || response.isSuccess()) {
            return Token.UNKNOWN;
        }
        ErrorInfo error = response.getError();
        if (error == null || error.getCode() == null) {
            return INTERNAL;
        }
        Token token = Token.of(error.getCode());
        return token == Token.UNKNOWN ? INTERNAL : token;
    }
}
