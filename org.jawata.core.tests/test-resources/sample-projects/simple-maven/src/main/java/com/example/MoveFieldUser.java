package com.example;

/**
 * A second reader of the moved field, in another class.
 *
 * <p>This is what makes the fixture worth having: a move that relocates the declaration
 * and leaves this reference behind produces code that does not compile, and the compile
 * gate would catch it — but only because somebody wrote a reference for it to break.</p>
 */
public class MoveFieldUser {

    public int doubled() {
        return MoveFieldSource.SHARED_LIMIT * 2;
    }
}
