package com.example;

/**
 * ROW 57 REFUSES this: it is itself extended, and delegation is not inherited — its own subclass
 * would lose everything the {@code extends} currently gives it.
 *
 * <p>It overrides nothing of {@link Ledgering} and nothing holds one as a {@code Ledgering}, so
 * the three refusals ahead of the subclass check cannot answer instead.</p>
 */
public class ExtendedReuse extends Ledgering {

    public ExtendedReuse(String book) {
        super(book);
    }

    public int reused() {
        return 1;
    }
}
