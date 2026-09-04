package com.example;

/**
 * Fixture for Move Field (Sprint 28d-rescue, row 23).
 *
 * <p>{@code SHARED_LIMIT} is static and is read from another class, so moving it must
 * rewrite that reference too — the half of this refactoring a declaration-only move gets
 * wrong. {@code instanceCount} is the instance case the tool refuses, and it is here so
 * the refusal has something real to refuse.</p>
 */
public class MoveFieldSource {

    /** Static, and used from MoveFieldUser — the reference must travel with it. */
    public static final int SHARED_LIMIT = 42;

    /** The receiver an instance move is rewritten through — named by the caller. */
    private final MoveFieldTarget target = new MoveFieldTarget();

    /** Instance state, private, so every access to it lives in this file. */
    private int instanceCount;

    /** Not private, so its readers are outside this file's reach — the refused case. */
    public int exposed;

    public int bump() {
        instanceCount++;
        return instanceCount;
    }

    public int limit() {
        return SHARED_LIMIT;
    }
}
