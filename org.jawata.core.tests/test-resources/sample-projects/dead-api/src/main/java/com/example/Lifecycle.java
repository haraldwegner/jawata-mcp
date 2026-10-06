package com.example;

/**
 * The census's row-76 case: a public interface whose members were deleted by hand, because
 * removing a declaration that implementors satisfy is not a deletion of one member.
 *
 * <p>{@link #shutdown()} has ZERO references — nobody calls it through this interface — and
 * deleting it would leave {@link Service#shutdown()} overriding nothing. The search result and
 * the dead case are identical; only the implementor list tells them apart.</p>
 */
public interface Lifecycle {

    void shutdown();
}
