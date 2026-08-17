package org.jawata.mcp.field;

import java.util.regex.Pattern;

/**
 * A whitelist-validated identifier — the field-recording layer's unit of text
 * (Sprint 28b, D1). A {@code Token} can only ever hold a bare lower-snake or
 * UPPER_SNAKE identifier of at most 40 characters; a file path, an error
 * message, a fully-qualified symbol or any free text cannot pass the pattern,
 * so the leak class dies at the type level rather than in a filter
 * (ARCHITECTURE-field-recordings-28b.md).
 *
 * <p>{@link #of(String)} COERCES instead of throwing: anything that is not a
 * bare identifier becomes {@link #UNKNOWN}. That is the sanitizing move — a
 * rejected value must not surface anywhere, not even in an exception message.</p>
 */
public record Token(String value) {

    private static final Pattern OK = Pattern.compile("[a-z0-9_]{1,40}|[A-Z0-9_]{1,40}");

    /** The coercion target for every value the whitelist rejects. */
    public static final Token UNKNOWN = new Token("unknown");

    public Token {
        if (value == null || !OK.matcher(value).matches()) {
            // Deliberately does NOT include the offending value — it may be
            // exactly the free text this type exists to keep out.
            throw new IllegalArgumentException("not a bare identifier token");
        }
    }

    /** Sanitizing factory: a valid identifier passes; everything else — null,
     *  paths, messages, symbols — becomes {@link #UNKNOWN}. */
    public static Token of(String raw) {
        return raw != null && OK.matcher(raw).matches() ? new Token(raw) : UNKNOWN;
    }
}
