package org.jawata.catalogue;

/**
 * One row of a published catalogue of refactorings or smells — its key and its name, and
 * nothing else.
 *
 * <h2>Why the row carries no operation</h2>
 *
 * <p>Until Sprint 28g the row was {@code (number, name, operation)} and lived in a test, where
 * the third field named the one engine kind that performs it. That shape cannot survive a
 * second language: {@code extract kind=method} is a Java engine's spelling, and a C++ engine
 * performing the same refactoring would need the row to say something different. So the row
 * says WHAT the refactoring is, and each engine declares separately what IT can do about this
 * key — see {@code Coverage} on the engine side. A row is a fact about the literature; what
 * can be done about it is a fact about a tool.</p>
 *
 * <h2>The key is namespaced by its origin, from the first commit</h2>
 *
 * <p>{@code fowler:23}, not {@code 23}. The number belongs to somebody else's published
 * catalogue and is how a reader looks the row up, so it is never renumbered — and a later
 * {@code cpp:rule-of-five} or {@code rust:clippy-needless-clone} row joins the same table
 * without retrofitting a scheme onto the rows already there. Prefixing from the start costs
 * one colon; adding it afterwards would mean rewriting every reference in the product.</p>
 *
 * @param key  the origin-namespaced key, {@code <origin>:<id>}
 * @param name the row's name in its own catalogue, verbatim
 */
public record CatalogueRow(String key, String name) {

    /** The separator between a key's origin and its id within that origin. */
    public static final char SEPARATOR = ':';

    public CatalogueRow {
        if (key == null || key.indexOf(SEPARATOR) <= 0) {
            throw new IllegalArgumentException(
                    "a catalogue key must be namespaced as <origin>:<id>, so a second language's "
                            + "rows can join this table without renumbering: got " + key);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a catalogue row must carry its name: " + key);
        }
    }

    /** Whose catalogue this row came from — {@code fowler} for {@code fowler:23}. */
    public String origin() {
        return key.substring(0, key.indexOf(SEPARATOR));
    }

    /** The row's id within its own catalogue — {@code 23} for {@code fowler:23}. */
    public String id() {
        return key.substring(key.indexOf(SEPARATOR) + 1);
    }
}
